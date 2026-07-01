# Uptimererer — Implementation Plan

Uptimererer is a serverless uptime checker: it periodically probes a configurable
list of websites over HTTP/HTTPS, tracks their up/down state, and emails
subscribers when a site goes offline or recovers. This plan implements the
architecture in [architecture-diagram.png](architecture-diagram.png) as a Java
(Maven multi-module) monorepo, deployed with the AWS CDK to either real AWS or a
local [Floci](https://floci.io/) emulator.

> **Status:** the checker Lambda is implemented in Java 21. The other Lambdas
> and the CDK app are not built yet; the checker stack is currently stood up on
> the emulator by the `deploy` CLI (see the [README](../README.md)) rather than
> CDK. The sections below describe the intended end state.

## 1. Architecture recap

From the diagram, one check cycle flows as:

| # | Component | Role |
|---|-----------|------|
| 1 | EventBridge scheduled rule | Fires every minute ("tick") |
| 2 | SQS queue | Buffers ticks; gives us retries + a DLQ |
| 3 | **Dispatcher Lambda** | Consumes ticks, loads URL configs from SSM, consults current state in DynamoDB, decides which checks to run |
| 4 | EventBridge custom bus | Carries one `CheckRequested` event per site (fan-out so checks run in parallel) |
| 5 | **Checker Lambda** | Makes the actual HTTP/HTTPS request, writes the result and up/down state to DynamoDB |
| 6 | DynamoDB table | Stores per-site state (status, last check, failure streak) |
| 7 | **Notifier Lambda** + SNS topic | Reacts to state *transitions* (UP→DOWN, DOWN→UP) via DynamoDB Streams and publishes to SNS, which emails subscribers |
| 8 | SSM Parameter Store | Holds the list of sites to monitor (JSON) |

Interpretation notes on the diagram:

- The diagram draws DynamoDB → SNS directly. DynamoDB cannot target SNS
  directly, so we implement that arrow as **DynamoDB Streams → Notifier Lambda
  → SNS** (see risk R2 for a fallback).
- The long arrows between the SQS/DynamoDB area are read as: the dispatcher
  reads current site state from DynamoDB when deciding what to check
  (enables per-site check intervals later without changing the topology).

## 2. Repository layout

Maven multi-module reactor, one deployable module per Lambda plus a shared
`core` library:

```
uptimererer/
├── pom.xml                      # parent (reactor): shared versions + plugins
├── Makefile                     # build, test, deploy-local, deploy-aws
├── docker-compose.yml           # Floci emulator
├── core/                        # shared library used by every Lambda
│   └── src/main/java/com/adamblakey/uptimererer/
│       ├── events/              # shared event types (CheckRequested, SiteConfig)
│       ├── probe/               # HTTP/HTTPS check logic (pure, unit-testable)
│       ├── state/               # DynamoDB repository + transition rules
│       └── json/                # shared Jackson configuration
├── dispatcher/                  # Lambda 1: decides requests to make (planned)
├── checker/                     # Lambda 2: makes network requests → shaded jar
├── notifier/                    # Lambda 3: DDB stream -> SNS (planned)
├── deploy/                      # CLI that provisions the stack on the emulator
├── infra/                       # CDK app (planned)
└── docs/
    ├── architecture-diagram.png
    └── implementation-plan.md
```

Why a Maven reactor rather than one flat module: each Lambda is packaged and
deployed independently (as its own shaded jar), while all of them share the
`core` contracts. The reactor builds them together and keeps the shared code in
one place; `core` carries no Lambda-runtime dependencies so it stays cheap to
reuse and unit-test.

## 3. Technology choices

- **Java 21** for all three Lambdas and the CDK app (single-language repo).
- **AWS SDK for Java v2** — natively honours `AWS_ENDPOINT_URL`, which is how the
  Lambdas find Floci's services when running locally, with zero code branching.
  Clients pin the URL-connection HTTP client for lean, deterministic startup.
- **Lambda runtime `java21`**, handler
  `com.adamblakey.uptimererer.checker.CheckerHandler::handleRequest`, packaged as
  a shaded ("uber") jar via the Maven Shade plugin. The jar is
  architecture-independent, so the same artifact runs on x86_64 or Graviton.
- **`java.net.http.HttpClient`** for the probe — no third-party HTTP dependency;
  it validates TLS and caps redirects out of the box.
- **CDK v2 in Java** (`software.amazon.awscdk` bindings). Lambda code is shipped
  as the prebuilt shaded jars via `Code.fromAsset` — no Docker bundling, which
  keeps `cdklocal` deploys to Floci fast and reliable.
- **Floci** for local runs: LocalStack-compatible emulator on
  `http://localhost:4566`, accepts dummy credentials, deployed to with
  `cdklocal` (the `aws-cdk-local` npm wrapper).

## 4. Contracts

### 4.1 Site configuration (SSM Parameter Store)

One parameter, `/uptimererer/sites`, type `String`, JSON:

```json
{
  "sites": [
    {
      "id": "example",
      "url": "https://example.com/health",
      "method": "GET",
      "timeoutSeconds": 10,
      "expectedStatusCodes": [200, 204],
      "failuresBeforeDown": 3
    }
  ]
}
```

`id` is the stable key used everywhere (DynamoDB PK, event field, email text).
`method`, `timeoutSeconds`, `expectedStatusCodes`, `failuresBeforeDown` are
optional with the defaults shown. HTTPS vs HTTP is simply the URL scheme; the
checker follows redirects up to a small limit and validates TLS by default.

### 4.2 `CheckRequested` event (EventBridge custom bus)

- Bus: `uptimererer-bus`; Source: `uptimererer.dispatcher`;
  DetailType: `CheckRequested`.
- Detail: the full site config object plus `requestedAt` (RFC 3339). Embedding
  the config means the checker never reads SSM itself.

### 4.3 DynamoDB table `uptimererer-state`

- PK: `siteId` (S). Single item per site, no sort key needed for the MVP.
- Attributes: `status` (`UP` | `DOWN` | `UNKNOWN`), `consecutiveFailures` (N),
  `lastCheckedAt`, `lastStatusChangeAt`, `lastHttpStatus` (N), `latencyMs` (N),
  `lastError` (S, optional).
- Streams enabled, `NEW_AND_OLD_IMAGES` (the notifier needs both to detect
  transitions). Billing mode `PAY_PER_REQUEST`.
- Check *history* (per-check records with a time sort key) is deliberately out
  of scope for the MVP — see milestone M6.

### 4.4 Notifications (SNS)

Topic `uptimererer-alerts` with an email subscription (address supplied at
deploy time, e.g. `cdk deploy -c alertEmail=you@example.com`). Message subject:
`[Uptimererer] example is DOWN` / `... is UP`, body includes URL, previous
status, timestamp, and last error/HTTP status.

## 5. Application specs

### 5.1 Dispatcher (`dispatcher`)

Trigger: SQS event source mapping on the tick queue (batch size 1).

1. Read and parse `/uptimererer/sites` from SSM; on parse failure, log and
   return an error so the tick lands in the DLQ (visible failure, no silent
   skips).
2. `PutEvents` one `CheckRequested` per site to the custom bus, batched 10 per
   call (the EventBridge API limit).
3. MVP checks every site on every tick (once a minute). The DynamoDB read
   (`lastCheckedAt`) is wired in but only becomes meaningful when per-site
   `intervalSeconds` support is added (M6).

Duplicate ticks are harmless: checks are idempotent reads and the state
machine in the checker tolerates repeated results.

### 5.2 Checker (`checker`)

Trigger: EventBridge rule on the bus (`source = uptimererer.dispatcher`,
`detail-type = CheckRequested`), async invocation with retries=2 and a DLQ.

1. Perform the request with the site's method/timeout; a result is **success**
   iff the request completes and the status code is in
   `expectedStatusCodes`. Capture latency, status code, or error string.
2. Read current state, then apply the transition rules:
   - success → `consecutiveFailures = 0`; if status was `DOWN`/`UNKNOWN`,
     set `UP` and update `lastStatusChangeAt`.
   - failure → increment `consecutiveFailures`; only flip to `DOWN` once the
     streak reaches `failuresBeforeDown` (anti-flapping).
3. Write the item with a conditional expression on `lastCheckedAt` so a stale
   overlapping invocation can't clobber a newer result.

The probe logic lives in `core` (`probe` package) as a pure method
(`Probe.check(SiteConfig) → ProbeResult`) so it's unit-testable against an
in-JVM `com.sun.net.httpserver` server — including TLS failure, timeout,
redirect, and wrong-status cases.

### 5.3 Notifier (`notifier`)

Trigger: DynamoDB Streams event source mapping (batch size 10, retry with
bisect-on-error).

For each `MODIFY`/`INSERT` record, compare `OldImage.status` vs
`NewImage.status`; if they differ (and new status isn't `UNKNOWN`), publish the
formatted alert to SNS. Everything else is ignored, so writes that only bump
`lastCheckedAt` cost nothing.

## 6. Infrastructure (CDK, `infra/`)

One stack, `UptimerererStack`, containing (roughly in dependency order):

1. DynamoDB table (streams on) — `RemovalPolicy.DESTROY` while pre-production.
2. SNS topic + email subscription from the `alertEmail` context value.
3. SSM parameter `/uptimererer/sites` seeded with a default JSON config
   (subsequent edits happen out-of-band via the console/CLI; CDK ignores
   drift on the value using `ignoreChanges` or by seeding only on create).
4. Tick queue + DLQ; checker DLQ.
5. Custom EventBridge bus + rule targeting the checker.
6. Scheduled rule `rate(1 minute)` on the *default* bus targeting the tick
   queue.
7. Three Lambda functions from the shaded jars (`*/target/*.jar`) with
   least-privilege grants: dispatcher (SSM read, DDB read, `events:PutEvents` on
   the bus), checker (DDB read/write), notifier (stream read, `sns:Publish`).
8. Stack outputs: table name, bus name, topic ARN — consumed by smoke tests.

Environment-specific values (table name, bus name, topic ARN, parameter name)
are passed to the Lambdas as environment variables by CDK; nothing is
hardcoded in Java.

## 7. Local development with Floci

`docker-compose.yml` runs the Floci emulator exposing `localhost:4566`.
Local flow:

```sh
make local-up        # docker compose up -d floci
make deploy-local    # builds the jars, then: cdklocal bootstrap && cdklocal deploy
make local-logs      # tail emulated Lambda logs
```

(Until the CDK app exists, `make deploy-local` provisions the checker stack via
the `deploy` CLI instead — see the [README](../README.md).)

`deploy-local` exports the standard dummy environment first:

```sh
export AWS_ENDPOINT_URL=http://localhost:4566
export AWS_DEFAULT_REGION=us-east-1
export AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test
```

Key points:

- `cdklocal` (npm `aws-cdk-local`) is the only extra tool vs. real AWS; the
  CDK app itself is identical for both targets.
- Inside emulated Lambdas the SDK picks up the emulator endpoint via
  `AWS_ENDPOINT_URL`; the Java code has no local/AWS branching.
- Email doesn't actually send locally. For local verification, `cdk.json`
  context flag `localTest=true` additionally subscribes an SQS queue to the
  SNS topic so tests (and humans) can assert that alerts fired.

## 8. Deploying to AWS

```sh
make deploy-aws      # builds zips, then: cdk deploy -c alertEmail=...
```

Prereqs: bootstrapped account/region (`cdk bootstrap`), credentials via the
usual chain. The email subscription requires one manual confirmation click on
first deploy. CI note: `make build && make test` on every push; deploys stay
manual until the project stabilises.

## 9. Testing strategy

1. **Unit tests** (pure JUnit 5, no emulator): the `probe` package against an
   in-JVM HTTP server; state-transition table tests for the checker's rules; SSM
   config parsing/validation; notifier transition detection on synthetic stream
   records.
2. **Infra assertions**: CDK `assertions` snapshot/fine-grained tests on the
   synthesized template (right triggers, grants, env vars).
3. **End-to-end against Floci** (Makefile target `make e2e`, also CI-able):
   deploy to Floci, point the SSM config at a locally running `httptest`-style
   container with a toggleable `/health` endpoint, flip it down, poll DynamoDB
   until status becomes `DOWN`, assert an alert message arrived on the test
   SQS queue, flip it up, assert recovery.
4. **Smoke test on AWS**: same script as e2e minus the toggle server, run
   against a known-good public URL.

## 10. Milestones

| # | Deliverable | Done when |
|---|-------------|-----------|
| M1 | Scaffolding: Maven reactor, Makefile, docker-compose, CI running `mvn verify` | `make build` produces the Lambda shaded jars |
| M2 | `core` packages: probe, sites, state, events — with unit tests | `make test` green |
| M3 | CDK stack + all three Lambdas wired, deployable to Floci | `make deploy-local` succeeds; manual tick → state appears in DynamoDB |
| M4 | Notifications: notifier + SNS + local test queue | e2e test passes locally (down + recovery emails observed) |
| M5 | AWS deploy: bootstrap, deploy, real email received | Smoke test green in a real account |
| M6 | Nice-to-haves (pick after M5): per-site `intervalSeconds`, check-history items with TTL, CloudWatch dashboard/alarms on DLQs, Slack webhook target | — |

## 11. Risks & open questions

- **R1 — Floci Lambda networking**: Floci docs note native-Linux Docker needs
  firewall (UFW) adjustments for container→host traffic. The e2e toggle
  server must be reachable from inside the Lambda container — run it on the
  compose network, not the host.
- **R2 — DynamoDB Streams on Floci**: supported in LocalStack and Floci claims
  compatibility, but verify early (M3). Fallback that preserves behaviour: the
  checker emits a `StatusChanged` event to the custom bus and a rule targets
  the notifier — only the trigger changes, not the notifier logic.
- **R3 — Diagram ambiguity**: the long SQS↔DynamoDB arrows are interpreted as
  the dispatcher reading state (§1). If they instead meant re-queueing failed
  checks into SQS for re-verification, that slots into M6 without topology
  changes.
- **Q1 — Check cadence**: MVP fixes it at one check per site per minute.
  Confirm whether per-site intervals are actually needed before building M6.
- **Q2 — Multi-region checking** (probing from several regions to rule out
  local network blips) is explicitly out of scope.

## References

- [Floci — local cloud emulator](https://floci.io/) · [quick start](https://floci.io/floci/getting-started/quick-start/) · [GitHub](https://github.com/floci-io/floci)
- [Floci CDK compatibility tests](https://github.com/floci-io/floci-compatibility-tests) (cdklocal bootstrap/deploy flow)

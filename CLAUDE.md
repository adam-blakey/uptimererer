# uptimererer

Uptime checker: a Quarkus app whose core piece (the "checkererer") is an
SQS-triggered Lambda that pings websites and records their state in DynamoDB.
See `docs/architecture-diagram.png` for the target architecture; only the
SQS → Lambda → DynamoDB slice is implemented so far.

## Toolchain

- JDK 25 (`maven.compiler.release=25`), always via the wrapper: `./mvnw`.
- Spotless (google-java-format) runs automatically at compile; don't hand-format.
- Everything AWS-shaped runs against a local emulator (Floci or LocalStack) on
  `:4566`, started from `docker-compose.yml`. Only one emulator can hold the
  port; the justfile handles swapping them.

## Layout

- `family.blakey.uptimererer.checkererer` — `CheckerererHandler` (Lambda entry
  point, `SQSEvent` → `SQSBatchResponse` with per-message failures), `Request`
  (message body `{"url": ...}` + validation), `HealthHandler` (`GET /health`),
  `CreateStateTable` (dev/test-only table bootstrap).
- `family.blakey.uptimererer.core.db` — `StateRecord` (DynamoDB enhanced-client
  schema; also derives the create-table request) and `StateRepository`.
- `family.blakey.uptimererer.core.models` — `Website` (a checked site's config:
  URL, method, timeout, expected status codes, failures-before-down; validates
  itself) and `Poll` (runs one `Website` check via `java.net.http.HttpClient`,
  returning a `Poll.Result`). Not yet wired into `CheckerererHandler`.
- `family.blakey.uptimererer.core.Helpers` — `describe(Exception)`, a shared
  fallback-to-class-name exception-message formatter.
- `family.blakey.uptimererer.deploy` — `DeployTool`, the local deploy CLI (see
  below). Its extra AWS SDK deps (`sqs`, `iam`, `lambda`) are `provided`-scope
  so they stay out of the Lambda `function.zip`; it runs via `exec:java` with
  `classpathScope=compile` for the same reason.

## Commands

- `just test` — runs the tests. They talk to DynamoDB on `:4566`, so start an
  emulator first (`just emulator-floci` or `emulator-localstack`).
- `just dev-floci` / `just dev-localstack` — emulator + `quarkus:dev` (hot
  reload). The handler is invoked through the Lambda mock event server on
  `:8082` (`:8083` in tests), not through a real queue; the state table is
  auto-created on startup (`CreateStateTable`).
- `just deploy-floci` / `just deploy-localstack` — the real thing, locally:
  builds `target/function.zip` and has `DeployTool` provision the state table,
  the `uptimererer-checks` queue, the `uptimererer-checkererer` function
  (Java 25 runtime, Quarkus stream handler) and the SQS event source mapping
  in the emulator. Idempotent; re-run to push new code.
- `just send https://example.com` — queue a check request.
- `just state` — print all site state records from DynamoDB.
- `just build` — build `function.zip` only (skips tests).
- `just local-down` — stop the emulator.

## DeployTool configuration (env vars, all optional)

- `AWS_ENDPOINT_URL` — emulator endpoint from this machine
  (default `http://localhost:4566`).
- `LAMBDA_DYNAMODB_ENDPOINT` — emulator endpoint as seen from *inside* the
  Lambda container, set on the function as `QUARKUS_DYNAMODB_ENDPOINT_OVERRIDE`
  (default `http://localhost.localstack.cloud:4566`, which LocalStack resolves;
  Floci may need a different value).
- `FUNCTION_ZIP` — path to the Lambda zip (default `target/function.zip`).

The state table name is the single source of truth in
`application.properties` (`uptimererer.state-table`); `DeployTool` reads it
from there rather than duplicating it.

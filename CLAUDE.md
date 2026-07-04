# uptimererer

Uptime checker: a Quarkus app implementing the full pipeline from
`docs/architecture-diagram.png` — an EventBridge scheduler ticks an SQS queue
every minute; the "decidererer" Lambda expands each tick into pending requests
(one per URL configured in Systems Manager Parameter Store) on a custom
EventBridge bus; the "checkererer" Lambda makes the network requests and
records website state in DynamoDB; the "notifierer" publishes to an SNS topic
(email) when a site goes offline or recovers. Both Lambdas are built from the
same artifact; `QUARKUS_LAMBDA_HANDLER` selects the named handler.

## Toolchain

- JDK 25 (`maven.compiler.release=25`), always via the wrapper: `./mvnw`.
- Spotless (google-java-format) runs automatically at compile; don't hand-format.
- Everything AWS-shaped runs against a local emulator (Floci or LocalStack) on
  `:4566`, started from `docker-compose.yml`. Only one emulator can hold the
  port; the Makefile handles swapping them.

## Layout

- `family.blakey.uptimererer.core.events` — the shared pipeline contract:
  `CheckRequest` (`{"url": ...}` + validation) and `CheckRequestedEvent` (the
  EventBridge envelope, source `uptimererer` / detail-type `check-requested`).
- `family.blakey.uptimererer.core.db` — `StateRecord` (DynamoDB enhanced-client
  schema: status UP/DOWN, lastCheckedAt, lastChangedAt; also derives the
  create-table request), `Status`, and `StateRepository`.
- `family.blakey.uptimererer.decidererer` — `DeciderererHandler` (Lambda entry
  point behind the SQS mapping, `SQSEvent` → `SQSBatchResponse` with
  per-message failures): a scheduler tick (JSON body without a `url`) fans out
  to every configured URL, a direct `{"url": ...}` message is forwarded as one
  pending request. `UrlConfigStore`/`SsmUrlConfigStore` (URL configs, one
  parameter per site under `uptimererer.url-parameter-prefix`) and
  `CheckRequestPublisher`/`EventBridgeCheckRequestPublisher` (PutEvents to the
  bus in chunks of ten).
- `family.blakey.uptimererer.checkererer` — `CheckerererHandler` (Lambda entry
  point behind the bus rule, one `CheckRequestedEvent` per invocation; a
  failed ping is a successful check — only processing failures throw),
  `HttpChecker` (GET with `uptimererer.check-timeout`, <400 after redirects is
  UP), `HealthHandler` (`GET /health`), `CreateStateTable` (dev/test-only
  table bootstrap).
- `family.blakey.uptimererer.notifierer` — `StatusChangeNotifier`: SNS publish
  on a status flip; without `uptimererer.notification-topic-arn` (dev/test) it
  only logs.
- `family.blakey.uptimererer.deploy` — `DeployTool`, the local deploy CLI (see
  below). Its extra AWS SDK deps (`sqs`, `iam`, `lambda`) are `provided`-scope
  so they stay out of the Lambda `function.zip`; it runs via `exec:java` with
  `classpathScope=compile` for the same reason.

## Commands

- `make test` — runs the tests. The `@QuarkusTest` ones talk to DynamoDB on
  `:4566`, so start an emulator first (`make emulator-floci` or
  `emulator-localstack`).
- `make dev-floci` / `make dev-localstack` — emulator + `quarkus:dev` (hot
  reload). The checkererer handler is invoked through the Lambda mock event
  server on `:8082` (`:8083` in tests), not through the real bus; the state
  table is auto-created on startup (`CreateStateTable`).
- `make deploy-floci` / `make deploy-localstack` — the real thing, locally:
  builds `target/function.zip` and has `DeployTool` provision the state table,
  the `uptimererer-checks` queue, the `uptimererer-bus` event bus, the
  `uptimererer-notifications` SNS topic, both functions (Java 25 runtime,
  Quarkus stream handler, `QUARKUS_LAMBDA_HANDLER` picks decidererer vs
  checkererer), the SQS event source mapping, the every-minute scheduler rule
  → queue, and the bus rule → checkererer. Idempotent; re-run to push new code.
- `make add-url URL=https://example.com [NAME=example]` — configure a URL for
  checking on every tick (Systems Manager parameter).
- `make urls` — print the configured URLs.
- `make send URL=https://example.com` — queue a one-off check request.
- `make tick` — queue a scheduler tick now (checks every configured URL).
- `make subscribe EMAIL=me@example.com` — email on/offline notifications.
- `make state` — print all site state records from DynamoDB.
- `make build` — just build `function.zip` (skips tests).
- `make local-down` — stop the emulator.

## DeployTool configuration (env vars, all optional)

- `AWS_ENDPOINT_URL` — emulator endpoint from this machine
  (default `http://localhost:4566`).
- `LAMBDA_AWS_ENDPOINT` — emulator endpoint as seen from *inside* the Lambda
  containers, set on the functions as the per-service
  `QUARKUS_*_ENDPOINT_OVERRIDE` variables (default
  `http://localhost.localstack.cloud:4566`, which LocalStack resolves; Floci
  may need a different value).
- `FUNCTION_ZIP` — path to the Lambda zip (default `target/function.zip`).

Resource names are single-sourced in `application.properties`
(`uptimererer.state-table`, `uptimererer.event-bus`,
`uptimererer.url-parameter-prefix`); `DeployTool` reads them from there rather
than duplicating them.

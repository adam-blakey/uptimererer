# Uptimererer

Serverless uptime checker: probes websites over HTTP/HTTPS, tracks up/down
state, and (eventually) emails subscribers on transitions. Architecture in
[docs/architecture-diagram.png](docs/architecture-diagram.png).

**Implemented so far: the checker Lambda.** It consumes `CheckRequested`
events from the `uptimererer-bus` EventBridge bus, performs the HTTP check,
and writes per-site state to the `uptimererer-state` DynamoDB table with
anti-flapping (a site only flips DOWN after `failuresBeforeDown` consecutive
failures) and a conditional write so overlapping checks can't clobber newer
results. The dispatcher and notifier Lambdas don't exist yet.

Written in Java 21, built with Maven as a multi-module reactor.

## Layout

- `core` — shared library:
  - `…events` — event contracts (`CheckRequested`, `SiteConfig` + defaults)
  - `…probe` — pure HTTP(S) check logic (`java.net.http`), unit-tested with an in-JVM server
  - `…state` — state-transition rules + DynamoDB repository
  - `…json` — shared Jackson configuration
- `checker` — the checker Lambda (`java21` runtime); packaged as a shaded jar
- `deploy` — Java CLI that provisions the stack against the local emulator (no AWS CLI / npm needed)

## Local development ([Floci](https://floci.io/) emulator)

Prereqs: JDK 21, Maven, Docker. No AWS account or credentials.

```sh
make local-up      # start the Floci emulator on :4566 (skip if already running)
make deploy-local  # build the jars and provision table + bus + rule + function
make check URL=https://example.com          # put a CheckRequested event on the bus
make state         # print site state records from DynamoDB
make local-down    # stop the emulator
```

To watch a site go DOWN quickly, lower the failure threshold and point it at
nothing:

```sh
make check URL=http://localhost:59999 ID=deadsite FAILURES=2   # run twice
make state
```

Checker logs stream through the emulator: `docker logs -f floci` (or
`uptimererer-floci` when started via `make local-up`).

Notes:

- The Java Lambda jar is architecture-independent; the deploy tool sets the
  function's runtime to `java21` and its architecture to `x86_64` (the local
  emulator's arch). Switch the architecture in `DeployTool` when targeting
  Graviton on real AWS — the same jar runs on either.
- Inside emulated Lambdas the AWS SDK finds the emulator via the injected
  `AWS_ENDPOINT_URL` — there is no local/AWS branching in the Java code.
- On native Linux with UFW, container→host traffic needs
  `sudo ufw allow in on docker0` (see Floci docs).

## Tests

```sh
make test
```

Covers the probe (status codes, timeout, connection refused, redirects +
loop limit, TLS validation), the state-transition table, and the event
contract defaults/validation.

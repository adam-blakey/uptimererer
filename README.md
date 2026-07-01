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

## Layout

- `cmd/checker` — the checker Lambda (`provided.al2023`, binary named `bootstrap`)
- `cmd/deploy` — Go deploy tool for the local emulator (no AWS CLI / npm needed)
- `internal/events` — shared event contracts (`CheckRequested`, site config + defaults)
- `internal/probe` — pure HTTP(S) check logic, unit-tested with `httptest`
- `internal/state` — state-transition rules + DynamoDB repository

## Local development ([Floci](https://floci.io/) emulator)

Prereqs: Go, Docker. No AWS account or credentials.

```sh
make local-up      # start the Floci emulator on :4566 (skip if already running)
make deploy-local  # build the Lambda and provision table + bus + rule + function
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

- The Lambda binary arch must match where it runs: the Makefile defaults to
  `GOARCH=amd64` for the local emulator; use `make build GOARCH=arm64` when
  targeting Graviton on real AWS. The deploy tool reads the arch from the
  built binary, so the function config always matches.
- Inside emulated Lambdas the AWS SDK finds the emulator via the injected
  `AWS_ENDPOINT_URL` — there is no local/AWS branching in the Go code.
- On native Linux with UFW, container→host traffic needs
  `sudo ufw allow in on docker0` (see Floci docs).

## Tests

```sh
make test
```

Covers the probe (status codes, timeout, connection refused, redirects +
loop limit, TLS validation), the state-transition table, and the event
contract defaults/validation.

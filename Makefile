# GOARCH must match where the Lambda runs: the local emulator executes on the
# host (amd64 here); use GOARCH=arm64 when targeting real AWS on Graviton.
GOARCH ?= amd64

LOCAL_ENV = AWS_ENDPOINT_URL=http://localhost:4566 \
            AWS_REGION=us-east-1 AWS_DEFAULT_REGION=us-east-1 \
            AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test

.PHONY: build test local-up local-down deploy-local check state

build:
	CGO_ENABLED=0 GOOS=linux GOARCH=$(GOARCH) go build -tags lambda.norpc -o dist/checker/bootstrap ./cmd/checker

test:
	go test ./...

local-up:
	docker compose up -d floci

local-down:
	docker compose down

# Works against any emulator on :4566 — run `make local-up` first if none is running.
deploy-local: build
	$(LOCAL_ENV) go run ./cmd/deploy deploy

# usage: make check URL=https://example.com [ID=example] [FAILURES=1]
check:
	@test -n "$(URL)" || { echo "usage: make check URL=https://example.com [ID=example] [FAILURES=1]"; exit 1; }
	$(LOCAL_ENV) go run ./cmd/deploy send -url "$(URL)" -id "$(ID)" -failures "$(or $(FAILURES),0)"

state:
	$(LOCAL_ENV) go run ./cmd/deploy state

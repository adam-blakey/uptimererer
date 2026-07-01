# The checker ships as a Java 21 Lambda (runtime java21). The jar is
# architecture-independent; the deploy tool sets the function's architecture.

LOCAL_ENV = AWS_ENDPOINT_URL=http://localhost:4566 \
            AWS_REGION=us-east-1 AWS_DEFAULT_REGION=us-east-1 \
            AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test

DEPLOY_JAR = deploy/target/uptimererer-deploy.jar

.PHONY: build test local-up local-down deploy-local check state clean

build:
	mvn -q -DskipTests package

test:
	mvn -q test

local-up:
	docker compose up -d floci

local-down:
	docker compose down

# Works against any emulator on :4566 — run `make local-up` first if none is running.
deploy-local: build
	$(LOCAL_ENV) java -jar $(DEPLOY_JAR) deploy

# usage: make check URL=https://example.com [ID=example] [FAILURES=0]
check:
	@test -n "$(URL)" || { echo "usage: make check URL=https://example.com [ID=example] [FAILURES=0]"; exit 1; }
	$(LOCAL_ENV) java -jar $(DEPLOY_JAR) send -url "$(URL)" -id "$(ID)" -failures "$(or $(FAILURES),0)"

state:
	$(LOCAL_ENV) java -jar $(DEPLOY_JAR) state

clean:
	mvn -q clean

# Maven's bundled Guava calls a deprecated sun.misc.Unsafe method, which the JVM
# warns about on JDK 24+. Silence it there; the flag doesn't exist on older JDKs,
# so only add it when the running JDK is 24 or newer.
JAVA_MAJOR := $(shell java -version 2>&1 | awk -F'"' '/version/ {print $$2}' | awk -F. '{print ($$1 == "1") ? $$2 : $$1}')
ifeq ($(shell test "$(JAVA_MAJOR)" -ge 24 2>/dev/null && echo yes),yes)
  export MAVEN_OPTS := $(MAVEN_OPTS) --sun-misc-unsafe-memory-access=allow
endif

.PHONY: build dev dev-floci dev-localstack deploy deploy-floci deploy-localstack \
	send state emulator-floci emulator-localstack local-down test

# Ensures the emulator service $(1) is the one serving :4566: reuses a running
# container with a matching image (however it was started), otherwise stops
# whatever holds the port and starts $(1) from docker-compose.yml.
define ensure-emulator
	@current=$$(docker ps --filter publish=4566 --format '{{.Image}}'); \
	case "$$current" in \
	  $(1)/*) echo "$(1) already serving :4566 — reusing it." ;; \
	  "") docker compose up -d $(1) ;; \
	  *) echo "Freeing :4566 (stopping $$current)..."; \
	     docker stop $$(docker ps -q --filter publish=4566) >/dev/null; \
	     docker compose up -d $(1) ;; \
	esac; \
	echo "Waiting for the emulator on :4566..."; \
	for i in $$(seq 1 60); do \
	  curl -s --max-time 1 -o /dev/null http://localhost:4566/ && break; \
	  if [ $$i -eq 60 ]; then echo "emulator did not respond on :4566" >&2; exit 1; fi; \
	  sleep 1; \
	done
endef

# --- Deploy: build function.zip and provision it (with table + queue) in the emulator.

deploy-floci: emulator-floci deploy

deploy-localstack: emulator-localstack deploy

deploy:
	./mvnw -q package -DskipTests exec:java -Dexec.args="deploy"

# Queue a check request for URL, e.g. make send URL=https://example.com
send:
	@test -n "$(URL)" || { echo "usage: make send URL=https://example.com" >&2; exit 1; }
	./mvnw -q compile exec:java -Dexec.args="send -url $(URL)"

# Print all site state records from DynamoDB.
state:
	./mvnw -q compile exec:java -Dexec.args="state"

# --- Dev mode: hot reload; SQS events go to the mock event server on :8082,
#     not to the emulator's queue.

dev-floci: emulator-floci dev

dev-localstack: emulator-localstack dev

dev:
	./mvnw quarkus:dev

# --- Plumbing.

build:
	./mvnw -q package -DskipTests

emulator-floci:
	$(call ensure-emulator,floci)

emulator-localstack:
	$(call ensure-emulator,localstack)

local-down:
	docker compose down

# Tests talk to DynamoDB on :4566, so an emulator must be up (make emulator-floci).
test:
	./mvnw test

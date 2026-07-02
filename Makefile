# Maven's bundled Guava calls a deprecated sun.misc.Unsafe method, which the JVM
# warns about on JDK 24+. Silence it there; the flag doesn't exist on older JDKs,
# so only add it when the running JDK is 24 or newer.
JAVA_MAJOR := $(shell java -version 2>&1 | awk -F'"' '/version/ {print $$2}' | awk -F. '{print ($$1 == "1") ? $$2 : $$1}')
ifeq ($(shell test "$(JAVA_MAJOR)" -ge 24 2>/dev/null && echo yes),yes)
  export MAVEN_OPTS := $(MAVEN_OPTS) --sun-misc-unsafe-memory-access=allow
endif

.PHONY: deploy-floci deploy-localstack emulator-floci emulator-localstack dev local-down test

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

deploy-floci: emulator-floci dev

deploy-localstack: emulator-localstack dev

emulator-floci:
	$(call ensure-emulator,floci)

emulator-localstack:
	$(call ensure-emulator,localstack)

dev:
	./mvnw quarkus:dev

local-down:
	docker compose down

test:
	./mvnw test

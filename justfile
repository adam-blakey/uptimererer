[private]
default:
    @just --list

deploy-floci: emulator-floci deploy

deploy-localstack: emulator-localstack deploy

# Queue a check request for url, e.g. just send https://example.com
send url:
    ./mvnw -q compile exec:java -Dexec.args="send -url {{ url }}"

# Print all site state records from DynamoDB.
state:
    ./mvnw -q compile exec:java -Dexec.args="state"

dev-floci: emulator-floci dev

dev-localstack: emulator-localstack dev

build:
    ./mvnw -q package -DskipTests

local-down:
    docker compose down

format:
    ./mvnw spotless:apply

# Tests talk to DynamoDB on :4566, so an emulator must be up (e.g., just emulator-floci).
test:
    ./mvnw test

[private]
deploy:
    ./mvnw -q package -DskipTests exec:java -Dexec.args="deploy"

[private]
dev:
    ./mvnw quarkus:dev

[private]
emulator-floci: (_ensure-emulator "floci")

[private]
emulator-localstack: (_ensure-emulator "localstack")

# Ensures the emulator service `service` is the one serving :4566: reuses a running
# container with a matching image (however it was started), otherwise stops
# whatever holds the port and starts `service` from docker-compose.yml.
[private]
_ensure-emulator service:
    #!/usr/bin/env bash
    set -euo pipefail
    current=$(docker ps --filter publish=4566 --format '{{{{.Image}}')
    case "$current" in
      {{ service }}/*) echo "{{ service }} already serving :4566 — reusing it." ;;
      "") docker compose up -d {{ service }} ;;
      *) echo "Freeing :4566 (stopping $current)..."
         docker stop $(docker ps -q --filter publish=4566) >/dev/null
         docker compose up -d {{ service }} ;;
    esac
    echo "Waiting for the emulator on :4566..."
    for i in $(seq 1 60); do
      curl -s --max-time 1 -o /dev/null http://localhost:4566/ && break
      if [ "$i" -eq 60 ]; then echo "emulator did not respond on :4566" >&2; exit 1; fi
      sleep 1
    done

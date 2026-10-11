# Local runbook

How to start, check, stop and fix the service on a laptop. This grows as more parts are added (message broker, cache, load test).

## What you need
- Docker (running)
- Java 25
- Nothing else: Maven comes with the project (`./mvnw`).

## Start

1. Start the database:
   ```
   docker compose up -d
   ```
   Wait until it is healthy:
   ```
   docker compose ps
   ```
   The `postgres` service should show `(healthy)`.

2. Start the service:
   ```
   ./mvnw spring-boot:run
   ```
   Database migrations (Flyway) run automatically at startup.

## Check it's working

| Check | Command | Expected |
|---|---|---|
| Service is up | `curl localhost:8080/actuator/health` | `{"status":"UP"}` |
| Connection pool size | `curl localhost:8080/actuator/metrics/hikaricp.connections.max` | `10` |
| Threads waiting for a DB connection | `curl localhost:8080/actuator/metrics/hikaricp.connections.pending` | `0` when idle |
| Database is reachable | `docker compose exec postgres psql -U vitallink -d vitallink -c 'select 1'` | `1` |
| Migrations that have run | `docker compose exec postgres psql -U vitallink -d vitallink -c 'select version, description, success from flyway_schema_history'` | one row per migration |

## Demo devices and keys

The database is loaded with demo devices on startup (`src/main/resources/db/seed`). These keys are for local use only.

| Device | Patient | API key (`X-Device-Key` header) |
|---|---|---|
| `bp-001` | `patient-001` | `local-key-bp-001` |
| `bp-002` | `patient-002` | `local-key-bp-002` |
| `bp-003` | `patient-003` | `local-key-bp-003` |
| `bp-003` | `patient-003` | `local-key-bp-003-old` (revoked: always rejected) |

| Check | Command | Expected |
|---|---|---|
| No key | `curl -i -X POST localhost:8080/v1/readings` | `401` |
| Revoked key | `curl -i -X POST localhost:8080/v1/readings -H 'X-Device-Key: local-key-bp-003-old'` | `401` |
| Valid key, no body | `curl -i -X POST localhost:8080/v1/readings -H 'X-Device-Key: local-key-bp-001'` | `400` (the key was accepted) |

## Send a reading

Happy path, then the same reading again (the duplicate path):

```bash
BODY=$(cat <<JSON
{"readings": [{"readingId": "$(uuidgen)", "metricType": "BLOOD_PRESSURE",
  "systolic": 182, "diastolic": 111, "pulse": 74,
  "measuredAt": "$(date -u +%Y-%m-%dT%H:%M:%SZ)"}]}
JSON
)
curl -s -X POST localhost:8080/v1/readings -H 'X-Device-Key: local-key-bp-001' \
  -H 'Content-Type: application/json' -d "$BODY"   # {"results":[{..."status":"ACCEPTED"}]}
curl -s -X POST localhost:8080/v1/readings -H 'X-Device-Key: local-key-bp-001' \
  -H 'Content-Type: application/json' -d "$BODY"   # {"results":[{..."status":"DUPLICATE"}]}
```

Check where it was stored: `docker compose exec postgres psql -U vitallink -d vitallink -c 'select tableoid::regclass, * from readings'` shows today's daily partition.

## Build and test

| Task | Command |
|---|---|
| Full build: compile, tests, formatting check | `./mvnw verify` |
| Tests only | `./mvnw test` |
| Fix formatting | `./mvnw spotless:apply` |

Tests start their own PostgreSQL in Docker (Testcontainers), so Docker must be running. They don't use the `docker compose` database.

## Stop and reset

| Task | Command | Data |
|---|---|---|
| Stop the service | `Ctrl+C` in its terminal | – |
| Stop the database | `docker compose stop` | kept |
| Remove the containers | `docker compose down` | kept (in a Docker volume) |
| Start from an empty database | `docker compose down -v` | **deleted** |

## Configuration

Every setting has a local default, so nothing needs to be set to run locally. To override, copy `.env.example` to `.env` and edit it.

- `docker compose` reads `.env` automatically.
- `./mvnw spring-boot:run` does **not**. Load it into your shell first:
  ```
  set -a; source .env; set +a
  ./mvnw spring-boot:run
  ```
  Otherwise the database and the service end up with different passwords.

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `Port 8080 was already in use` | another app is on 8080 | find it with `lsof -iTCP:8080 -sTCP:LISTEN`, then stop it, or run on another port: `./mvnw spring-boot:run -Dspring-boot.run.arguments=--server.port=8081` |
| `docker compose up` fails with `port is already allocated` on 5432 | another PostgreSQL (or old container) is using 5432 | `docker ps` to find it, then stop it |
| Health check works but the numbers look wrong | you may be talking to a different app (an old container on the same port) | `docker ps` and `lsof -iTCP:8080 -sTCP:LISTEN` to confirm what is answering |
| `password authentication failed for user "vitallink"` | `.env` changed the password for Docker but the service didn't load it, or an old database volume has a different password | load `.env` as shown above, or reset with `docker compose down -v` |
| `Connection refused` to localhost:5432 | the database isn't running or isn't healthy yet | `docker compose ps`, then wait for `(healthy)` |
| Tests fail with `Could not find a valid Docker environment` | Docker isn't running | start Docker and re-run |
| Startup fails with `Migration checksum mismatch` | a migration file that already ran was edited | never edit an applied migration; add a new one. Locally, `docker compose down -v` resets the database |
| A request with a valid key gets `401` | the seed data didn't load, or the key was typed with extra spaces | check `select * from devices` in psql; seed data loads only when `FLYWAY_LOCATIONS` includes `classpath:db/seed` (the default) |
| Build fails at `spotless:check` | code isn't formatted | `./mvnw spotless:apply` |

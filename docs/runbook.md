# Local runbook

How to start, check, stop and fix the service on a laptop. This grows as more parts are added (load test next).

## What you need
- Docker (running)
- Java 25
- Nothing else: Maven comes with the project (`./mvnw`).

## Start

1. Start PostgreSQL, RabbitMQ and Redis:
   ```
   docker compose up -d
   ```
   Wait until it is healthy:
   ```
   docker compose ps
   ```
   The `postgres`, `rabbitmq` and `redis` services should all show `(healthy)`.

2. Start the service:
   ```
   ADMIN_API_KEY=local-admin-key ./mvnw spring-boot:run
   ```
   Database migrations (Flyway) run automatically at startup. `ADMIN_API_KEY` is only needed to change thresholds (see below); without it the service runs normally and the admin API rejects every request.

## Check it's working

| Check | Command | Expected |
|---|---|---|
| Service is up | `curl localhost:8080/actuator/health` | `{"status":"UP"}` |
| Connection pool size | `curl localhost:8080/actuator/metrics/hikaricp.connections.max` | `10` |
| Threads waiting for a DB connection | `curl localhost:8080/actuator/metrics/hikaricp.connections.pending` | `0` when idle |
| Database is reachable | `docker compose exec postgres psql -U vitallink -d vitallink -c 'select 1'` | `1` |
| Redis is reachable | `docker compose exec redis redis-cli ping` | `PONG` |
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

## See an alert being raised

The demo data gives `patient-001` (device `bp-001`) the coach `coach-asha`. Send a high reading:

```bash
BODY=$(cat <<JSON
{"readings": [{"readingId": "$(uuidgen)", "metricType": "BLOOD_PRESSURE",
  "systolic": 185, "diastolic": 112, "pulse": 80,
  "measuredAt": "$(date -u +%Y-%m-%dT%H:%M:%SZ)"}]}
JSON
)
curl -s -X POST localhost:8080/v1/readings -H 'X-Device-Key: local-key-bp-001' \
  -H 'Content-Type: application/json' -d "$BODY"
```

Within a second:

| Check | Command | Expected |
|---|---|---|
| The alert | `docker compose exec postgres psql -U vitallink -d vitallink -c 'select id, patient_id, severity, status, assigned_coach_id, breach_count from alerts'` | one `CRITICAL` alert for `coach-asha` |
| Its history | `... -c 'select alert_id, measured_at, severity, reason from alert_breaches'` | one row per high reading |
| The coach was notified | `... -c 'select alert_id, coach_id, kind, severity from notifications'` | `OPENED` |
| Nothing stuck in the outbox | `curl -s localhost:8080/actuator/metrics/outbox.pending` | `0` |
| Queues | RabbitMQ UI at http://localhost:15672 (user and password `vitallink`) | `alerting.reading-breached` empty, `alerting.reading-breached.dead` empty |

Send more high readings: the same alert's `breach_count` goes up, and a new notification appears only if the severity gets worse.

## Change a threshold and see the cache follow

Thresholds are cached in Redis for 24 hours, so a change must go through the admin API, which deletes the cached copy (README A14). This needs the service started with `ADMIN_API_KEY=local-admin-key`.

1. Send a reading of 150/90 for `patient-001`. It is under the default limit (warning above 160), so no alert:
   ```bash
   send() { curl -s -X POST localhost:8080/v1/readings -H 'X-Device-Key: local-key-bp-001' \
     -H 'Content-Type: application/json' \
     -d "{\"readings\": [{\"readingId\": \"$(uuidgen)\", \"metricType\": \"BLOOD_PRESSURE\", \"systolic\": $1, \"diastolic\": 90, \"measuredAt\": \"$(date -u +%Y-%m-%dT%H:%M:%SZ)\"}]}"; echo; }
   send 150
   ```
2. The limits are now cached. `docker compose exec redis redis-cli keys 'thresholds:*'` shows `thresholds:v1:defaults` and `thresholds:v1:patient:patient-001`.
3. Give `patient-001` a stricter systolic limit:
   ```bash
   curl -s -X PUT localhost:8080/v1/admin/thresholds/patients/patient-001 -H 'X-Admin-Key: local-admin-key' \
     -H 'Content-Type: application/json' \
     -d '{"thresholds": [{"metric": "SYSTOLIC", "severity": "WARNING", "above": 140}]}'
   ```
   Expected: `200` with the new limit. The patient's cache entry is gone (`redis-cli keys 'thresholds:*'` no longer lists it).
4. Send 150/90 again: `send 150`. This time it breaks the new limit, and a `WARNING` alert appears (see the checks in "See an alert being raised").
5. Put `patient-001` back on the defaults:
   ```bash
   curl -s -X PUT localhost:8080/v1/admin/thresholds/patients/patient-001 -H 'X-Admin-Key: local-admin-key' \
     -H 'Content-Type: application/json' -d '{"thresholds": []}'
   ```

| Check | Command | Expected |
|---|---|---|
| Cache hits, misses and errors | `curl -s 'localhost:8080/actuator/metrics/thresholds.cache.lookups?tag=result:hit'` (or `miss`, `error`) | mostly hits once warm |
| What is cached for a patient | `docker compose exec redis redis-cli get thresholds:v1:patient:patient-001` | `[]` means "no limits of their own" |
| How long until it expires | `docker compose exec redis redis-cli ttl thresholds:v1:patient:patient-001` | up to 86400 seconds |
| No admin key | `curl -i -X PUT localhost:8080/v1/admin/thresholds/defaults -H 'Content-Type: application/json' -d '{"thresholds": []}'` | `401` |

**Redis down:** `docker compose stop redis`, then `send 150`. The reading is still `ACCEPTED`; thresholds come from PostgreSQL and the service log says "Threshold cache unavailable" once. `docker compose start redis` and the log says "Threshold cache works again".

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
| Remove the containers | `docker compose down` | kept (in a Docker volume); the Redis cache is emptied, which is harmless |
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
| Alerts aren't appearing | RabbitMQ isn't running, or events are stuck in the outbox | `docker compose ps`; check `outbox.pending` and `outbox.oldest.pending.seconds` in actuator metrics; look for "Could not send outbox events" in the service log |
| Messages in `alerting.reading-breached.dead` | an alert job failed every retry | read the message in the RabbitMQ UI and the service log, fix the cause, then move it back to the main queue |
| A threshold change has no effect | it was made directly in the database, so the cached copy (up to 24 hours old) is still used | make changes through the admin API. To clear the cache by hand: `docker compose exec redis redis-cli del thresholds:v1:defaults` (or the patient's key) |
| Admin API returns `401` with the right key | the service was started without `ADMIN_API_KEY`, or with a different value | restart it with `ADMIN_API_KEY=...`; the startup log warns when it is not set |
| Log says "Threshold cache unavailable" | Redis is down or slow | `docker compose ps` and `docker compose start redis`. Ingest keeps working from PostgreSQL meanwhile |
| Messages in `thresholds.cache-invalidation.dead` | a cache delete failed every retry (Redis down for a long time) | once Redis is back, delete the key named in the message by hand (or move the message back to the main queue) |
| Build fails at `spotless:check` | code isn't formatted | `./mvnw spotless:apply` |

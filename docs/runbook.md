# Local runbook

How to start, check, load-test, stop and fix the service on a laptop.

## What you need
- Docker (running). That's all to run the service.
- Java 25, only to build, test or run the service outside Docker. Maven comes with the project (`./mvnw`).

## Start

**Everything in Docker (one command):**
```
docker compose up --build -d
```
This builds the service image (the first build downloads dependencies and takes a few minutes), starts PostgreSQL, RabbitMQ and Redis, and starts the service once they are healthy. Database migrations (Flyway) and demo data load at startup. Check with `docker compose ps` (the three dependencies show `(healthy)`) and `curl localhost:8080/actuator/health`. Service logs: `docker compose logs -f app`.

If you set this up before the scope was trimmed, reset the database once first: `docker compose down -v` (the migrations changed).

**For development (service outside Docker):** start only the dependencies, then run the service from source:
```
docker compose up -d postgres rabbitmq redis
./mvnw spring-boot:run
```
Don't run both ways at once: they both use port 8080.

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
| The alert | `docker compose exec postgres psql -U vitallink -d vitallink -c 'select id, patient_id, reason, coach_id from alerts'` | one alert for `coach-asha`, with the limit that was broken |
| The coach was notified | `docker compose exec postgres psql -U vitallink -d vitallink -c 'select alert_id, coach_id, created_at from notifications'` | one row for `coach-asha` |
| Nothing stuck in the outbox | `docker compose exec postgres psql -U vitallink -d vitallink -c 'select count(*) from outbox where sent_at is null'` | `0` |
| Queues | RabbitMQ UI at http://localhost:15672 (user and password `vitallink`) | `alerting.reading-breached` and `alerting.reading-breached.dead` empty |

Every high reading raises its own alert and notification. A reading for `patient-003` (key `local-key-bp-003`) raises an alert with no coach: it is stored, nobody is notified, and the log says so.

## Read readings and alerts

The read APIs have no login in this exercise (README A15). Demo data: `coach-asha` looks after `patient-001`, `coach-ben` looks after `patient-002`, and `patient-003` has no coach.

| Check | Command | Expected |
|---|---|---|
| Latest readings | `curl -s localhost:8080/v1/patients/patient-001/readings/latest` | readings, newest first |
| A time range | `curl -s "localhost:8080/v1/patients/patient-001/readings?from=$(date -u -v-1d +%Y-%m-%dT%H:%M:%SZ)&to=$(date -u +%Y-%m-%dT%H:%M:%SZ)"` | readings from the last day |
| An invalid range | the same with `from` after `to` | `400` |
| A coach's alerts | `curl -s localhost:8080/v1/coaches/coach-asha/alerts` | Asha's alerts, newest first |

(`date -v-1d` is the macOS form; on Linux use `date -u -d '1 day ago' +%Y-%m-%dT%H:%M:%SZ`.)

## Change a threshold and see the cache follow

Thresholds belong to the care-plan system, which announces changes with a `ThresholdsChanged` event (README A14). Here we play that system by publishing the event through the RabbitMQ management API.

1. Send a reading of 150/90 for `patient-001`. It is under the limit (180), so no alert:
   ```bash
   send() { curl -s -X POST localhost:8080/v1/readings -H 'X-Device-Key: local-key-bp-001' \
     -H 'Content-Type: application/json' \
     -d "{\"readings\": [{\"readingId\": \"$(uuidgen)\", \"metricType\": \"BLOOD_PRESSURE\", \"systolic\": $1, \"diastolic\": 90, \"measuredAt\": \"$(date -u +%Y-%m-%dT%H:%M:%SZ)\"}]}"; echo; }
   send 150
   ```
2. The patient's limits are now cached: `docker compose exec redis redis-cli get thresholds:patient:patient-001`.
3. Lower the systolic limit to 140:
   ```bash
   curl -s -u vitallink:vitallink -H 'Content-Type: application/json' \
     -X POST localhost:15672/api/exchanges/%2F/careplan.events/publish -d '{
       "routing_key": "ThresholdsChanged", "properties": {"content_type": "application/json"},
       "payload_encoding": "string",
       "payload": "{\"patientId\":\"patient-001\",\"thresholds\":[{\"metric\":\"SYSTOLIC\",\"below\":90,\"above\":140},{\"metric\":\"DIASTOLIC\",\"below\":50,\"above\":110},{\"metric\":\"PULSE\",\"below\":40,\"above\":130}]}"}'
   ```
   Expected: `{"routed":true}`. Within a second the cached entry is gone (`redis-cli get ...` returns nothing) and `select * from thresholds where patient_id = 'patient-001'` shows 140.
4. Send 150/90 again: `send 150`. This time it breaks the new limit and an alert appears ("systolic 150 above 140").
5. Put the limit back to 180 by publishing the same event with `"above":180`.

**Redis down:** `docker compose stop redis`, then `send 150`. The reading is still `ACCEPTED`; thresholds come from PostgreSQL and the service log says "Threshold cache unavailable" once. `docker compose start redis` and the log says "Threshold cache works again".

## Run the load test

With the stack running from `docker compose up --build -d`:
```
docker run --rm -i --network vitallink_default -e BASE_URL=http://app:8080 grafana/k6:2.3.0 run - < loadtest/burst.js
```
It takes about 100 seconds and prints requests per second, latency (p50, p95, p99) and whether the two thresholds passed (fewer than 1% errors, p95 under 500 ms). The network name comes from the project folder name; if yours differs, find it with `docker network ls`.

While it runs, these show where time goes:

| What | Command |
|---|---|
| CPU of each container | `docker stats` |
| Requests waiting for a database connection | `curl -s localhost:8080/actuator/metrics/hikaricp.connections.pending` |
| Alert backlog | `docker compose exec postgres psql -U vitallink -d vitallink -c 'select count(*) from outbox where sent_at is null'` |

To try a bigger connection pool: `DB_POOL_SIZE=20 docker compose up -d app`. The test leaves millions of rows behind; `docker compose down -v` starts again from an empty database.

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
| Stop the service | `docker compose stop app`, or `Ctrl+C` if it runs from source | – |
| Stop everything | `docker compose stop` | kept |
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
| Alerts aren't appearing | RabbitMQ isn't running, or events are stuck in the outbox | `docker compose ps`; check `select count(*) from outbox where sent_at is null`; look for "Could not send outbox events" in the service log |
| Messages in `alerting.reading-breached.dead` | an alert job failed every retry | read the message in the RabbitMQ UI and the service log, fix the cause, then move it back to the main queue |
| A threshold change has no effect | it was made directly in the database, so the cached copy (up to 24 hours old) is still used | publish a `ThresholdsChanged` event as shown above, or delete the entry by hand: `docker compose exec redis redis-cli del thresholds:patient:<patientId>` |
| Log says "Threshold cache unavailable" | Redis is down or slow | `docker compose ps` and `docker compose start redis`. Ingest keeps working from PostgreSQL meanwhile |
| Messages in `readings.thresholds-changed.dead` | a threshold update failed every retry (for example Redis down for a long time, or a bad event) | read the message in the RabbitMQ UI and the service log, fix the cause, then move it back to the main queue |
| `docker compose up` says port 8080 is already allocated | the service is also running from source, or another app uses 8080 | stop the other one (`lsof -iTCP:8080 -sTCP:LISTEN`) |
| The `app` container keeps restarting or exits | a dependency wasn't ready, or a startup error | `docker compose logs app`; the database errors there are the same as in the rows above |
| Build fails at `spotless:check` | code isn't formatted | `./mvnw spotless:apply` |

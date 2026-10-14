# Runbook: run and test the service

A guided tour for running the service locally and seeing every behaviour the brief asks for. Each step has commands
to copy and the result to expect. The whole tour takes about 10 minutes (15 with the load test).

## 1. Start

You need **Docker** (running). Java 25 is only needed to build or test outside Docker.

```bash
docker compose down -v          # only if you ran an earlier version of this project: starts from an empty database
docker compose up --build -d    # first build downloads dependencies: a few minutes
```

This builds the service image and starts PostgreSQL, RabbitMQ and Redis; the service starts once they are healthy.
Database migrations (Flyway) and demo data load at startup.

```bash
docker compose ps                         # postgres, rabbitmq, redis "(healthy)", app "Up"
curl -s localhost:8080/actuator/health    # {"groups":["liveness","readiness"],"status":"UP"}
```

If the app isn't up after a minute: `docker compose logs app` (and see [Troubleshooting](#troubleshooting)).

**Prefer Postman?** Import [`vitallink.postman_collection.json`](vitallink.postman_collection.json) (in this folder).
It has the requests from sections 3–8 in the same order, and each one checks its own result, so **Run collection**
shows everything passing. The database, Redis and failure-mode checks below need a terminal.

## 2. Helpers for the commands below

Paste these into your terminal once (bash or zsh). They only shorten the curls.

```bash
now()     { date -u +%Y-%m-%dT%H:%M:%SZ; }
# One blood-pressure reading: reading <systolic> <diastolic>
reading() { printf '{"readingId":"%s","metricType":"BLOOD_PRESSURE","systolic":%s,"diastolic":%s,"pulse":74,"measuredAt":"%s"}' "$(uuidgen)" "$1" "$2" "$(now)"; }
# Send readings as a device: send <device key> <JSON body>
send()    { curl -s -w '  [HTTP %{http_code}]\n' -X POST localhost:8080/v1/readings -H "X-Device-Key: $1" -H 'Content-Type: application/json' -d "$2"; }
# Run SQL against the service's database: db "<sql>"
db()      { docker compose exec -T postgres psql -U vitallink -d vitallink -c "$1"; }
```

(`uuidgen` ships with macOS and most Linux distributions; otherwise use `cat /proc/sys/kernel/random/uuid`.)

### Demo data

| Device | Patient | API key (`X-Device-Key`) | Coach | Systolic limit |
|---|---|---|---|---|
| `bp-001` | `patient-001` | `local-key-bp-001` | `coach-asha` | 90–180 |
| `bp-002` | `patient-002` | `local-key-bp-002` | `coach-ben` | 90–170 |
| `bp-003` | `patient-003` | `local-key-bp-003` | none | 90–180 |
| `bp-003` | `patient-003` | `local-key-bp-003-old` (revoked) | | |

Every patient also has diastolic 50–110 and pulse 40–130. These keys are for local use only.

## 3. Device authentication

```bash
curl -s -o /dev/null -w '%{http_code}\n' -X POST localhost:8080/v1/readings                                          # 401: no key
curl -s -o /dev/null -w '%{http_code}\n' -X POST localhost:8080/v1/readings -H 'X-Device-Key: wrong-key'             # 401: unknown key
curl -s -o /dev/null -w '%{http_code}\n' -X POST localhost:8080/v1/readings -H 'X-Device-Key: local-key-bp-003-old'  # 401: revoked key
curl -s -o /dev/null -w '%{http_code}\n' -X POST localhost:8080/v1/readings -H 'X-Device-Key: local-key-bp-001'      # 400: key accepted, no body
```

A device may only send readings for its own patient. `patientId` in the body is optional; if it's sent, it must match:

```bash
send local-key-bp-001 "{\"patientId\":\"patient-002\",\"readings\":[$(reading 120 80)]}"
# {"detail":"this device is not assigned to that patient",...,"status":403,...}  [HTTP 403]
```

## 4. Send readings: the happy path and duplicates

**One reading, then the same reading again** (a device retrying because it never got the answer):

```bash
BODY="{\"readings\":[$(reading 120 80)]}"
send local-key-bp-001 "$BODY"    # {"results":[{"readingId":"…","status":"ACCEPTED"}]}   [HTTP 200]
send local-key-bp-001 "$BODY"    # {"results":[{"readingId":"…","status":"DUPLICATE"}]}  [HTTP 200]
```

`ACCEPTED` means the reading is stored in PostgreSQL, so the device can delete its copy. `DUPLICATE` means it was
already stored: the device can delete it too, and nothing is stored or alerted twice.

**A batch** (a device flushing its offline buffer): each reading gets its own result.

```bash
send local-key-bp-002 "{\"readings\":[$(reading 130 85),$(reading 70 90),$(reading 125 82)]}"
# ACCEPTED, REJECTED ("systolic must be higher than diastolic"), ACCEPTED   [HTTP 200]
```

**Requests that are wrong as a whole** get `400`:

```bash
send local-key-bp-001 '{"readings": []}'   # 400: readings must contain at least one reading
send local-key-bp-001 '{"readings": ['     # 400: malformed JSON
```

**Where the readings are stored** (daily partitions; today's is `readings_YYYY_MM_DD`, in UTC):

```bash
db "select tableoid::regclass as partition, device_id, patient_id, systolic, diastolic, measured_at from readings order by received_at"
```

## 5. Alerts: a breach goes through the outbox and RabbitMQ to the coach

```bash
BODY="{\"readings\":[$(reading 185 112)]}"
send local-key-bp-001 "$BODY"   # ACCEPTED: the device gets its answer before any alert work happens
send local-key-bp-001 "$BODY"   # DUPLICATE: the retry must not raise a second alert
sleep 1
```

What happened, step by step:

```bash
db "select event_type, payload->>'reason' as reason, sent_at is not null as sent from outbox"
#  ReadingBreached | systolic 185 above 180; diastolic 112 above 110 | t      ← saved with the reading, then sent to RabbitMQ

db "select id, patient_id, reason, coach_id from alerts"
#  1 | patient-001 | systolic 185 above 180; diastolic 112 above 110 | coach-asha    ← one alert, despite the retry

db "select alert_id, coach_id, created_at from notifications"
#  1 | coach-asha | …                                                        ← the coach was notified once

docker compose exec rabbitmq rabbitmqctl -q list_queues name messages consumers
#  alerting.reading-breached 0 2, and both *.dead (dead-letter) queues 0     ← nothing stuck
```

The RabbitMQ UI is at http://localhost:15672 (user and password `vitallink`).

**A patient with no coach** still gets an alert, nobody is notified, and the service logs it:

```bash
send local-key-bp-003 "{\"readings\":[$(reading 190 100)]}"
sleep 1
db "select id, patient_id, reason, coach_id from alerts where patient_id = 'patient-003'"   # coach_id empty
docker compose logs app | grep 'no coach'                                                      # "Alert … has no coach to notify"
```

**A low reading** breaks a limit too: `send local-key-bp-002 "{\"readings\":[$(reading 85 60)]}"` raises
"systolic 85 below 90" for `coach-ben`.

## 6. Read APIs

The read APIs have no login in this exercise (README A15).

```bash
curl -s localhost:8080/v1/patients/patient-001/readings/latest              # newest first (default 20, ?limit= up to 100)
curl -s "localhost:8080/v1/patients/patient-002/readings?from=$(date -u -v-1d +%Y-%m-%dT%H:%M:%SZ)&to=$(now)"   # a time range
curl -s localhost:8080/v1/coaches/coach-asha/alerts                         # Asha's alert queue, newest first
curl -s localhost:8080/v1/coaches/coach-ben/alerts                          # Ben's: only his patients' alerts
```

(`date -v-1d` is macOS; on Linux use `date -u -d '1 day ago' +%Y-%m-%dT%H:%M:%SZ`.)

Invalid queries get `400` with a clear message:

```bash
curl -s "localhost:8080/v1/patients/patient-002/readings?from=$(now)&to=2020-01-01T00:00:00Z"   # from must be before to
curl -s "localhost:8080/v1/patients/patient-002/readings?from=2020-01-01T00:00:00Z&to=$(now)"   # the range can be at most 90 days
curl -s "localhost:8080/v1/patients/patient-001/readings/latest?limit=0"                         # limit must be between 1 and 100
```

## 7. AI reading summary (Part C)

```bash
curl -s localhost:8080/v1/patients/patient-001/summary
# "source":"LLM", the stub model's text, and the real numbers under "facts" (count, averages, range, breaches, trend)
curl -s localhost:8080/v1/patients/patient-999/summary
# "source":"FALLBACK", "No readings in the last 7 days." (the model isn't called)
curl -s "localhost:8080/v1/patients/patient-001/summary?days=0"   # 400: days must be between 1 and 30
```

The model is a stub by default (README A17). Timeouts, the limit on calls at once and the fallback are covered by
`SummaryServiceTest`.

## 8. Threshold changes and the Redis cache

Thresholds belong to the care-plan system, which announces changes with a `ThresholdsChanged` event (README A14).
Here we play that system by publishing the event through RabbitMQ's management API.

```bash
send local-key-bp-001 "{\"readings\":[$(reading 150 90)]}"     # under the limit (180): no alert
docker compose exec redis redis-cli get thresholds:patient:patient-001   # the patient's limits are now cached
docker compose exec redis redis-cli ttl thresholds:patient:patient-001   # about 86400 (24 hours)
```

Lower the systolic limit to 140:

```bash
limit() { curl -s -u vitallink:vitallink -H 'Content-Type: application/json' \
  -X POST localhost:15672/api/exchanges/%2F/careplan.events/publish \
  -d "{\"routing_key\":\"ThresholdsChanged\",\"properties\":{\"content_type\":\"application/json\"},\"payload_encoding\":\"string\",\"payload\":\"{\\\"patientId\\\":\\\"patient-001\\\",\\\"thresholds\\\":[{\\\"metric\\\":\\\"SYSTOLIC\\\",\\\"below\\\":90,\\\"above\\\":$1},{\\\"metric\\\":\\\"DIASTOLIC\\\",\\\"below\\\":50,\\\"above\\\":110},{\\\"metric\\\":\\\"PULSE\\\",\\\"below\\\":40,\\\"above\\\":130}]}\"}"; echo; }
limit 140                                                                  # {"routed":true}
sleep 2
docker compose exec redis redis-cli get thresholds:patient:patient-001    # empty: the cached copy was deleted
db "select metric, below, above from thresholds where patient_id = 'patient-001'"   # SYSTOLIC 90 | 140
```

The same reading now breaks the new limit:

```bash
send local-key-bp-001 "{\"readings\":[$(reading 150 90)]}"
sleep 1
db "select reason from alerts order by id desc limit 1"    # systolic 150 above 140
limit 180                                                  # put it back
```

## 9. Failure modes: dependencies going down

**Redis down:** ingest and alerting keep working; thresholds come from PostgreSQL.

```bash
docker compose stop redis
send local-key-bp-001 "{\"readings\":[$(reading 182 90)]}"    # still ACCEPTED, and the alert is still raised
docker compose start redis
docker compose logs app | grep 'Threshold cache'              # "unavailable…" once, then "works again" once
```

**RabbitMQ down:** ingest stays fast; alerts are delayed, never lost.

```bash
docker compose stop rabbitmq
curl -s -o /dev/null -w 'HTTP %{http_code} in %{time_total}s\n' -X POST localhost:8080/v1/readings \
  -H 'X-Device-Key: local-key-bp-002' -H 'Content-Type: application/json' -d "{\"readings\":[$(reading 195 100)]}"
                                                                  # HTTP 200 in a few milliseconds
db "select count(*) as waiting from outbox where sent_at is null" # 1: the event waits in the outbox
docker compose start rabbitmq
sleep 15
db "select count(*) as waiting from outbox where sent_at is null" # 0: sent once RabbitMQ was back
db "select reason, coach_id from alerts order by id desc limit 1" # systolic 195 above 170 | coach-ben
```

While RabbitMQ is down, the service log shows Spring's reconnect attempts and our "Could not send outbox events"
once; that's expected.

## 10. Load test

With the stack running:

```bash
docker run --rm -i --network vitallink_default -e BASE_URL=http://app:8080 grafana/k6:2.3.0 run - < loadtest/burst.js
```

It takes about 100 seconds and prints requests per second, latency (p50, p95, p99) and whether both thresholds
passed (fewer than 1% errors, p95 under 500 ms). The README has the results and the bottleneck analysis. The network
name comes from the project folder name; if yours differs, find it with `docker network ls`.

While it runs, these show where time goes:

| What | Command |
|---|---|
| CPU of each container | `docker stats` |
| Requests waiting for a database connection | `curl -s localhost:8080/actuator/metrics/hikaricp.connections.pending` |
| Alert backlog | `db "select count(*) from outbox where sent_at is null"` |

To try a bigger connection pool: `DB_POOL_SIZE=20 docker compose up -d app`. The test leaves millions of rows behind;
`docker compose down -v` starts again from an empty database.

## 11. Build and tests

| Task | Command |
|---|---|
| Full build: compile, tests, formatting check | `./mvnw verify` |
| Tests only | `./mvnw test` |
| Fix formatting | `./mvnw spotless:apply` |

Tests start their own PostgreSQL, RabbitMQ and Redis in Docker (Testcontainers), so Docker must be running; they
don't use the `docker compose` stack.

## 12. Running the service from source (for development)

Start only the dependencies, then run the service from your IDE (`ReadingsServiceApplication`) or Maven:

```bash
docker compose up -d postgres rabbitmq redis
./mvnw spring-boot:run
```

Don't run it this way and in Docker at the same time: both use port 8080 (`docker compose stop app` first).

## Stop and reset

| Task | Command | Data |
|---|---|---|
| Stop everything | `docker compose stop` | kept |
| Remove the containers | `docker compose down` | kept (in Docker volumes) |
| Start from an empty database | `docker compose down -v` | **deleted** |

## Configuration

Every setting has a local default, so nothing needs to be set to run locally. To override, copy `.env.example` to
`.env` and edit it.

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

# VitalLink Readings & Alerting Service

This service takes in vitals from connected medical devices, handles duplicate retries, stores the readings and raises alerts for care coaches. Alerting happens asynchronously so that ingestion stays fast during the morning peak (6-9 AM).

## Scope

What this service covers:
- Ingest blood pressure readings, one at a time or in batches
- Ignore duplicate readings when a device retries
- Store readings and raise alerts to care coaches in the background
- Read APIs for a patient's readings and a coach's alerts

What I am not doing, and why:
- Other metric types (glucose, SpO2, weight): one metric done fully is enough to show the design
- Stretch goals from the brief: better to spend the time on the required parts

## Run it

```
docker compose up --build -d
```
Only Docker is needed. This starts the service with PostgreSQL, RabbitMQ and Redis and loads demo data. Then follow [docs/runbook.md](docs/runbook.md) for example requests (the happy path, a duplicate, an alert, a threshold change), checks and troubleshooting. Tests: `./mvnw verify` (needs Java 25 and Docker).

## Assumptions

The brief leaves some things open. These are the assumptions I made, and the reason behind each one.

| # | Assumption | Reason |
|---|---|---|
| A1 | The device generates a unique `readingId` (UUID) for every reading and sends the same ID again when it retries. | A device can take two readings in the same second, so the time alone can't tell a retry apart from a new reading. |
| A2 | `readingId` is unique per device, not across all devices. | Two different devices could generate the same ID. A reading from one device must never be treated as a duplicate of another device's reading. |
| A3 | A batch can be partially accepted. Each reading in it gets its own result: `ACCEPTED`, `DUPLICATE` or `REJECTED`. | If one reading in a batch is invalid, the valid readings in the same batch should still be saved. |
| A4 | Only blood pressure is implemented end to end. The model can be extended to other metric types. | The brief allows one metric. Doing one metric properly from start to end is more useful than doing every metric halfway. |
| A5 | Device-to-patient mapping, thresholds and coach panels are owned by other services. Here they are seed data. | Other systems manage this data. This service only reads it. |
| A6 | `ACCEPTED` means the reading is stored in PostgreSQL. Alerts are evaluated afterwards, usually within seconds. | Once the device gets `ACCEPTED`, it deletes its own copy of the reading. If we said `ACCEPTED` before saving and then crashed, the reading would be lost for good. |
| A7 | The project ships with a throwaway default database password and demo device API keys, for local Docker only. In production, every credential comes from a secret manager through environment variables, and demo data is never loaded. | The brief asks for both "no secrets in the repo" and "run with one command". A local-only password protects nothing real, and it lets `docker compose up` work without any setup. |
| A8 | Scale numbers I designed for are estimates built from the brief's figures. See [Scale assumptions](#scale-assumptions). | The brief gives devices and readings per day, but not patients, batch size, coaches or alert rates. I need those numbers to size partitions, indexes and the database. |
| A9 | Every request is a batch. A single reading is a batch of one. A batch holds at most 500 readings. | One format means the duplicate, validation and partial-acceptance rules are written and tested once. The limit stops a device that was offline for days from holding a database connection with one huge request. |
| A10 | When a device retries, it sends the reading unchanged: the same `readingId` and the same `measuredAt`. | The duplicate check uses device, `readingId` and `measuredAt` together, because PostgreSQL needs the partition column in every unique key. A device that changed `measuredAt` on a retry would have its reading stored twice. That would be a firmware bug, so I don't make every insert pay for an extra check. Instead, a periodic query can find such repeats and flag the firmware. |
| A11 | Every patient has their own thresholds per metric (e.g. systolic below 90 or above 180), and one level of alert: breaking any limit raises an alert. The thresholds are a local copy owned by the care-plan system (A5). | The brief: "each patient has thresholds per metric". One level is the smallest version that shows the full path. |
| A12 | Every reading that breaks a threshold raises its own alert, and the patient's coach gets one notification for it. A device retry never raises a second alert. | The brief: "a reading that breaches a threshold should generate an alert". Grouping repeated breaches into one alert would reduce noise for coaches; see "Left out on purpose". |
| A13 | A patient has one coach. If a patient has no coach, the alert is still stored and a warning is logged, but nobody is notified. Programs aren't modelled, because nothing in this scope depends on them. | The brief: "a care coach is assigned a panel of patients". An alert must never be dropped just because nobody is assigned. |
| A14 | The care-plan system announces threshold changes with a `ThresholdsChanged` event carrying the patient's full new set of limits. This service updates its copy and then deletes the cached entry. | Thresholds belong to another system, so changes arrive as events, and the event is also the trigger for cache invalidation. |
| A15 | The read APIs have no login in this exercise. In production, coaches log in through the identity provider (OAuth2/OIDC) and see only their own patients. | The brief asks for one authentication scheme and allows describing the rest; the device API key is the one implemented. |
| A16 | Readings are medical records and must be kept for years; I assume 7 years, as many medical-record rules require. Coaches work with recent data, so production keeps the last 6 months in PostgreSQL and moves older readings to cheap object storage, where they can still be queried. | The real period depends on regulations and contracts that aren't in the brief. 6 months keeps the database at about 1.8 TB while covering every coach screen (the read APIs allow at most 90 days per request). |
| A17 | The reading summary uses a stubbed language model by default; no API key is needed. | The brief accepts a stub and evaluates the integration pattern. Everything around the model (timeout, call limit, fallback) is real and tested; a real client is one class that calls the provider over HTTP. |

## Sending readings

`POST /v1/readings` with the device's key in the `X-Device-Key` header. `patientId` is optional; if it is sent, it must be the patient the device is assigned to.

```json
{
  "patientId": "patient-001",
  "readings": [
    {"readingId": "0b8f0c4e-6a1e-4c55-9f3a-1d2e3f4a5b6c", "metricType": "BLOOD_PRESSURE",
     "systolic": 182, "diastolic": 111, "pulse": 74, "measuredAt": "2026-10-12T07:02:00Z"}
  ]
}
```

The response is `200` with one result per reading, in the same order:

```json
{"results": [{"readingId": "0b8f0c4e-6a1e-4c55-9f3a-1d2e3f4a5b6c", "status": "ACCEPTED"}]}
```

| Result | Meaning | What the device should do |
|---|---|---|
| `ACCEPTED` | stored in PostgreSQL | delete its copy |
| `DUPLICATE` | already stored earlier (or twice in this batch) | delete its copy |
| `REJECTED` | invalid, with a `reason`; not stored | don't retry; report it |

Whole-request errors use the standard JSON error format (RFC 9457):

| Status | When |
|---|---|
| `400` | the body is missing, malformed or has no readings |
| `401` | the key is missing, unknown or revoked |
| `403` | `patientId` doesn't match the device's patient |
| `413` | more than 500 readings |
| `503` + `Retry-After` | the database is overloaded; retry after a short, random delay |

A reading is rejected if it is more than 30 days old.

## Reading data

These have no login in this exercise (A15). Errors use the same format as above (`400` for a bad parameter).

| Endpoint | What it returns |
|---|---|
| `GET /v1/patients/{patientId}/readings/latest?limit=20` | the newest readings from the last 30 days, newest first (`limit` 1–100) |
| `GET /v1/patients/{patientId}/readings?from=...&to=...&limit=100` | readings measured in `[from, to)`, newest first; at most 90 days per request (`limit` 1–500) |
| `GET /v1/coaches/{coachId}/alerts?limit=50` | the coach's alerts, newest first (`limit` 1–200) |

Each reading has `measuredAt` (the device's time) and `receivedAt` (when we got it); a big gap means the device uploaded late.

## Threshold cache

Ingest needs the patient's thresholds for every request, so they are cached in Redis, one entry per patient.

- **Cache-aside:** ingest reads Redis first; on a miss it reads PostgreSQL and stores a copy for 24 hours. A device sends every 30 minutes to a few hours, so a short-lived entry would almost always have expired by the time it came back.
- **Invalidation:** when a `ThresholdsChanged` event arrives (A14), the service replaces its copy in PostgreSQL, then deletes the cached entry, and deletes it again a second later. The second delete removes an old copy that a request may have written back just after the first one. If Redis is down, the update is retried. The 24-hour expiry is only a safety net.
- **Redis is optional at runtime:** if it is down, ingest reads PostgreSQL instead, and the service stays healthy.

## AI reading summary (Part C)

`GET /v1/patients/{patientId}/summary?days=7` (1–30 days) returns a short summary of a patient's recent readings for a coach, for example "BP trending up over the last 4 days, two readings over the limit". The model is treated as advisory: it must never make the request fail or hang, and never touches ingest or alerting.

```json
{"patientId": "patient-001", "days": 7, "source": "LLM",
 "summary": "...",
 "facts": {"readingCount": 12, "averageSystolic": 141, "averageDiastolic": 86, "highestSystolic": 185,
           "lowestSystolic": 122, "breaches": 2, "trend": "rising"},
 "generatedAt": "2026-10-13T07:20:00Z"}
```

**How it's built.** Our code computes the facts (count, averages, range, breaches, trend) from PostgreSQL. The model only turns those facts into sentences, and the facts are always returned next to its text, so the coach can check them. `source` says who wrote the summary: `LLM`, or `FALLBACK` when our code wrote it from the facts because the model was unavailable. With no readings, the model isn't called at all.

**Timeouts and fallbacks.** The model call runs on its own thread and is cut off after 3 seconds; the coach then gets the fallback, the same facts in plain words. A failing call (rate limited, server error, empty answer) also gives the fallback. Nothing is retried while a person is waiting. The endpoint is only on the read path, so a model outage can't affect ingest or alerts.

**Cost and rate limits.** At most 4 model calls run at once per instance; a request over the limit gets the fallback immediately instead of queueing behind a slow provider (a slow call keeps its slot until it really ends). Prompts are small (a few lines of numbers), and a short-text model such as Claude Haiku would be the choice. With a real model I would also cache each patient's summary for a few minutes in Redis, since coaches reopen the same patient, and track tokens and spend per day with an alarm.

**PHI.** The prompt holds only numbers and day counts: no patient ID, name or other identifier. A real provider would still need a business associate agreement, since readings are health data.

**Evaluating quality.** Automatically: every number in the model's text must appear in the facts (a mismatch means it invented something, so use the fallback); a fixed set of example patients (rising, falling, steady, no readings, many breaches) is run on every prompt or model change and compared with expected statements. With people: coaches mark a summary as helpful or wrong, and a clinician reviews a sample each week.

**Failure modes considered.**

| Failure | What happens |
|---|---|
| Model slow | Cut off at 3 s; fallback |
| Model down, rate limited (429) or erroring (5xx) | Fallback; no retry while the coach waits |
| Many coaches at once | Over 4 concurrent calls, fallback at once instead of a queue |
| Empty answer | Fallback |
| Wrong numbers or advice in the text | Facts shown next to it, labelled `LLM`; the prompt forbids diagnosis; the number check above in production |
| No readings | No model call; "No readings in the last N days" |

## Load test

`loadtest/burst.js` ([k6](https://k6.io), run through Docker; see the runbook) simulates a burst like the top of the hour: 50 devices sending batches of 5 readings back to back, ramping up for 30 s and holding for 60 s. 5% of requests resend the device's previous batch, as a device does when it never got the answer, and about 1 reading in 100 breaks a limit, so the alert pipeline works too.

**Setup:** everything on one laptop, inside Docker Desktop (11 CPUs, 8 GB), including k6 itself: one service instance, a connection pool of 10, PostgreSQL, RabbitMQ and Redis.

| | Pool of 10 (default) | Pool of 20 |
|---|---|---|
| Requests per second | 6,780 | 8,450 |
| Readings per second | ~33,900 | ~42,300 |
| Latency p50 / p95 / p99 | 5.4 / 10.9 / 17.9 ms | 4.3 / 8.3 / – ms |
| Errors | 0 of 678,055 | 0 of 845,151 |
| PostgreSQL CPU | ~190% | ~270% |

All retries came back `DUPLICATE` and every stored reading ID was unique. Alerting kept up: the outbox never held more than about 70 unsent events, and the oldest was under a second old. 3.2 million readings took 559 MB in their daily partition, about 175 bytes each, close to the 200 bytes assumed in the scale estimate.

**Where the bottleneck is.** During the burst all 10 database connections were busy and 25 to 40 requests were waiting for one, while neither the service (~3.2 of 11 CPUs) nor PostgreSQL (~1.9 CPUs) was out of CPU. So requests queue at the **connection pool**, which is deliberately small. Doubling the pool raised throughput by only 17% while PostgreSQL's CPU rose by 40%, so behind the pool the real limit is **PostgreSQL's work per request**: each request makes two database round trips (the device key lookup, which is not cached, and the insert transaction with its commit). At larger scale I would first remove work per request (cache device keys), then add database capacity, rather than simply raise the pool: more connections on every instance can exceed PostgreSQL's connection limit and make a busy database slower. The design document covers this.

**What these numbers don't show.** One laptop shares its CPUs between the load generator and the service, the table is small and its indexes are in memory, there is no network between machines, and k6 waits for each answer before sending the next request, so a slow service also slows the load down and hides some queueing. The result says the core path is efficient (about 3 times the 10,000 readings/s peak I designed for, on one instance), not what production would handle.

## Left out on purpose

The brief asks for a credible core, not a complete platform. These would come next, with more time or at production scale; the design document explains each one.

- **Login for coaches and staff** (OAuth2/OIDC), with each coach seeing only their own patients, and cover when a coach is away.
- **Grouping alerts:** one open alert per patient and metric, updated by new breaches, with the coach notified only when it opens or gets worse, plus a history of every breach. About 150 alerts per coach per day would otherwise cause alert fatigue.
- **Severity levels** (warning and critical), and acknowledging or resolving alerts.
- **Default thresholds per program** for patients who have none of their own.
- **A queue for alerts with no coach,** for operations staff to act on.
- **Backpressure:** answering `503` with `Retry-After` when the database is overloaded.
- **Metrics and alarms:** outbox lag, partitions ready ahead, cache hit rate, ingest results.
- **Paging** through long time ranges.

## Scale assumptions

The brief gives 1M active devices, 50M readings a day, and says most readings arrive between 6 and 9 AM. Everything else below is my estimate (marked *assumed*).

| What | How I got it | Result |
|---|---|---|
| Patients | half the patients have 1 device and half have 2 (*assumed*), so 1.5 on average: 1M ÷ 1.5 | ~670k |
| Readings per device | 50M ÷ 1M | 50 a day |
| Average write rate | 50M ÷ 86,400 seconds | ~580 readings/s |
| Morning window | 60% of readings between 6 and 9 AM (*assumed*) | ~2,800 readings/s |
| Peak burst | ×3 at the top of the hour and when offline devices reconnect (*assumed*) | design for **10,000 readings/s** |
| Write requests | 5 readings per batch on average (*assumed*) | ~2,000 requests/s at peak |
| Storage | ~200 bytes per reading, row plus indexes (*assumed*) | ~10 GB a day, ~3.6 TB a year |
| Care coaches | 200 patients per coach (*assumed*) | ~3,300 coaches |
| Read requests | each coach refreshes about every 30 seconds, ~3× in the morning (*assumed*) | ~100–300 requests/s |
| Read : write | read requests vs write requests at peak | ~1 : 7, so the system is **write-heavy** |
| Alerts | 1% of readings breach a threshold (*assumed*) | ~500k a day, ~150 per coach |

What these numbers mean for the design:
- The peak is about 17 times the average, so I size for the peak, not the average.
- Writes dominate, so the insert path stays lean: few indexes, batch inserts, and alerting off the request path.
- The patient count only affects the number of coaches and reads. Even if it were off by 2×, the system would still be write-heavy and the design would not change.
- About 10 GB arrives every day, so the readings table is partitioned by day. Today's indexes then stay small enough to fit in memory during the morning peak.

### Two red flags in these numbers

1. **50 readings per device per day is too many for blood pressure cuffs alone.** A cuff is used 1 to 3 times a day. Most of the volume must come from devices that send often, such as pulse oximeters. I only implement blood pressure, but the database and ingest path are sized for the total volume across all devices.
2. **About 150 alerts per coach per day is more than a person can act on.** Coaches would start ignoring alerts, which is a patient-safety risk. The design needs alert de-duplication, for example not opening a new alert when the patient already has an open one for the same metric.

## How I worked (Part D)

I used **Claude Code** (Claude Opus) in the terminal, inside this repository, mainly for code generation: the
Spring Boot service, the SQL migrations, the seed data (demo devices, coaches and thresholds), the tests, the
Docker setup and the k6 script. It also helped with first drafts of the docs and with checking current library
versions. I made the design decisions, reviewed every change and approved every commit myself.

**Where it helped.** It was fastest at the parts that are easy to check: boilerplate, test cases, and SQL I could run
straight away. It was also good at checking its own claims when asked to run the real thing instead of trusting a
green build. It stopped RabbitMQ mid-run to show an alert is delayed but not lost. It ran `EXPLAIN` on the
"latest readings" query and found it opened every daily partition, which the passing tests had not shown. And it
reran the load test with a bigger connection pool to test its own claim about the bottleneck (+17% throughput,
+40% database CPU, so the real limit is database work per request, not the pool).

**Where I had to correct it.** Two cases stand out. First, it proposed caching device keys and thresholds with a
60-second expiry. It sounded reasonable, but when I asked how often the same key comes back, the numbers showed a
device calls every 30 minutes or more, so the cache would almost never hit. Its next suggestion also turned out to
save nothing once we looked at the actual query it was meant to replace. The design we kept (cache each patient's
limits, delete the copy when they change) came from checking the access pattern, not from the first idea. Second,
scope creep: over many steps it kept proposing sensible extras (alert grouping, severity levels, coach login, an
admin API, custom metrics), and I accepted several. When I asked whether one small detail was in the brief, it was
not, and neither were many of the others. I removed them all and listed them under "Left out on purpose". Smaller
cases: library versions from an outdated index that crashed on Java 25 (the build caught it, and I checked Maven
Central), and an admin API secured with a shared key right after it had told me people should log in with JWTs.

**What I'd ask of a team using these tools.** Treat the output as a draft from a fast, confident colleague who
doesn't know our constraints. Check versions and facts at the source, run the app the way a user would, read the
query plan, and when a result looks odd, look at the data before the summary. Above all, deciding what not to
build stays with the engineer. The tool makes adding things cheap, which makes saying no more important.

## Design decisions & conventions (Part E)

This is for the engineer who will own this service next. The short version of how it works: a device's readings
are saved to PostgreSQL first, and that save is what "accepted" means. Anything that has to happen because of a
reading (an alert, a notification) happens later, through the outbox and RabbitMQ, so a slow alert path can never
slow down ingest. The reasons behind each choice are in [Assumptions](#assumptions) and
[docs/design.md](docs/design.md). Three rules hold this together. Please hold the line on them in review, mine
included.

### 1. Anything another system must hear about goes through the outbox, in the same transaction

When a reading breaks a limit, `ReadingIngestService` saves the reading and an `outbox` row in one transaction.
`OutboxRelay` publishes the row to RabbitMQ afterwards. Never publish to RabbitMQ, or call another service, from
the code that saves the data.

**Why it matters here:** if you save and then publish, a crash between the two loses the alert, and nobody finds
out until a patient with a dangerous reading is missed. If you publish and then save, a failed save leaves a coach
with an alert for a reading that doesn't exist. With the outbox both happen or neither does, and if RabbitMQ is
down, alerts are delayed, not lost. A slow outside call inside a transaction also holds a database connection and
its locks for the whole call, which is how a broker hiccup becomes an ingest outage.

**In review, ask:** is there a `RabbitTemplate` call, an HTTP call or a model call anywhere near a database
write? A new event type means a new `outbox.add(...)` inside the existing transaction, nothing else.

### 2. Every consumer must be safe to run twice, and the database decides what is a duplicate

RabbitMQ delivers at least once. The relay can publish a row twice (it crashed after sending but before marking
it sent), and a worker can receive a message twice (it crashed before acknowledging). Duplicates are normal here,
not rare. Kafka would be the same, so this rule stays if the broker ever changes.

**Why it matters here:** a duplicate alert means a coach is paged twice for one reading, and soon learns to
ignore pages. The fix is a unique constraint plus `ON CONFLICT DO NOTHING`: `alerts_reading_unique` on
`(device_id, reading_id, measured_at)`, and a notification is only created when the insert actually added a row.
A check in Java ("does the alert exist? if not, insert it") is not enough, because two workers can both see
"no" at the same moment.

**In review, ask:** what happens if this message arrives twice, or twice at the same instant? Prove it with a
test against real PostgreSQL (Testcontainers), as `ConcurrentRetryTest` and `AlertPipelineTest` do. A mock
can't show a race the database would catch.

### 3. Database changes must be safe on a live, busy table

`readings` takes inserts all day, and most of them arrive between 6 and 9 AM. A change that needs a strong lock
on it, even for a moment, queues every insert behind it.

- **Never edit a migration once it has been released.** Add a new one. (V3 and V4 were edited during development,
  before any release.)
- **Expand, then contract.** To rename or change a column: add the new one, write to both, backfill in small
  batches, switch reads, and drop the old one in a later release.
- **Indexes:** `CREATE INDEX CONCURRENTLY`, which means a migration outside a transaction.
- **Always set `lock_timeout`** (for example `SET lock_timeout = '3s'`) so a migration that can't get its lock fails
  quickly instead of blocking everything waiting behind it. Retrying a failed migration is cheap. An ingest outage
  at 7:15 AM is not.

**Why it matters here:** this is the 7:15 AM incident in [docs/design.md](docs/design.md#7-a-live-incident-715-am).
A schema change left open in a console held a lock on `readings`, and every insert for every patient queued behind
it.

**In review, ask:** what lock does this statement take, on which table, and for how long? If the answer isn't
obvious, test the migration against a copy with production-like data while a load test runs.

### A code-review take on my own shortcut

The corner I knowingly cut is in the outbox relay. This is the comment I'd want a senior engineer to leave on it:

> **`OutboxRelay.java:99`**: `sendBatch()` waits for RabbitMQ's confirms (up to 5 s) inside the transaction that
> holds the `FOR UPDATE` locks on the batch and one pooled connection. That's the exact pattern convention 1 tells
> everyone else not to use.
>
> I can see why it's acceptable today, and I agree. The locks are only on `outbox` rows, never on `readings`, so
> ingest keeps inserting. The wait has a 5 s ceiling. It's one connection per instance. And every failure ends the
> same safe way: rollback, nothing marked sent, retry on the next run. It's the simplest design that can't mark an
> unconfirmed event as sent.
>
> The risk is a broker that is up but slow, not one that is down (a down broker fails fast). If confirms take
> 4 s during the 7 AM peak, the relay holds 1 of our 10 connections almost all the time, and batches confirmed
> after the timeout are sent again. Convention 2 makes those repeats harmless, but they still cost work.
>
> Two asks:
> 1. **Now:** add a timer around the confirm wait, and a comment on `sendBatch()` saying why this exception to
>    convention 1 is allowed here (side table, ceiling, one connection). Then nobody copies the pattern into code
>    that touches `readings` or holds many connections.
> 2. **When confirm time regularly goes above about 100 ms, or before we raise the batch size or send in parallel:**
>    split it into claim, send, mark. A short transaction sets a `claimed_until` well beyond the confirm timeout
>    (say 30 s) and commits. The send happens with no transaction open. A second short transaction sets `sent_at`.
>    An expired claim is simply picked up again. Duplicates stay possible either way, so the consumer rules don't
>    change.

A related one I'd fix first, because it's a missed detail rather than a trade-off:
`ReadingPartitionMaintainer` creates partitions without a `lock_timeout`, so on a bad morning it could queue every
insert behind its own lock. That breaks my convention 3.

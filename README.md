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

A reading is rejected if it is more than 30 days old or more than 5 minutes in the future.

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

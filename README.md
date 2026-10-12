# VitalLink Readings & Alerting Service

This service takes in vitals from connected medical devices, handles duplicate retries, stores the readings and raises alerts for care coaches. Alerting happens asynchronously so that ingestion stays fast during the morning peak (6-9 AM).

## Scope

What this service covers:
- Ingest blood pressure readings, one at a time or in batches
- Ignore duplicate readings when a device retries
- Store readings and raise alerts to care coaches in the background
- Read APIs for a patient's readings and a coach's alert queue

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
| A11 | A patient without their own thresholds uses program-wide defaults. A patient's own limits for a metric replace the defaults for that metric only. If several limits are broken, the most severe one sets the alert's severity. | Without defaults, a patient missing from the threshold data would never get an alert. That is a silent safety gap. |
| A12 | A patient has at most one open alert per metric. New high readings update that alert instead of creating new ones, and every reading stays in its history. The coach is notified only when an alert is first opened and when it becomes more severe, so they aren't flooded with repeats. | About 150 alerts per coach per day would make coaches start ignoring them. One alert per problem, plus a full history of every breach, keeps the coach focused without losing how the condition developed (a gradual rise or a sudden spike). |
| A13 | A patient has one coach per program, and an alert goes to the coach of the program it belongs to (blood pressure → hypertension coach). If there is no coach, the alert waits in an unassigned queue. | A diabetes coach shouldn't be interrupted by a blood pressure alert. An alert must never be dropped just because nobody is assigned. |

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

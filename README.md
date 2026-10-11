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
| A7 | The project ships with a throwaway default database password for local Docker only. In production, every credential comes from a secret manager through environment variables. | The brief asks for both "no secrets in the repo" and "run with one command". A local-only password protects nothing real, and it lets `docker compose up` work without any setup. |

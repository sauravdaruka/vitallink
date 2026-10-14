# 📈 Load test

**A top-of-the-hour burst against one instance on a laptop: ~34,000 readings/s, 0 errors, p99 18 ms.**

[← Back to README](../README.md) · [Design at scale](design.md) · [How to run it](runbook.md#-10-load-test)

---

## 🎯 What it simulates

`loadtest/burst.js` ([k6](https://k6.io), run through Docker) plays the morning peak:

- **50 devices** sending batches of **5 readings** back to back
- **30 s** ramp-up, then **60 s** at full load
- **5% retries:** a device resends its previous batch, as it does when it never got the answer
- **~1% breaches:** about 1 reading in 100 breaks a limit, so the alert pipeline works too

**Setup:** everything on one laptop inside Docker Desktop (11 CPUs, 8 GB), k6 included: one service instance, a connection pool of 10, PostgreSQL, RabbitMQ and Redis.

---

## 📊 Results

| | Pool of 10 (default) | Pool of 20 |
|---|---|---|
| **Requests per second** | 6,780 | 8,450 |
| **Readings per second** | **~33,900** | ~42,300 |
| **Latency p50 / p95 / p99** | 5.4 / 10.9 / 17.9 ms | 4.3 / 8.3 / – ms |
| **Errors** | **0** of 678,055 | 0 of 845,151 |
| **PostgreSQL CPU** | ~190% | ~270% |

- ✅ **Duplicates:** every retry came back `DUPLICATE`, and every stored reading ID was unique.
- ✅ **Alerting kept up:** the outbox never held more than ~70 unsent events, and the oldest was under a second old.
- ✅ **Storage matches the estimate:** 3.2 million readings took 559 MB in their daily partition, ~175 bytes each (200 assumed).

---

## 🔍 Where the bottleneck is

- **Requests queue at the connection pool.** All 10 database connections were busy and 25–40 requests were waiting for one, while neither the service (~3.2 of 11 CPUs) nor PostgreSQL (~1.9 CPUs) was out of CPU. The pool is small on purpose.
- **Behind the pool, the limit is PostgreSQL's work per request.** Doubling the pool gave only **+17% throughput for +40% database CPU**. Each request makes two round trips: the device key lookup (not cached) and the insert transaction with its commit.
- **So at larger scale:** first remove work per request (cache device keys), then add database capacity. Simply raising the pool on every instance can exceed PostgreSQL's connection limit and make a busy database slower. See [design §2](design.md#-2-scaling-to-1m-devices-and-50m-readings-a-day).

---

## ⚠️ What these numbers don't show

> [!NOTE]
> The result says the core path is efficient (about **3×** the 10,000 readings/s peak I designed for, on one instance), not what production would handle.

- One laptop shares its CPUs between the load generator and the service.
- The table is small, so its indexes are in memory.
- There is no network between machines.
- k6 waits for each answer before sending the next request, so a slow service also slows the load down and hides some queueing.

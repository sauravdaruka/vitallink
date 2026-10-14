# Load test

`loadtest/burst.js` ([k6](https://k6.io), run through Docker; see [runbook section 10](runbook.md#10-load-test)) simulates a burst like the top of the hour: 50 devices sending batches of 5 readings back to back, ramping up for 30 s and holding for 60 s. 5% of requests resend the device's previous batch, as a device does when it never got the answer, and about 1 reading in 100 breaks a limit, so the alert pipeline works too.

**Setup:** everything on one laptop, inside Docker Desktop (11 CPUs, 8 GB), including k6 itself: one service instance, a connection pool of 10, PostgreSQL, RabbitMQ and Redis.

| | Pool of 10 (default) | Pool of 20 |
|---|---|---|
| Requests per second | 6,780 | 8,450 |
| Readings per second | ~33,900 | ~42,300 |
| Latency p50 / p95 / p99 | 5.4 / 10.9 / 17.9 ms | 4.3 / 8.3 / – ms |
| Errors | 0 of 678,055 | 0 of 845,151 |
| PostgreSQL CPU | ~190% | ~270% |

All retries came back `DUPLICATE` and every stored reading ID was unique. Alerting kept up: the outbox never held more than about 70 unsent events, and the oldest was under a second old. 3.2 million readings took 559 MB in their daily partition, about 175 bytes each, close to the 200 bytes assumed in the scale estimate.

**Where the bottleneck is.** During the burst all 10 database connections were busy and 25 to 40 requests were waiting for one, while neither the service (~3.2 of 11 CPUs) nor PostgreSQL (~1.9 CPUs) was out of CPU. So requests queue at the **connection pool**, which is deliberately small. Doubling the pool raised throughput by only 17% while PostgreSQL's CPU rose by 40%, so behind the pool the real limit is **PostgreSQL's work per request**: each request makes two database round trips (the device key lookup, which is not cached, and the insert transaction with its commit). At larger scale I would first remove work per request (cache device keys), then add database capacity, rather than simply raise the pool: more connections on every instance can exceed PostgreSQL's connection limit and make a busy database slower. The [design document](design.md#2-scaling-to-1m-devices-and-50m-readings-a-day) covers this.

**What these numbers don't show.** One laptop shares its CPUs between the load generator and the service, the table is small and its indexes are in memory, there is no network between machines, and k6 waits for each answer before sending the next request, so a slow service also slows the load down and hides some queueing. The result says the core path is efficient (about 3 times the 10,000 readings/s peak I designed for, on one instance), not what production would handle.

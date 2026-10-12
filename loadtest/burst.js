// A burst of batched readings, like devices flushing at the top of the hour.
// Run (see docs/runbook.md): docker run --rm -i --network vitallink_default -e BASE_URL=http://app:8080 \
//   grafana/k6:2.3.0 run - < loadtest/burst.js
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const BATCH_SIZE = 5; // matches the scale estimate in the README
const DEVICE_KEYS = ['local-key-bp-001', 'local-key-bp-002', 'local-key-bp-003'];

const readingsSent = new Counter('readings_sent');

export const options = {
  stages: [
    { duration: '30s', target: 50 }, // ramp up to 50 devices sending at once
    { duration: '60s', target: 50 }, // hold the burst
    { duration: '10s', target: 0 },
  ],
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<500'],
  },
};

// Each virtual user is one device, so a retry is sent with the same key, as a real device would.
let previous = null;

export default function () {
  // 5% of requests resend the previous batch, as a device does when it never got the answer.
  const isRetry = previous !== null && Math.random() < 0.05;
  const body = isRetry ? previous : JSON.stringify({ readings: batch() });
  previous = body;

  const res = http.post(`${BASE_URL}/v1/readings`, body, {
    headers: {
      'Content-Type': 'application/json',
      'X-Device-Key': DEVICE_KEYS[__VU % DEVICE_KEYS.length],
    },
    tags: { kind: isRetry ? 'retry' : 'new' },
  });
  check(res, {
    'status is 200': (r) => r.status === 200,
    'retry comes back DUPLICATE': (r) => !isRetry || r.json('results.0.status') === 'DUPLICATE',
  });
  readingsSent.add(BATCH_SIZE);
}

function batch() {
  const now = Date.now();
  return Array.from({ length: BATCH_SIZE }, (_, i) => ({
    readingId: crypto.randomUUID(),
    metricType: 'BLOOD_PRESSURE',
    // About 1 reading in 100 breaks a limit, as in the scale estimate, so alerts flow too.
    systolic: Math.random() < 0.01 ? 195 : 110 + Math.floor(Math.random() * 40),
    diastolic: 70 + Math.floor(Math.random() * 15),
    pulse: 60 + Math.floor(Math.random() * 30),
    measuredAt: new Date(now - i * 1000).toISOString(),
  }));
}

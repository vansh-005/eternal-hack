// Q-06 — k6 load profiles P1..P6 (LOAD_AND_CHAOS.md §2). Profile via __ENV.PROFILE.
import http from 'k6/http';
import exec from 'k6/execution';
import { check, sleep } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://web';
const RUN_ID = __ENV.RUN_ID || 'adhoc';
const PROFILE = __ENV.PROFILE || 'P1';
// Optional shortened run: DURATION=60s (applies to the main constant-rate scenarios). NOTE: k6's CLI --duration
// REPLACES the scripted scenarios with a 1-VU 'default' scenario; the default fn below then self-paces.
const DUR = __ENV.DURATION;

const PROFILES = {
  P1: { scenarios: { main: { executor: 'constant-arrival-rate', rate: 28, timeUnit: '50s', duration: '5m', preAllocatedVUs: 56, maxVUs: 200 } } },
  P2: { scenarios: { main: { executor: 'constant-arrival-rate', rate: 42, timeUnit: '50s', duration: '20m', preAllocatedVUs: 84, maxVUs: 200 } } },
  P3: { scenarios: { main: { executor: 'constant-arrival-rate', rate: 42, timeUnit: '5s', duration: '10m', preAllocatedVUs: 84, maxVUs: 300 } } },
  P4: { scenarios: { main: { executor: 'constant-arrival-rate', rate: 56, timeUnit: '1s', duration: '10m', preAllocatedVUs: 112, maxVUs: 500 } } },
  P5: {
    scenarios: {
      burst: { executor: 'constant-arrival-rate', rate: 200, timeUnit: '1s', duration: '60s', preAllocatedVUs: 400, maxVUs: 2000 },
      tail: { executor: 'constant-arrival-rate', rate: 42, timeUnit: '50s', duration: '4m', startTime: '60s', preAllocatedVUs: 84, maxVUs: 2000 },
    },
  },
  P6: {
    scenarios: {
      knee: {
        executor: 'ramping-arrival-rate', startRate: 10, timeUnit: '1s',
        stages: [{ target: 500, duration: '10m' }],
        preAllocatedVUs: 200, maxVUs: 2000,
      },
    },
  },
};

if (DUR) for (const sc of Object.values(PROFILES[PROFILE]?.scenarios || {})) if (sc.duration) sc.duration = DUR;
if (!PROFILES[PROFILE]) throw new Error(`unknown PROFILE ${PROFILE}`);

export const options = {
  ...PROFILES[PROFILE],
  discardResponseBodies: false,
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
  thresholds: {
    dropped_iterations: ['count==0'],
    http_req_failed: ['rate<0.01'],
  },
};

const randInt = (lo, hi) => lo + Math.floor(Math.random() * (hi - lo + 1));
const pick = (a) => a[Math.floor(Math.random() * a.length)];
const headers = { 'Content-Type': 'application/json', 'X-Run-Id': RUN_ID };

export default function () {
  if (exec.scenario.name === 'default') {
    // --duration on the CLI discarded the scripted scenarios: emulate the profile's first-scenario rate with 1 VU.
    const sc = Object.values(PROFILES[PROFILE].scenarios)[0];
    if (sc.rate) sleep(Number.parseFloat(sc.timeUnit) / sc.rate);
  }
  const outletId = `outlet-load-${String((exec.scenario.iterationInTest % 500) + 1).padStart(4, '0')}`;

  const created = http.post(
    `${BASE_URL}/oms/api/load/accepted-orders`,
    JSON.stringify({
      outletId,
      stage: pick(['NOT_STARTED', 'PREP', 'OVEN']),
      acceptedSecondsAgo: randInt(30, 600),
      valuePaise: randInt(15000, 90000),
    }),
    { headers, tags: { name: 'create' } },
  );
  if (!check(created, { 'create 201': (r) => r.status === 201 })) return;
  const orderId = created.json('orderId');

  if (Math.random() < 0.03) {
    http.post(`${BASE_URL}/oms/api/orders/${orderId}/ready`, null, { headers, tags: { name: 'ready' } });
  }

  const initiatedBy = Math.random() < 0.95 ? 'CUSTOMER' : 'RESTAURANT';
  const res = http.post(
    `${BASE_URL}/oms/api/orders/${orderId}/cancel`,
    JSON.stringify({ initiatedBy }),
    { headers, tags: { name: 'cancel' }, responseCallback: http.expectedStatuses(200, 409, 422) },
  );
  // READY orders legitimately reject the cancel (NOT_ACTIONABLE, 409/422); anything else is a failure.
  check(res, { 'cancel ok': (r) => r.status === 200 || r.status === 409 || r.status === 422 });
}

// Minimal text summary (jslib.k6.io is not reachable from the container).
function fmt(m, key) {
  const v = m && m.values ? m.values[key] : undefined;
  return v === undefined ? '-' : (Math.round(v * 100) / 100).toString();
}
function textSummary(data) {
  const m = data.metrics;
  const lines = [`k6 summary  run=${RUN_ID} profile=${PROFILE}`];
  const c = (n) => (m[n] ? m[n].values.count : 0);
  lines.push(`  iterations: ${c('iterations')}  dropped: ${c('dropped_iterations')}  http_reqs: ${c('http_reqs')}`);
  lines.push(`  http_req_failed rate: ${fmt(m.http_req_failed, 'rate')}`);
  for (const n of ['http_req_duration', 'http_req_duration{name:create}', 'http_req_duration{name:cancel}']) {
    if (m[n]) lines.push(`  ${n}: avg=${fmt(m[n], 'avg')} med=${fmt(m[n], 'med')} p(95)=${fmt(m[n], 'p(95)')} p(99)=${fmt(m[n], 'p(99)')} max=${fmt(m[n], 'max')} ms`);
  }
  for (const [n, t] of Object.entries(m)) {
    if (t.thresholds) for (const [e, r] of Object.entries(t.thresholds)) lines.push(`  threshold ${n} ${e}: ${r.ok ? 'PASS' : 'FAIL'}`);
  }
  return lines.join('\n') + '\n';
}

export function handleSummary(data) {
  return {
    [`/results/${RUN_ID}/k6-summary.json`]: JSON.stringify(data),
    stdout: textSummary(data),
  };
}

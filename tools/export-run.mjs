#!/usr/bin/env node
// Q-07: export a finished run (docs/OUTPUT.md sections 2-4, LOAD_AND_CHAOS.md sections 7-8).
//   node tools/export-run.mjs --run run-20261121-1930-P3
// Reads results/<run>/manifest.json (+ k6-summary.json, notes.md), queries Prometheus and Postgres, writes
// summary.json, invariants.json, timeseries.json, cases.csv next to them.
// Env: PROM_URL (http://localhost:9090) PGHOST/PGPORT/PGUSER/PGPASSWORD/PGDATABASE (localhost:5432 postgres/postgres nocook)
//      GRAFANA_URL (http://localhost:3000)
import { spawnSync } from 'node:child_process';
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import pg from 'pg';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const PROM = (process.env.PROM_URL ?? 'http://localhost:9090').replace(/\/$/, '');
const GRAFANA = (process.env.GRAFANA_URL ?? 'http://localhost:3000').replace(/\/$/, '');
const MIN_N_FOR_P99 = 1000;
const QUEUE = 'nocook.cancellations';
const ACK_URI = '/api/cases/{caseId}/ack';

const argv = process.argv.slice(2);
const runIdArg = argv.includes('--run') ? argv[argv.indexOf('--run') + 1] : undefined;
if (!runIdArg) {
  console.error('usage: node tools/export-run.mjs --run <runId>');
  process.exit(2);
}
const runId = runIdArg;
const dir = join(root, 'results', runId);
if (!existsSync(join(dir, 'manifest.json'))) {
  console.error(`no manifest at ${dir}/manifest.json`);
  process.exit(2);
}
const manifest = JSON.parse(readFileSync(join(dir, 'manifest.json'), 'utf8'));
const profile = manifest.profile;
const fault = manifest.fault ?? null;

// ---------- time window ----------
const startedMs = Date.parse(manifest.startedAt);
const k6StartMs = Date.parse(manifest.k6StartedAt ?? manifest.startedAt);
const k6EndMs = Date.parse(manifest.k6EndedAt ?? manifest.endedAt ?? new Date().toISOString());
const endedMs = Date.parse(manifest.endedAt ?? new Date().toISOString());
const warmupMs = 30_000;
const fromSec = Math.ceil((startedMs + warmupMs) / 1000);
const EXPORT_LAG_SECONDS = 6; // OTLP step is 5 s: samples for the last arrivals reach Prometheus up to ~5 s after k6 ends
const toSec = Math.floor(k6EndMs / 1000) + EXPORT_LAG_SECONDS;
const D = Math.max(1, toSec - fromSec); // window length for [D s] @ toSec
const k6Seconds = Math.max(1, (k6EndMs - k6StartMs) / 1000);

// ---------- helpers ----------
const round = (v, d = 4) => (v === null || v === undefined || !Number.isFinite(v) ? null : Math.round(v * 10 ** d) / 10 ** d);
const iso = (ms) => new Date(ms).toISOString();
const fmtInt = (n) => Number(n).toLocaleString('en-US');

async function promPost(path, params) {
  for (let attempt = 0; ; attempt++) {
    try {
      const res = await fetch(`${PROM}${path}`, {
        method: 'POST',
        headers: { 'content-type': 'application/x-www-form-urlencoded' },
        body: new URLSearchParams(params),
      });
      const body = await res.json();
      if (body.status !== 'success') throw new Error(`${path} ${JSON.stringify(params)} -> ${JSON.stringify(body)}`);
      return body.data;
    } catch (err) {
      if (attempt >= 2) throw err;
      await new Promise((r) => setTimeout(r, 500));
    }
  }
}
const toNum = (s) => {
  const v = Number(s);
  return Number.isFinite(v) ? v : null;
};
/** Instant query -> first sample value or null (empty / NaN). */
async function scalar(expr, atSec = toSec) {
  const data = await promPost('/api/v1/query', { query: expr, time: String(atSec) });
  const first = data.result?.[0];
  return first ? toNum(first.value[1]) : null;
}
/** Instant query -> {labelValue: number} grouped by a label. */
async function byLabel(expr, label, atSec = toSec) {
  const data = await promPost('/api/v1/query', { query: expr, time: String(atSec) });
  const out = {};
  for (const r of data.result ?? []) out[r.metric[label] ?? ''] = toNum(r.value[1]);
  return out;
}
/** Range query -> [{metric, values: [[t, number|null]]}] */
async function range(expr, startSec, endSec, step = 5) {
  const data = await promPost('/api/v1/query_range', { query: expr, start: String(startSec), end: String(endSec), step: String(step) });
  return (data.result ?? []).map((r) => ({ metric: r.metric, values: r.values.map(([t, v]) => [t, toNum(v)]) }));
}
const maxOf = (series) => {
  let best = null;
  for (const s of series) for (const [, v] of s.values) if (v !== null && (best === null || v > best)) best = v;
  return best;
};

const leCache = new Map();
async function leFor(metric, matchLabels, slo) {
  const key = `${metric}|${JSON.stringify(matchLabels)}`;
  if (!leCache.has(key)) {
    const sel = labelSel(matchLabels);
    const res = await fetch(`${PROM}/api/v1/label/le/values?${new URLSearchParams({ 'match[]': `${metric}_bucket${sel}` })}`);
    leCache.set(key, (await res.json()).data ?? []);
  }
  return leCache.get(key).find((le) => Number(le) === slo) ?? null;
}
const labelSel = (labels) => {
  const parts = Object.entries(labels).map(([k, v]) => `${k}="${v}"`);
  return parts.length ? `{${parts.join(',')}}` : '';
};

/** Latency block over [D s] @ toSec per OUTPUT.md section 3. slo=null -> report-only (no share). */
async function latencyBlock(metric, labels, slo, win = { d: D, at: toSec }) {
  const inc = (suffix, extra = {}) => `increase(${metric}_${suffix}${labelSel({ ...labels, ...extra })}[${win.d}s] @ ${win.at})`;
  const nRaw = await scalar(`sum(${inc('count')})`, win.at);
  const n = nRaw === null ? 0 : Math.round(nRaw);
  const block = { n, p50: null, p95: null, p99: null, slo, shareWithinSlo: null };
  if (n < 1) return { block, p99Raw: null };
  const q = (p) => scalar(`histogram_quantile(${p}, sum by (le) (${inc('bucket')}))`, win.at);
  const [p50, p95, p99] = [await q(0.5), await q(0.95), await q(0.99)];
  block.p50 = round(p50);
  block.p95 = round(p95);
  block.p99 = n >= MIN_N_FOR_P99 ? round(p99) : null;
  if (slo !== null) {
    const le = await leFor(metric, labels, slo);
    if (le !== null) {
      const within = await scalar(`sum(${inc('bucket', { le })})`, win.at);
      const total = await scalar(`sum(${inc('count')})`, win.at);
      block.shareWithinSlo = within !== null && total ? round(Math.min(1, within / total), 6) : null;
    }
  }
  return { block, p99Raw: round(p99) };
}

// ---------- latency blocks ----------
const raw = {}; // unrounded-hidden p99 values for the verdict
async function blk(name, metric, labels, slo) {
  const { block, p99Raw } = await latencyBlock(metric, labels, slo);
  raw[name] = p99Raw;
  return block;
}

const caseOpen = await blk('caseOpen', 'nocook_case_open_seconds', {}, 2);
const cardDelivery = await blk('cardDelivery', 'nocook_card_delivery_seconds', {}, 2);
cardDelivery.byClient = {
  tablet: await blk('cardDelivery.tablet', 'nocook_card_delivery_seconds', { client: 'tablet' }, 2),
  swarm: await blk('cardDelivery.swarm', 'nocook_card_delivery_seconds', { client: 'swarm' }, 2),
};
const cardRender = await blk('cardRender', 'nocook_card_render_seconds', {}, 2);
cardRender.byClient = {
  tablet: await blk('cardRender.tablet', 'nocook_card_render_seconds', { client: 'tablet' }, 2),
  swarm: await blk('cardRender.swarm', 'nocook_card_render_seconds', { client: 'swarm' }, 2),
};
const ackApi = await blk('ackApi', 'http_server_requests_seconds', { uri: ACK_URI }, 0.3);
const timerLateness = {};
for (const kind of ['L1', 'IVR', 'EXPIRY']) timerLateness[kind] = await blk(`timer.${kind}`, 'nocook_deadline_lateness_seconds', { kind }, 3);
const ackFanout = await blk('ackFanout', 'nocook_ack_fanout_seconds', {}, 2);
const consumeLag = await blk('consumeLag', 'nocook_consume_lag_seconds', {}, null);
const omsRead = await blk('omsRead', 'nocook_oms_read_seconds', {}, null);

// ---------- throughput ----------
async function rateStats(counter) {
  const total = await scalar(`sum(increase(${counter}[${D}s] @ ${toSec}))`);
  const series = await range(`sum(increase(${counter}[30s]))`, fromSec, toSec, 5);
  const max30 = maxOf(series);
  return { total: total === null ? 0 : Math.round(total), mean: round((total ?? 0) / k6Seconds, 3), max30s: round(max30 === null ? null : max30 / 30, 3) };
}
const casesRate = await rateStats('nocook_cases_opened_total');
const acksRate = await rateStats('nocook_acks_total');
const firehoseAchieved = round(await scalar(`avg_over_time(oms_firehose_achieved[${D}s] @ ${toSec})`), 0);

let k6 = null;
const k6Path = join(dir, 'k6-summary.json');
if (existsSync(k6Path)) {
  const k6Data = JSON.parse(readFileSync(k6Path, 'utf8'));
  const m = k6Data.metrics ?? {};
  const val = (name, key) => m[name]?.values?.[key] ?? m[name]?.[key];
  k6 = {
    iterations: Math.round(val('iterations', 'count') ?? 0),
    dropped: Math.round(val('dropped_iterations', 'count') ?? 0),
    httpP99: round((val('http_req_duration', 'p(99)') ?? 0) / 1000, 4),
    httpFailedRate: round(val('http_req_failed', 'rate') ?? 0, 6),
    testRunDurationSeconds: round((k6Data.state?.testRunDurationMs ?? 0) / 1000, 1),
  };
}

// ---------- backlog / resources ----------
const queueSeries = await range(`max(nocook_queue_ready{queue="${QUEUE}"})`, Math.floor(startedMs / 1000), Math.ceil(endedMs / 1000), 5);
const outboxSeries = await range('max(nocook_outbox_pending)', Math.floor(startedMs / 1000), Math.ceil(endedMs / 1000), 5);
const overdueSeries = await range('max(nocook_deadlines_overdue)', Math.floor(startedMs / 1000), Math.ceil(endedMs / 1000), 5);
const omsOutboxSeries = await range('max(oms_outbox_pending)', Math.floor(startedMs / 1000), Math.ceil(endedMs / 1000), 5);
let drainSeconds = manifest.drainSeconds ?? null;
if (drainSeconds === null) {
  const afterEnd = Math.ceil(k6EndMs / 1000);
  const q = queueSeries[0]?.values ?? [];
  const o = outboxSeries[0]?.values ?? [];
  const firstZero = q.find(([t, v], i) => t >= afterEnd && v === 0 && (o[i]?.[1] ?? 0) === 0);
  drainSeconds = firstZero ? firstZero[0] - afterEnd : null;
}
const backlog = {
  queuePeak: maxOf(queueSeries),
  drainSeconds,
  outboxPendingPeak: maxOf(outboxSeries),
  omsOutboxPendingPeak: maxOf(omsOutboxSeries),
  deadlinesOverduePeak: maxOf(overdueSeries),
};
const dlqNow = await scalar(`max(nocook_queue_ready{queue="${QUEUE}.dlq"})`, Math.floor(endedMs / 1000));
backlog.dlqAtEnd = dlqNow;

const cpuSeries = await range('process_cpu_usage{job=~"nocook|mock-oms"}', fromSec, toSec, 5);
const cpuPeakByReplica = {};
for (const s of cpuSeries) {
  const key = s.metric.replica ?? s.metric.job;
  cpuPeakByReplica[key] = round(maxOf([s]), 3);
}
const heapSeries = await range('sum by (replica) (jvm_memory_used_bytes{area="heap",job="nocook"})', fromSec, toSec, 5);
const heapPeakMbByReplica = {};
for (const s of heapSeries) heapPeakMbByReplica[s.metric.replica ?? ''] = round((maxOf([s]) ?? 0) / 1048576, 1);
const resources = {
  cpuPeakByReplica,
  heapPeakMbByReplica,
  hikariPendingPeak: maxOf(await range('max(hikaricp_connections_pending{job="nocook"})', fromSec, toSec, 5)),
  omsHikariPendingPeak: maxOf(await range('max(hikaricp_connections_pending{job="mock-oms"})', fromSec, toSec, 5)),
  systemCpuPeak: round(maxOf(await range('max(system_cpu_usage)', fromSec, toSec, 5)), 3),
  note: 'process_cpu_usage is the JVM share of all host cores (0..1)',
};

// ---------- knee (P6) ----------
let knee = null;
if (profile === 'P6') {
  const WIN = 30;
  const start = fromSec + WIN;
  const rates = await range(`sum(increase(nocook_cases_opened_total[${WIN}s]))`, start, toSec, WIN);
  const p99s = await range(`histogram_quantile(0.99, sum by (le) (increase(nocook_case_open_seconds_bucket[${WIN}s])))`, start, toSec, WIN);
  const qAll = (await range(`max(nocook_queue_ready{queue="${QUEUE}"})`, fromSec, toSec, 5))[0]?.values ?? [];
  const slopeTolerance = 0.05; // messages/s: queue noise of 1-2 messages must not disqualify a window
  const slope = (t0, t1) => {
    const pts = qAll.filter(([t, v]) => t > t0 && t <= t1 && v !== null);
    if (pts.length < 2) return 0;
    const mt = pts.reduce((a, [t]) => a + t, 0) / pts.length;
    const mv = pts.reduce((a, [, v]) => a + v, 0) / pts.length;
    const num = pts.reduce((a, [t, v]) => a + (t - mt) * (v - mv), 0);
    const den = pts.reduce((a, [t]) => a + (t - mt) ** 2, 0);
    return den === 0 ? 0 : num / den;
  };
  const windows = [];
  const rateVals = rates[0]?.values ?? [];
  const p99Vals = p99s[0]?.values ?? [];
  for (let i = 0; i < rateVals.length; i++) {
    const [tEnd, count] = rateVals[i];
    const p99 = p99Vals.find(([t]) => t === tEnd)?.[1] ?? null;
    if (count === null) continue;
    windows.push({ end: tEnd, casesPerSecond: round(count / WIN, 3), p99, queueSlope: round(slope(tEnd - WIN, tEnd), 4) });
  }
  const ok = windows.filter((w) => w.casesPerSecond > 0 && w.p99 !== null && w.p99 <= 2 && w.queueSlope <= slopeTolerance);
  if (ok.length) {
    const best = ok.reduce((a, b) => (b.casesPerSecond > a.casesPerSecond ? b : a));
    knee = {
      casesPerSecond: best.casesPerSecond,
      p99AtKnee: round(best.p99),
      windowStart: iso((best.end - WIN) * 1000),
      windows: windows.length,
      queueSlopeToleranceMsgPerSec: slopeTolerance,
      peakWindowCasesPerSecond: round(Math.max(...windows.map((w) => w.casesPerSecond)), 3),
    };
  } else {
    knee = { casesPerSecond: null, p99AtKnee: null, windowStart: null, windows: windows.length, note: 'no 30 s window met p99 <= 2 s with queue slope <= 0' };
  }
  writeFileSync(join(dir, 'knee-windows.json'), JSON.stringify(windows, null, 1));
}

// ---------- invariants (Postgres, as postgres) ----------
// ---------- Postgres access ----------
// Preferred: TCP as postgres/postgres on localhost:5432. On machines where a native Postgres service shadows the
// container's published port (database "nocook" missing), fall back to `docker compose exec postgres psql`.
// PG_VIA=docker|tcp forces one of them.
async function connectDb() {
  const cwd = resolve(dirname(fileURLToPath(import.meta.url)), '..');
  if ((process.env.PG_VIA ?? 'auto') !== 'docker') {
    const client = new pg.Client({
      host: process.env.PGHOST ?? 'localhost', port: Number(process.env.PGPORT ?? 5432),
      user: process.env.PGUSER ?? 'postgres', password: process.env.PGPASSWORD ?? 'postgres', database: process.env.PGDATABASE ?? 'nocook',
    });
    try {
      await client.connect();
      await client.query('select 1 from oms.orders limit 0');
      return { via: 'tcp', query: (sql, params) => client.query(sql, params), end: () => client.end() };
    } catch (err) {
      await client.end().catch(() => {});
      if (process.env.PG_VIA === 'tcp') throw err;
      console.warn(`postgres over TCP unusable (${err.message}); using docker compose exec`);
    }
  }
  const lit = (v) => (v === null || v === undefined ? 'null' : `'${String(v).replace(/'/g, "''")}'`);
  const isoRe = /^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d/;
  const revive = (v) => (typeof v === 'string' && isoRe.test(v) ? new Date(v) : v);
  return {
    via: 'docker',
    end: async () => {},
    query: async (sql, params = []) => {
      const text = sql.replace(/\$(\d+)(::[a-z]+)?/g, (_m, i, cast) => lit(params[Number(i) - 1]) + (cast ?? '')).trim().replace(/;$/, '');
      const wrapped = /^(select|with)/i.test(text) ? `select coalesce(json_agg(t), '[]'::json) from (${text}) t` : text;
      const res = spawnSync('docker', ['compose', '-f', 'infra/docker-compose.yml', 'exec', '-T', 'postgres', 'psql', '-U', 'postgres', '-d', 'nocook', '-v', 'ON_ERROR_STOP=1', '-At', '-c', wrapped],
        { cwd, encoding: 'utf8', maxBuffer: 512 * 1024 * 1024 });
      if (res.status !== 0) throw new Error(`psql failed: ${res.stderr?.slice(0, 400)}`);
      const out = res.stdout.trim();
      if (!/^(select|with)/i.test(text)) return { rows: [] };
      const rows = JSON.parse(out);
      return { rows: rows.map((r) => Object.fromEntries(Object.entries(r).map(([k, v]) => [k, revive(v)]))) };
    },
  };
}
const client = await connectDb();
console.log(`postgres via ${client.via}`);
const one = async (sql, params = []) => Number((await client.query(sql, params)).rows[0].v);
const checkedAt = new Date();
const checks = [];
const add = (name, value, rule, pass, extra = {}) => checks.push({ name, value, rule, pass, ...extra });

add('duplicate_cases', await one('select count(*) v from (select order_id, order_version from nocook.cases group by 1, 2 having count(*) > 1) d'), '== 0', null);
add('max_ivr_per_case', await one('select coalesce(max(c), 0) v from (select case_id, count(*) c from nocook.call_attempts group by 1) x'), '<= 1', null);
add('escalations_after_close', await one(
  `select count(*) v from nocook.case_events e join nocook.cases c on c.id = e.case_id
   where e.kind in ('L1_TRIGGERED','IVR_ESCALATED','IVR_RESERVED') and c.closed_at is not null and e.server_at > c.closed_at`), '== 0', null);
add('escalations_after_max_age', await one(
  `select count(*) v from nocook.case_events e join nocook.cases c on c.id = e.case_id
   where e.kind in ('L1_TRIGGERED','IVR_ESCALATED') and e.server_at >= c.cancelled_at + interval '10 minutes'`), '== 0', null);
add('escalations_on_not_actionable', await one(
  `select count(*) v from nocook.case_events e join nocook.cases c on c.id = e.case_id
   where c.state = 'NOT_ACTIONABLE' and e.kind in ('L1_TRIGGERED','IVR_ESCALATED','IVR_RESERVED')`), '== 0', null);
add('lost_cases', await one(
  `select count(*) v from oms.orders o where o.run_id = $1 and o.status = 'CANCELLED'
   and not exists (select 1 from nocook.cases c where c.order_id = o.id)`, [runId]), '== 0', null);
add('stuck_open', await one(
  `select count(*) v from nocook.cases where state in ('AWAITING_ACK','ESCALATED_L1','ESCALATED_IVR')
   and cancelled_at < $1::timestamptz - interval '10 minutes 30 seconds'`, [checkedAt.toISOString()]), '== 0', null);
add('outbox_stuck', await one(
  `select count(*) v from nocook.outbox where status in ('PENDING','LEASED') and created_at < $1::timestamptz - interval '30 seconds'`,
  [checkedAt.toISOString()]), '== 0', null);

// Fault-specific checks
const faultAt = fault?.at ? new Date(fault.at) : null;
const faultUntil = fault?.until ? new Date(fault.until) : null;
if (fault?.id === 'F4' && faultAt && faultUntil) {
  add('fault_window_escalations', await one(
    `select count(*) v from nocook.case_events where kind in ('L1_TRIGGERED','IVR_ESCALATED') and server_at between $1 and $2`,
    [faultAt.toISOString(), faultUntil.toISOString()]), '== 0 (F4 only)', null);
  const deferred = await scalar(`sum(increase(nocook_escalations_total{outcome="deferred"}[${Math.ceil((endedMs - startedMs) / 1000)}s] @ ${Math.floor(endedMs / 1000)}))`, Math.floor(endedMs / 1000));
  add('f4_deferred_escalations', Math.round(deferred ?? 0), '> 0 (F4 only)', null);
} else {
  add('fault_window_escalations', null, '== 0 (F4 only)', true, { applicable: false });
}
if (fault?.id === 'F7' && faultAt) {
  const raceNotNa = await one(
    `select count(*) v from nocook.cases where outlet_id = 'outlet-race' and opened_at >= $1 and state <> 'NOT_ACTIONABLE'`, [new Date(faultAt.getTime() - 5000).toISOString()]);
  add('f7_race_cases_not_actionable_violations', raceNotNa, '== 0 (F7 only)', null);
}
if (fault?.id === 'F3') {
  add('f3_queue_drained', Math.round(await scalar(`max(nocook_queue_ready{queue="${QUEUE}"})`, Math.floor(endedMs / 1000)) ?? -1), '== 0 (F3 only)', null);
}

// recovery-style fault evidence from rolling 20 s windows
const faultEvidence = {};
async function lastViolation(expr, threshold, afterSec) {
  const s = (await range(expr, afterSec, Math.floor(endedMs / 1000), 5))[0]?.values ?? [];
  let last = null;
  for (const [t, v] of s) if (v !== null && v > threshold) last = t;
  return { last, peak: maxOf([{ values: s }]) };
}
if (fault?.id === 'F2' && faultAt) {
  const at = Math.floor(faultAt.getTime() / 1000);
  const r = await lastViolation('histogram_quantile(0.99, sum by (le) (increase(nocook_case_open_seconds_bucket[20s])))', 2, at);
  const rec = r.last === null ? 0 : r.last - at;
  faultEvidence.caseOpenP99PeakAfterKill = round(r.peak);
  faultEvidence.caseOpenRecoverySeconds = rec;
  add('f2_case_open_recovery_seconds', rec, '<= 60 (F2 only)', null);
}
if (fault?.id === 'F5' && faultAt && faultUntil) {
  const until = Math.floor(faultUntil.getTime() / 1000);
  const r = await lastViolation('histogram_quantile(0.99, sum by (le) (increase(nocook_deadline_lateness_seconds_bucket[20s])))', 3, Math.floor(faultAt.getTime() / 1000));
  const rec = r.last === null || r.last <= until ? 0 : r.last - until;
  faultEvidence.timerLatenessP99Peak = round(r.peak);
  faultEvidence.timerLatenessRecoverySeconds = rec;
  add('f5_timer_lateness_recovery_seconds', rec, '<= 60 (F5 only)', null);
}
const rules = {
  '== 0': (v) => v === 0,
  '<= 1': (v) => v <= 1,
  '== 0 (F4 only)': (v) => v === 0,
  '> 0 (F4 only)': (v) => v > 0,
  '== 0 (F7 only)': (v) => v === 0,
  '== 0 (F3 only)': (v) => v === 0,
  '<= 60 (F2 only)': (v) => v <= 60,
  '<= 60 (F5 only)': (v) => v <= 60,
};
for (const c of checks) if (c.pass === null) c.pass = rules[c.rule](c.value);

// counts block
const byState = {};
for (const r of (await client.query('select state, count(*)::int n from nocook.cases where run_id = $1 group by 1', [runId])).rows) byState[r.state] = r.n;
const openStates = ['AWAITING_ACK', 'ESCALATED_L1', 'ESCALATED_IVR'];
const counts = {
  cases: Object.values(byState).reduce((a, b) => a + b, 0),
  byState: {
    ACKNOWLEDGED: byState.ACKNOWLEDGED ?? 0,
    EXPIRED: byState.EXPIRED ?? 0,
    NOT_ACTIONABLE: byState.NOT_ACTIONABLE ?? 0,
    SUPERSEDED: byState.SUPERSEDED ?? 0,
    open: openStates.reduce((a, s) => a + (byState[s] ?? 0), 0),
  },
  ordersCancelled: await one(`select count(*) v from oms.orders where run_id = $1 and status = 'CANCELLED'`, [runId]),
  duplicatesReceived: await one(`select count(*) v from nocook.case_events e join nocook.cases c on c.id = e.case_id where c.run_id = $1 and e.kind = 'DUPLICATE_EVENT'`, [runId]),
  ivrReserved: await one(`select count(*) v from nocook.call_attempts a join nocook.cases c on c.id = a.case_id where c.run_id = $1`, [runId]),
  ivrDuplicatePrevented: await one(`select count(*) v from nocook.case_events e join nocook.cases c on c.id = e.case_id where c.run_id = $1 and e.kind = 'IVR_DUPLICATE_PREVENTED'`, [runId]),
  lateAcks: await one(`select count(*) v from nocook.case_events e join nocook.cases c on c.id = e.case_id where c.run_id = $1 and e.kind = 'LATE_ACK_IGNORED'`, [runId]),
  leaseTakeovers: Math.round((await scalar(`sum(increase(nocook_lease_takeovers_total[${Math.ceil((endedMs - startedMs) / 1000)}s] @ ${Math.floor(endedMs / 1000)}))`, Math.floor(endedMs / 1000))) ?? 0),
};
if (fault?.id === 'F7') counts.raceCases = await one(`select count(*) v from nocook.cases where outlet_id = 'outlet-race' and opened_at >= $1`, [new Date((faultAt?.getTime() ?? 0) - 5000).toISOString()]);
const invariants = { runId, checkedAt: checkedAt.toISOString(), checks, counts };
const invariantsPass = checks.every((c) => c.pass);
writeFileSync(join(dir, 'invariants.json'), JSON.stringify(invariants, null, 2));

// ---------- cases.csv ----------
const csvCols = ['case_id', 'order_id', 'outlet_id', 'state', 'close_reason', 'ack_channel', 'ack_choice', 'cancelled_at', 'opened_at', 'first_delivered_at', 'first_rendered_at', 'l1_at', 'ivr_at', 'acked_at', 'closed_at', 'case_open_ms', 'delivery_ms', 'ack_ms'];
const caseRows = (await client.query(
  `select c.id::text as case_id, c.order_id, c.outlet_id, c.state, c.close_reason, c.ack_channel, c.ack_choice,
          c.cancelled_at, c.opened_at,
          min(e.server_at) filter (where e.kind = 'CARD_DELIVERED') as first_delivered_at,
          min(e.server_at) filter (where e.kind = 'CARD_RENDERED') as first_rendered_at,
          min(e.server_at) filter (where e.kind = 'L1_TRIGGERED') as l1_at,
          min(e.server_at) filter (where e.kind = 'IVR_ESCALATED') as ivr_at,
          c.acked_at, c.closed_at
   from nocook.cases c left join nocook.case_events e on e.case_id = c.id
   where c.run_id = $1 group by c.id order by c.opened_at`, [runId])).rows;
const csvVal = (v) => (v === null || v === undefined ? '' : v instanceof Date ? v.toISOString() : String(v));
const diff = (a, b) => (a && b ? Math.round((a.getTime() - b.getTime()) * 1000) / 1000 : null);
const lines = [csvCols.join(',')];
for (const r of caseRows) {
  const row = { ...r, case_open_ms: diff(r.opened_at, r.cancelled_at), delivery_ms: diff(r.first_delivered_at, r.opened_at), ack_ms: diff(r.acked_at, r.cancelled_at) };
  lines.push(csvCols.map((c) => csvVal(row[c])).join(','));
}
writeFileSync(join(dir, 'cases.csv'), lines.join('\n') + '\n');
const windowFrom = iso(fromSec * 1000);
const windowTo = iso(toSec * 1000);
const foreignCasesInWindow = (await one('select count(*) v from nocook.cases where opened_at between $1 and $2', [windowFrom, windowTo])) - (await one('select count(*) v from nocook.cases where run_id = $1 and opened_at between $2 and $3', [runId, windowFrom, windowTo]));
await client.end();

// ---------- timeseries.json ----------
const tsFrom = Math.floor(startedMs / 1000);
const tsTo = Math.ceil(endedMs / 1000);
const STEP = 5;
const grid = [];
for (let t = tsFrom; t <= tsTo; t += STEP) grid.push(t);
const seriesDefs = {
  casesOpenedPerSecond: 'sum(rate(nocook_cases_opened_total[15s]))',
  acksPerSecond: 'sum(rate(nocook_acks_total[15s]))',
  queueDepth: `max(nocook_queue_ready{queue="${QUEUE}"})`,
  caseOpenP99: 'histogram_quantile(0.99, sum by (le) (rate(nocook_case_open_seconds_bucket[30s])))',
  timerLatenessP99: 'histogram_quantile(0.99, sum by (le) (rate(nocook_deadline_lateness_seconds_bucket[30s])))',
  outboxPending: 'max(nocook_outbox_pending)',
  deadlinesOverdue: 'max(nocook_deadlines_overdue)',
  firehoseAchieved: 'max(oms_firehose_achieved)',
  openCases: 'max(nocook_cases_open)',
};
const series = {};
for (const [name, expr] of Object.entries(seriesDefs)) {
  const res = (await range(expr, tsFrom, tsTo, STEP))[0]?.values ?? [];
  const byT = new Map(res.map(([t, v]) => [t, v]));
  series[name] = grid.map((t) => round(byT.get(t) ?? null, 4));
}
for (const s of await range('process_cpu_usage{job=~"nocook|mock-oms"}', tsFrom, tsTo, STEP)) {
  const byT = new Map(s.values);
  series[`cpu.${s.metric.replica ?? s.metric.job}`] = grid.map((t) => round(byT.get(t) ?? null, 4));
}
writeFileSync(join(dir, 'timeseries.json'), JSON.stringify({
  runId, stepSeconds: STEP, from: iso(tsFrom * 1000), to: iso(tsTo * 1000), t: grid,
  markers: { runStart: manifest.startedAt, k6Start: manifest.k6StartedAt ?? null, k6End: manifest.k6EndedAt ?? null, fault: fault ? { id: fault.id, at: fault.at, until: fault.until } : null },
  series,
}));

// ---------- verdict ----------
const reasons = [];
let verdict = 'PASS';
if (k6 === null) {
  verdict = 'VOID';
  reasons.push('k6-summary.json missing: cannot confirm the load generator kept up');
} else if (k6.dropped > 0) {
  verdict = 'VOID';
  reasons.push(`k6 dropped ${k6.dropped} iterations: the laptop could not generate the load`);
} else {
  for (const c of checks) if (!c.pass) reasons.push(`invariant ${c.name}=${c.value} violates ${c.rule}`);
  if (['P1', 'P2', 'P3'].includes(profile) && !fault) { // fault runs are judged on invariants + fault checks; SLO numbers are reported only
    if (caseOpen.n === 0) reasons.push('no cases opened in the window');
    else if (caseOpen.shareWithinSlo === null || caseOpen.shareWithinSlo < 0.99) reasons.push(`caseOpen.shareWithinSlo=${caseOpen.shareWithinSlo} < 0.99`);
    if (raw.ackApi !== null && raw.ackApi >= 0.3) reasons.push(`ackApi.p99=${raw.ackApi} s >= 0.3 s${ackApi.n < MIN_N_FOR_P99 ? ` (N=${ackApi.n}, not published)` : ''}`);
  } else if (caseOpen.n === 0) {
    reasons.push('no cases opened in the window');
  }
  if (reasons.length) verdict = 'FAIL';
}

// ---------- notes / bottleneck ----------
let bottleneck = null;
const notesPath = join(dir, 'notes.md');
if (existsSync(notesPath)) {
  const text = readFileSync(notesPath, 'utf8').trim();
  const line = text.split(/\r?\n/).find((l) => /^\W*bottleneck\s*:/i.test(l));
  bottleneck = (line ? line.replace(/^\W*bottleneck\s*:\s*/i, '') : text).trim().slice(0, 600) || null;
}

// ---------- headline ----------
const pct = (v) => (v === null ? 'n/a' : `${(v * 100).toFixed(2)}%`);
const p99Text = caseOpen.p99 !== null ? `${caseOpen.p99} s (N = ${fmtInt(caseOpen.n)})` : `not reported (N = ${fmtInt(caseOpen.n)} < ${fmtInt(MIN_N_FOR_P99)})`;
const lead = {
  P1: "At the modelled dinner peak (93 orders/s) on one laptop",
  P2: "At Zomato's record New Year's Eve peak (140 orders/s) on one laptop",
  P3: "At 10× Zomato's record New Year's Eve peak, on one laptop",
  P4: 'At 100× the modelled cancellation rate (56 cancels/s, 14,000 events/s), on one laptop',
  P5: 'Through a 200 cancels/s burst for 60 s, on one laptop',
  P6: 'Ramping cancellations from 10 to 500/s, on one laptop',
}[profile] ?? `Under profile ${profile}`;
let headline = `${lead}, p99 case-open was ${p99Text}, with ${pct(caseOpen.shareWithinSlo)} of cases inside the 2 s SLO.`;
if (profile === 'P6') {
  headline = knee?.casesPerSecond
    ? `${lead}, the system's limit was ${knee.casesPerSecond} cases/s (case-open p99 ${knee.p99AtKnee} s at the knee)${bottleneck ? `, set by ${bottleneck}` : ' (bottleneck not yet recorded in notes.md)'}.`
    : `${lead}, no 30 s window met p99 <= 2 s with a flat queue.`;
}
if (fault) {
  headline = `${lead} with fault ${fault.id} (${fault.action}) injected: p99 case-open ${p99Text}, ${pct(caseOpen.shareWithinSlo)} inside 2 s, ${checks.find((c) => c.name === 'lost_cases')?.value} lost cases, max ${checks.find((c) => c.name === 'max_ivr_per_case')?.value} IVR call per case, invariants ${invariantsPass ? 'all pass' : 'FAILED'}.`;
}
if (verdict === 'VOID') headline = `VOID: ${reasons[0]}`;

const grafana = {};
for (const [key, uid] of Object.entries({ liveSlos: 'nocook-live-slos', reliability: 'nocook-reliability', load: 'nocook-load' })) {
  grafana[key] = `${GRAFANA}/d/${uid}?orgId=1&from=${startedMs}&to=${endedMs + 15_000}`;
}

const summary = {
  runId, profile, kind: manifest.kind ?? 'MEASURED',
  startedAt: manifest.startedAt, durationSeconds: Math.round((endedMs - startedMs) / 1000),
  targetRate: profile === 'P6' ? 500 : profile === 'P5' ? 200 : (manifest.rates?.cancelsPerSecond ?? null),
  fault: fault ? { id: fault.id, action: fault.action, at: fault.at, until: fault.until } : null,
  replicas: manifest.replicas,
  window: { from: iso(fromSec * 1000), to: iso(toSec * 1000), seconds: D, k6Seconds: round(k6Seconds, 1) },
  verdict, verdictReasons: reasons,
  latency: { caseOpen, cardDelivery, cardRender, ackApi, timerLateness, ackFanout, consumeLag, omsRead },
  throughput: {
    casesOpenedPerSecond: { mean: casesRate.mean, max30s: casesRate.max30s },
    acksPerSecond: { mean: acksRate.mean, max30s: acksRate.max30s },
    casesOpenedInWindow: casesRate.total,
    firehoseAchieved,
    k6,
  },
  backlog, resources, knee, bottleneck,
  invariantsPass,
  faultEvidence: fault ? faultEvidence : undefined,
  warnings: foreignCasesInWindow > 0 ? [`${foreignCasesInWindow} cases from other sources (other run_id / manual traffic) opened inside this window: Prometheus latency blocks include them. Re-run on an otherwise idle stack for a proof run.`] : [],
  headline, grafana,
  notes: { p99Rule: `p99 is null when N < ${MIN_N_FOR_P99}`, cardDeliveryIncludesSwarmLag: true },
};
writeFileSync(join(dir, 'summary.json'), JSON.stringify(summary, null, 2));

console.log(`exported ${runId}: verdict ${verdict}${reasons.length ? ` (${reasons.join('; ')})` : ''}`);
console.log(`  caseOpen N=${caseOpen.n} p50=${caseOpen.p50} p95=${caseOpen.p95} p99=${caseOpen.p99} within2s=${caseOpen.shareWithinSlo}`);
if (summary.warnings.length) console.log(`  WARNING: ${summary.warnings[0]}`);
console.log(`  ackApi N=${ackApi.n} p99=${ackApi.p99}; cases=${counts.cases}; invariants ${invariantsPass ? 'PASS' : 'FAIL'}`);
console.log(`  ${headline}`);

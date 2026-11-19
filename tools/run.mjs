#!/usr/bin/env node
// Q-07: one measured run (docs/LOAD_AND_CHAOS.md section 5, fault runs section 6).
//   node tools/run.mjs --profile P3 [--fault F2..F7] [--replicas 1|3] [--note "..."]
//   validation only: [--duration 3m] shortens k6 AND the post-run waits; [--tag validation] suffixes the runId.
// Env fallbacks: REPLICAS, NOTE, TRACING_SAMPLE (recorded; replicas are not restarted by this script).
// Env: BASE_URL (http://localhost:8080) RABBIT_URL (http://localhost:15672) PGHOST/PGPORT (localhost:5432, postgres/postgres)
import { spawn, spawnSync } from 'node:child_process';
import { createWriteStream, existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import os from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import pg from 'pg';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const BASE = (process.env.BASE_URL ?? 'http://localhost:8080').replace(/\/$/, '');
const RABBIT = (process.env.RABBIT_URL ?? 'http://localhost:15672').replace(/\/$/, '');
const COMPOSE = ['compose', '-f', 'infra/docker-compose.yml'];
const QUEUE = 'nocook.cancellations';

// ---------- args ----------
const argv = process.argv.slice(2);
const arg = (name, envName) => {
  const i = argv.indexOf(`--${name}`);
  if (i >= 0) return argv[i + 1];
  return envName && process.env[envName] ? process.env[envName] : undefined;
};
const profileArg = (arg('profile', 'P') ?? '').toUpperCase();
const faultArg = (arg('fault', 'FAULT') ?? '').toUpperCase() || null;
const replicas = Number(arg('replicas', 'REPLICAS') ?? 3);
const note = arg('note', 'NOTE') ?? '';
const durationArg = arg('duration');
const tag = arg('tag');

// profile table (LOAD_AND_CHAOS.md section 2): k6 seconds, cancels/s, firehose events/s, notional orders/s
const PROFILES = {
  P1: { cancels: 0.56, firehose: 933, orders: 93, seconds: 300 },
  P2: { cancels: 0.84, firehose: 1400, orders: 140, seconds: 1200 },
  P3: { cancels: 8.4, firehose: 14000, orders: 1400, seconds: 600 },
  P4: { cancels: 56, firehose: 14000, orders: 1400, seconds: 600 },
  P5: { cancels: 200, firehose: 14000, orders: 1400, seconds: 300 },
  P6: { cancels: 500, firehose: 14000, orders: 1400, seconds: 600 },
};
// F-runs: action, params, offset (s into k6), hold (s) before the undo action
const FAULTS = {
  F2: { action: 'kill-replica', params: { replica: 'nocook-2' }, at: 180, hold: null },
  F3: { action: 'listener', params: { state: 'STOPPED' }, at: 180, hold: 60 },
  F4: { action: 'oms-down', params: { down: true }, at: 180, hold: 30 },
  F5: { action: 'db-latency', params: { ms: 300 }, at: 180, hold: 60 },
  F6: { action: 'duplicate-storm', params: { lastN: 50, times: 20 }, at: 120, hold: null },
  F7: { action: 'race-ready-then-cancel', params: { count: 500, outletId: 'outlet-race', gapMillis: 5 }, at: 120, hold: null },
};
if (!PROFILES[profileArg] || (faultArg && !FAULTS[faultArg]) || ![1, 3].includes(replicas) || (faultArg === 'F2' && replicas < 2)) {
  console.error('usage: node tools/run.mjs --profile P1..P6 [--fault F2..F7] [--replicas 1|3] [--note "..."] [--duration 3m --tag validation]');
  process.exit(2);
}
if (faultArg && profileArg !== 'P3' && !durationArg) console.warn('note: LOAD_AND_CHAOS section 6 says fault runs use background P3');
const profile = PROFILES[profileArg];
const parseDur = (s) => {
  const m = /^(\d+)(s|m)$/.exec(s ?? '');
  return m ? Number(m[1]) * (m[2] === 'm' ? 60 : 1) : null;
};
if (durationArg && parseDur(durationArg) === null) {
  console.error('--duration must look like 90s or 3m');
  process.exit(2);
}
const shortened = Boolean(durationArg);
const k6Seconds = shortened ? parseDur(durationArg) : profile.seconds;
const drainMaxSeconds = shortened ? 45 : 120;
const settleSeconds = shortened ? 15 : 90;
const warmupSeconds = 30;

// ---------- small helpers ----------
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const log = (msg) => console.log(`[${new Date().toISOString().slice(11, 19)}] ${msg}`);
const run = (cmd, args, opts = {}) => spawnSync(cmd, args, { cwd: root, encoding: 'utf8', shell: false, ...opts });
async function http(method, path, body, base = BASE) {
  const res = await fetch(`${base}${path}`, {
    method,
    headers: body === undefined ? {} : { 'content-type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(15_000),
  });
  const text = await res.text();
  if (!res.ok) throw new Error(`${method} ${path} -> ${res.status} ${text.slice(0, 200)}`);
  try {
    return JSON.parse(text);
  } catch {
    return text;
  }
}
const rabbitAuth = { authorization: `Basic ${Buffer.from('nocook:nocook').toString('base64')}` };
async function queueState() {
  const res = await fetch(`${RABBIT}/api/queues/%2F/${QUEUE}`, { headers: rabbitAuth, signal: AbortSignal.timeout(10_000) });
  const q = await res.json();
  return { ready: q.messages_ready ?? 0, unacked: q.messages_unacknowledged ?? 0, consumers: q.consumers ?? 0 };
}
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
let dbPromise;
async function outboxPending() {
  const c = await (dbPromise ??= connectDb());
  {
    const nocook = Number((await c.query(`select count(*) v from nocook.outbox where status in ('PENDING','LEASED')`)).rows[0].v);
    const oms = Number((await c.query(`select count(*) v from oms.outbox where status = 'PENDING'`)).rows[0].v);
    return { nocook, oms };
  }
}
async function waitReplicas(expected, timeoutSeconds = 150) {
  const deadline = Date.now() + timeoutSeconds * 1000;
  while (Date.now() < deadline) {
    try {
      const s = await http('GET', '/api/ops/summary');
      const up = s.replicas.filter((r) => r.up).length;
      if (up >= expected) return true;
    } catch {
      /* web restarting */
    }
    await sleep(3000);
  }
  return false;
}

// ---------- ids ----------
const now = new Date();
const pad = (n) => String(n).padStart(2, '0');
const stamp = `${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}-${pad(now.getHours())}${pad(now.getMinutes())}`;
const runId = `run-${stamp}-${profileArg}${faultArg ? `-${faultArg}` : ''}${replicas === 1 ? '-1rep' : ''}${tag ? `-${tag}` : ''}`;
const dir = join(root, 'results', runId);
const manifestPath = join(dir, 'manifest.json');

// ---------- manifest ----------
function readEnvFile() {
  const out = {};
  const p = join(root, 'infra', '.env');
  if (!existsSync(p)) return out;
  for (const line of readFileSync(p, 'utf8').split(/\r?\n/)) {
    const m = /^([A-Z0-9_]+)=(.*)$/.exec(line.trim());
    if (m) out[m[1]] = m[2];
  }
  return out;
}
const envFile = readEnvFile();
const gitOut = (args) => run('git', args).stdout?.trim() ?? '';
const dockerInfo = run('docker', ['info', '--format', '{{.NCPU}} {{.MemTotal}}']).stdout?.trim().split(' ') ?? [];
const cpus = os.cpus();
const manifest = {
  runId, profile: profileArg, kind: faultArg ? 'TESTED' : 'MEASURED',
  startedAt: null, k6StartedAt: null, k6EndedAt: null, endedAt: null,
  window: { from: null, to: null },
  rates: { cancelsPerSecond: profile.cancels, firehoseEventsPerSecond: profile.firehose, notionalOrdersPerSecond: profile.orders },
  replicas, tracingSample: Number(process.env.TRACING_SAMPLE ?? envFile.TRACING_SAMPLE ?? 1.0),
  fault: faultArg
    ? { id: faultArg, action: FAULTS[faultArg].action, params: FAULTS[faultArg].params, plannedOffsetSeconds: null, at: null, until: null }
    : null,
  commit: gitOut(['rev-parse', '--short', 'HEAD']) || null,
  dirty: gitOut(['status', '--porcelain', '--', '.', ':!results']).length > 0,
  images: {
    postgres: envFile.POSTGRES_IMAGE, rabbitmq: envFile.RABBITMQ_IMAGE, mosquitto: envFile.MOSQUITTO_IMAGE,
    toxiproxy: envFile.TOXIPROXY_IMAGE, lgtm: envFile.LGTM_IMAGE, k6: envFile.K6_IMAGE, nginx: envFile.NGINX_IMAGE, node: envFile.NODE_IMAGE,
  },
  host: {
    cpuModel: cpus[0]?.model?.trim(), cores: cpus.length, ramGb: Math.round(os.totalmem() / 2 ** 30),
    os: `${os.type()} ${os.release()} (${os.version?.() ?? ''})`.trim(),
    docker: run('docker', ['version', '--format', '{{.Server.Version}}']).stdout?.trim(),
    dockerCpus: Number(dockerInfo[0]) || null, dockerMemGb: dockerInfo[1] ? Math.round(Number(dockerInfo[1]) / 2 ** 30 * 10) / 10 : null,
  },
  k6DurationSeconds: k6Seconds,
  shortenedValidationRun: shortened || undefined,
  note,
};
const saveManifest = () => writeFileSync(manifestPath, JSON.stringify(manifest, null, 2));

// ---------- restore / state ----------
let runStartSent = false;
let runEndSent = false;
let firehoseStarted = false;
let restored = false;
async function sendMarker(kind) {
  try {
    await http('POST', '/api/ops/run-marker', { kind, runId, profile: profileArg });
    return true;
  } catch (err) {
    log(`WARN run-marker ${kind} failed: ${err.message}`);
    return false;
  }
}
async function restoreStack() {
  if (restored) return;
  restored = true;
  log('restoring stack: firehose off, chaos reset, 3 replicas up');
  if (firehoseStarted) await http('DELETE', '/oms/api/firehose').catch((e) => log(`WARN firehose stop: ${e.message}`));
  await http('POST', '/api/ops/chaos/reset', {}).catch((e) => log(`WARN chaos reset: ${e.message}`));
  const up = run('docker', [...COMPOSE, 'up', '-d', 'nocook-1', 'nocook-2', 'nocook-3'], { stdio: 'pipe' });
  if (up.status !== 0) log(`WARN compose up replicas: ${up.stderr?.slice(-300)}`);
  if (!(await waitReplicas(3, 180))) log('WARN: not all 3 replicas reported up after restore');
  await http('POST', '/api/ops/chaos/reset', {}).catch(() => {});
  if (runStartSent && !runEndSent) {
    await sendMarker('RUN_END');
    runEndSent = true;
  }
  const state = await http('GET', '/api/ops/chaos/state').catch(() => null);
  log(`chaos state after restore: ${JSON.stringify(state)}`);
}
for (const sig of ['SIGINT', 'SIGTERM']) {
  process.on(sig, async () => {
    log(`${sig}: restoring before exit`);
    await restoreStack();
    process.exit(130);
  });
}

// ---------- fault injection ----------
async function injectFault(k6StartMs) {
  const spec = FAULTS[faultArg];
  const offset = shortened ? Math.min(spec.at, Math.floor(k6Seconds * 0.4)) : spec.at;
  manifest.fault.plannedOffsetSeconds = offset;
  const waitMs = k6StartMs + offset * 1000 - Date.now();
  if (waitMs > 0) await sleep(waitMs);
  const chaos = (action, body) => http('POST', `/api/ops/chaos/${action}`, body);
  manifest.fault.at = new Date().toISOString();
  log(`FAULT ${faultArg}: ${spec.action} ${JSON.stringify(spec.params)}`);
  if (faultArg === 'F7') {
    await http('POST', '/oms/api/test/race/ready-then-cancel', spec.params);
  } else {
    await chaos(spec.action, spec.params);
  }
  saveManifest();
  if (spec.hold) {
    await sleep(spec.hold * 1000);
    const undo = { F3: ['listener', { state: 'RUNNING' }], F4: ['oms-down', { down: false }], F5: ['db-latency', { ms: 0 }] }[faultArg];
    await chaos(undo[0], undo[1]);
    manifest.fault.until = new Date().toISOString();
    log(`FAULT ${faultArg} undone`);
    saveManifest();
  }
}

// ---------- main ----------
let exitCode = 0;
let k6Ok = false;
try {
  // preflight
  log(`runId ${runId} (profile ${profileArg}${faultArg ? ` + ${faultArg}` : ''}, replicas ${replicas}, k6 ${k6Seconds}s${shortened ? ' SHORTENED validation' : ''})`);
  const summary = await http('GET', '/api/ops/summary');
  log(`web healthy; ops summary: openCases=${summary.openCases} queueReady=${summary.queueReady} outboxPending=${summary.outboxPending}`);
  await http('POST', '/api/ops/chaos/reset', {});
  if (replicas === 1) {
    log('REPLICAS=1: stopping nocook-2 and nocook-3');
    run('docker', [...COMPOSE, 'stop', 'nocook-2', 'nocook-3'], { stdio: 'pipe' });
    await sleep(8000);
  } else if (!(await waitReplicas(3, 60))) {
    throw new Error('preflight: fewer than 3 replicas are up (make up / docker compose up -d nocook-1 nocook-2 nocook-3)');
  }
  let q = await queueState();
  for (let i = 0; i < 12 && (q.ready !== 0 || q.unacked !== 0); i++) {
    await sleep(5000);
    q = await queueState();
  }
  if (q.ready !== 0) throw new Error(`preflight: queue ${QUEUE} ready=${q.ready}, expected 0 (run make demo-reset or wait for drain)`);
  if (q.consumers < 1) throw new Error('preflight: no consumers on the cancellations queue');
  if (summary.openCases > 0) log(`WARN ${summary.openCases} open cases exist before the run (make demo-reset for a clean proof run)`);
  if (['P4', 'P5', 'P6'].includes(profileArg) && manifest.tracingSample >= 1) {
    log('NOTE: TRACING_SAMPLE=1.0 for a P4-P6 run; docs prefer 0.1 (restart replicas with TRACING_SAMPLE=0.1 beforehand if Tempo struggles)');
  }
  mkdirSync(dir, { recursive: true });
  saveManifest();

  // RUN_START + firehose + warm-up
  manifest.startedAt = new Date().toISOString();
  saveManifest();
  runStartSent = await sendMarker('RUN_START');
  await http('POST', '/oms/api/firehose', { eventsPerSecond: profile.firehose });
  firehoseStarted = true;
  log(`firehose started at ${profile.firehose}/s; warm-up ${warmupSeconds}s`);
  const swarm = run('docker', [...COMPOSE, '--profile', 'load', 'up', '-d', 'swarm'], { stdio: 'pipe' });
  if (swarm.status !== 0) log(`WARN swarm up failed: ${swarm.stderr?.slice(-300)}`);
  await sleep(warmupSeconds * 1000);

  // k6
  const k6Args = [...COMPOSE, '--profile', 'load', 'run', '--rm', '-T', '-e', `RUN_ID=${runId}`, '-e', `PROFILE=${profileArg}`];
  if (shortened) k6Args.push('-e', `DURATION=${durationArg}`);
  k6Args.push('k6', 'run', '--quiet', '-o', 'opentelemetry', '/scripts/profiles.js');
  const k6StartMs = Date.now();
  manifest.k6StartedAt = new Date(k6StartMs).toISOString();
  saveManifest();
  log('k6 starting');
  const k6Log = createWriteStream(join(dir, 'k6.log'));
  const k6Proc = spawn('docker', k6Args, { cwd: root, stdio: ['ignore', 'pipe', 'pipe'] });
  k6Proc.stdout.on('data', (d) => { k6Log.write(d); process.stdout.write(d); });
  k6Proc.stderr.on('data', (d) => { k6Log.write(d); });
  const faultTask = faultArg ? injectFault(k6StartMs).catch((e) => { log(`FAULT ERROR: ${e.message}`); manifest.fault.error = e.message; }) : Promise.resolve();
  const k6Code = await new Promise((res) => k6Proc.on('close', res));
  k6Log.end();
  manifest.k6EndedAt = new Date().toISOString();
  log(`k6 exited with code ${k6Code} (99 = thresholds crossed; the verdict decides)`);
  k6Ok = existsSync(join(dir, 'k6-summary.json'));
  await faultTask;
  if (faultArg === 'F2') {
    log('restarting nocook-2 after the F2 run');
    run('docker', [...COMPOSE, 'up', '-d', 'nocook-2'], { stdio: 'pipe' });
    manifest.fault.until = null;
  }
  saveManifest();

  // drain: queue ready 0 and outbox 0 (max drainMax), then settle
  const drainStart = Date.now();
  let zeroStreak = 0;
  let drained = false;
  while ((Date.now() - drainStart) / 1000 < drainMaxSeconds) {
    const [qs, ob] = await Promise.all([queueState(), outboxPending()]);
    // RabbitMQ management stats lag ~5 s: require two consecutive zero readings
    zeroStreak = qs.ready === 0 && qs.unacked === 0 && ob.nocook === 0 && ob.oms === 0 ? zeroStreak + 1 : 0;
    if (zeroStreak >= 2) { drained = true; break; }
    await sleep(3000);
  }
  manifest.drainSeconds = Math.max(0, Math.round((Date.now() - drainStart) / 1000) - (drained ? 5 : 0));
  manifest.drained = drained;
  log(`drain ${drained ? 'complete' : 'NOT complete'} after ${manifest.drainSeconds}s; settling ${settleSeconds}s`);
  await sleep(settleSeconds * 1000);

  await http('DELETE', '/oms/api/firehose').catch((e) => log(`WARN firehose stop: ${e.message}`));
  firehoseStarted = false;
  await sleep(6000); // let the last 5 s metric export land before the window closes
  runEndSent = await sendMarker('RUN_END');
  manifest.endedAt = new Date().toISOString();
  manifest.window = { from: new Date(Date.parse(manifest.startedAt) + warmupSeconds * 1000).toISOString(), to: manifest.k6EndedAt };
  saveManifest();
} catch (err) {
  exitCode = 1;
  console.error(`RUN FAILED: ${err.stack ?? err.message}`);
  manifest.error = err.message;
  manifest.endedAt ??= new Date().toISOString();
  if (existsSync(dir)) saveManifest();
} finally {
  await restoreStack().catch((e) => console.error(`restore failed: ${e.message}`));
}

// ---------- export ----------
if (manifest.startedAt && manifest.k6EndedAt) {
  const ex = spawnSync('node', ['tools/export-run.mjs', '--run', runId], { cwd: root, stdio: 'inherit' });
  if (ex.status !== 0) exitCode = exitCode || 1;
  const summaryPath = join(dir, 'summary.json');
  if (existsSync(summaryPath)) {
    const s = JSON.parse(readFileSync(summaryPath, 'utf8'));
    console.log(`\nHEADLINE: ${s.headline}`);
    console.log(`VERDICT: ${s.verdict}${s.verdictReasons?.length ? ` (${s.verdictReasons.join('; ')})` : ''}`);
    if (!k6Ok) console.log('WARN: k6-summary.json missing');
    if (s.verdict !== 'PASS') exitCode = exitCode || 1;
  }
}
process.exit(exitCode);

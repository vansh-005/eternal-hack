#!/usr/bin/env node
// NoCook system smoke (Q-04, G1 gate). Spec: docs/TESTING.md section 5.
// Real wall-clock timings (takeover +30 s, IVR call +75 s). No shortened timings.
//
// Usage:
//   node tools/smoke.mjs                 full run (~90 s)
//   node tools/smoke.mjs --only 1,2,3    run just these steps (steps needing earlier state will fail clearly)
//   node tools/smoke.mjs --until 3       run steps 1..3
//   node tools/smoke.mjs --skip-wait     skip the long waits (steps 4-7): runs 1,2,3,8,9
//   node tools/smoke.mjs --help
// Env: BASE_URL (default http://localhost:8080), MQTT_URL (mqtt://localhost:1883), PROM_URL (http://localhost:9090)
import mqtt from 'mqtt';

const BASE = (process.env.BASE_URL ?? 'http://localhost:8080').replace(/\/$/, '');
const MQTT_URL = process.env.MQTT_URL ?? 'mqtt://localhost:1883';
const PROM = (process.env.PROM_URL ?? 'http://localhost:9090').replace(/\/$/, '');
const OUTLET = 'outlet-smoke';
const DEVICE = 'tablet-smoke';
const TOL_MS = 3000;
const TAKEOVER_MS = 30_000;
const CALL_MS = 75_000;
const CASE_OPEN_MAX_MS = 5000;

// ---------- args ----------
const argv = process.argv.slice(2);
if (argv.includes('--help') || argv.includes('-h')) {
  console.log(
    'smoke.mjs [--only 1,2,3] [--until N] [--skip-wait]\n' +
      'Steps: 1 order+cancel, 2 case opens <5s, 3 MQTT OPEN+receipts, 4 TAKEOVER@+30s, 5 call@+75s+answer+DTMF,\n' +
      '       6 CLOSE(IVR/STOPPED)+DISMISSED, 7 timeline order, 8 ACK idempotency, 9 Prometheus, 10 PASS',
  );
  process.exit(0);
}
const argVal = (name) => {
  const i = argv.indexOf(name);
  return i >= 0 ? argv[i + 1] : undefined;
};
let enabled = new Set([1, 2, 3, 4, 5, 6, 7, 8, 9]);
if (argVal('--only')) enabled = new Set(argVal('--only').split(',').map(Number));
else if (argVal('--until')) enabled = new Set([...Array(Number(argVal('--until'))).keys()].map((i) => i + 1));
else if (argv.includes('--skip-wait')) enabled = new Set([1, 2, 3, 8, 9]);

// ---------- helpers ----------
const T0 = Date.now();
const timings = []; // {step, label, ms}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const ts = () => `+${((Date.now() - T0) / 1000).toFixed(1).padStart(5)}s`;
const log = (m) => console.log(`[${ts()}] ${m}`);

class StepFail extends Error {
  constructor(step, expectation, actual) {
    super(`FAIL step ${step}: expected ${expectation}; got ${actual}`);
    this.step = step;
  }
}
const fail = (step, expectation, actual) => {
  throw new StepFail(step, expectation, typeof actual === 'string' ? actual : JSON.stringify(actual));
};
const record = (step, label, ms) => {
  timings.push({ step, label, ms });
  log(`step ${step}: ${label} = ${Math.round(ms)} ms`);
};

async function http(method, path, { body, headers = {}, base = BASE, raw = false } = {}) {
  const res = await fetch(base + path, {
    method,
    headers: { ...(body !== undefined ? { 'content-type': 'application/json' } : {}), ...headers },
    body: body !== undefined ? JSON.stringify(body) : undefined,
    signal: AbortSignal.timeout(10_000),
  });
  const text = await res.text();
  let json = null;
  try {
    json = text ? JSON.parse(text) : null;
  } catch {
    /* non-JSON body */
  }
  return { status: res.status, json, text };
}
const tablet = { 'X-Outlet-Id': OUTLET, 'X-Device-Id': DEVICE };

async function oms(step, method, path, body, expect = [200, 201]) {
  const r = await http(method, '/oms/api' + path, { body });
  if (!expect.includes(r.status)) fail(step, `${method} /oms/api${path} -> ${expect.join('/')}`, `${r.status} ${r.text.slice(0, 200)}`);
  return r.json;
}

// ---------- MQTT ----------
const inbox = []; // {topic, kind, msg, at}
let client = null;
async function mqttConnect() {
  client = mqtt.connect(MQTT_URL, { clientId: 'tablet-smoke', protocolVersion: 5, clean: true, reconnectPeriod: 1000 });
  await new Promise((res, rej) => {
    const t = setTimeout(() => rej(new Error(`MQTT connect timeout ${MQTT_URL}`)), 8000);
    client.once('connect', () => (clearTimeout(t), res()));
    client.once('error', (e) => (clearTimeout(t), rej(e)));
  });
  client.on('message', (topic, buf) => {
    try {
      const msg = JSON.parse(buf.toString());
      inbox.push({ topic, kind: msg.kind, msg, at: Date.now() });
    } catch {
      /* ignore garbage */
    }
  });
  await new Promise((res, rej) =>
    client.subscribe([`nocook/outlets/${OUTLET}/cards`, `nocook/outlets/${OUTLET}/calls`], { qos: 1 }, (e) => (e ? rej(e) : res())),
  );
  log(`MQTT connected as tablet-smoke, subscribed to cards+calls for ${OUTLET}`);
}
async function waitMsg(step, desc, pred, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    const hit = inbox.find(pred);
    if (hit) return hit;
    if (Date.now() > deadline) fail(step, desc, `nothing within ${Math.round(timeoutMs)} ms (seen: ${inbox.map((m) => m.kind + ':' + (m.msg.caseId ?? '').slice(-6)).join(', ') || 'none'})`);
    await sleep(50);
  }
}

// ---------- shared state ----------
const S = { order: null, caseId: null, cancelAt: 0, caseSeen: null, card: null };

async function createCancelledOrder(step, label) {
  const order = await oms(step, 'POST', '/orders', {
    outletId: OUTLET,
    items: [{ name: `smoke-${label}`, qty: 1, pricePaise: 25000 }],
  });
  const id = order.orderId ?? fail(step, 'order create returns orderId', order);
  await oms(step, 'POST', `/orders/${id}/accept`);
  await oms(step, 'POST', `/orders/${id}/stage`, { stage: 'PREP' });
  const cancel = await oms(step, 'POST', `/orders/${id}/cancel`, { initiatedBy: 'CUSTOMER' });
  const cancelAt = Date.now();
  if (cancel?.status && cancel.status !== 'CANCELLED') fail(step, 'cancel response status CANCELLED', cancel.status);
  return { orderId: id, cancelAt, cancelledAtServer: cancel?.cancelledAt ?? null };
}

async function pollCase(step, orderId, cancelAt, maxMs) {
  for (;;) {
    const r = await http('GET', `/api/outlets/${OUTLET}/open-cases`, { headers: tablet });
    if (r.status !== 200) fail(step, 'open-cases 200', `${r.status} ${r.text.slice(0, 200)}`);
    const c = (r.json ?? []).find((x) => x.orderId === orderId);
    if (c) return c;
    if (Date.now() - cancelAt > maxMs) fail(step, `case for ${orderId} visible in open-cases within ${maxMs} ms of cancel`, `absent after ${Date.now() - cancelAt} ms`);
    await sleep(100);
  }
}

// ---------- steps ----------
const steps = {
  1: async () => {
    const o = await createCancelledOrder(1, 'main');
    S.order = o.orderId;
    S.cancelAt = o.cancelAt;
    log(`step 1: ${o.orderId} placed -> accepted -> PREP -> cancelled (CUSTOMER)`);
  },

  2: async () => {
    if (!S.order) fail(2, 'step 1 ran first (order exists)', 'no order; run step 1 too');
    const c = await pollCase(2, S.order, S.cancelAt, CASE_OPEN_MAX_MS);
    const ms = Date.now() - S.cancelAt;
    S.caseId = c.caseId;
    S.caseSeen = c;
    if (ms > CASE_OPEN_MAX_MS) fail(2, `case open <= ${CASE_OPEN_MAX_MS} ms`, `${ms} ms`);
    record(2, 'case-open (cancel -> visible in open-cases)', ms);
  },

  3: async () => {
    if (!S.caseId) fail(3, 'step 2 ran first (caseId known)', 'no case; run step 2 too');
    const open = await waitMsg(3, `OPEN card for case ${S.caseId} on MQTT`, (m) => m.kind === 'OPEN' && m.msg.caseId === S.caseId, 10_000);
    S.card = open.msg;
    record(3, 'cancel -> OPEN card on MQTT', open.at - S.cancelAt);
    for (const kind of ['DELIVERED', 'RENDERED']) {
      const r = await http('POST', `/api/cases/${S.caseId}/receipts`, {
        headers: tablet,
        body: { kind, deviceId: DEVICE, messageId: open.msg.messageId, deviceAt: new Date().toISOString() },
      });
      if (r.status !== 202) fail(3, `POST receipts ${kind} -> 202`, `${r.status} ${r.text.slice(0, 200)}`);
    }
    log('step 3: DELIVERED + RENDERED receipts accepted (202)');
  },

  4: async () => {
    if (!S.caseId) fail(4, 'earlier steps ran (caseId known)', 'no case');
    const m = await waitMsg(4, `TAKEOVER card for case ${S.caseId}`, (x) => x.kind === 'TAKEOVER' && x.msg.caseId === S.caseId, TAKEOVER_MS + TOL_MS + 5000);
    const ms = m.at - S.cancelAt;
    record(4, 'cancel -> TAKEOVER', ms);
    if (Math.abs(ms - TAKEOVER_MS) > TOL_MS) fail(4, `TAKEOVER at cancel+30 s +/- 3 s`, `${(ms / 1000).toFixed(1)} s`);
  },

  5: async () => {
    if (!S.caseId) fail(5, 'earlier steps ran (caseId known)', 'no case');
    const m = await waitMsg(5, `INCOMING_CALL for case ${S.caseId}`, (x) => x.kind === 'INCOMING_CALL' && x.msg.caseId === S.caseId, CALL_MS + TOL_MS + 5000);
    const ms = m.at - S.cancelAt;
    record(5, 'cancel -> INCOMING_CALL', ms);
    if (Math.abs(ms - CALL_MS) > TOL_MS) fail(5, 'INCOMING_CALL at cancel+75 s +/- 3 s', `${(ms / 1000).toFixed(1)} s`);
    const id = m.msg.providerCallId ?? fail(5, 'INCOMING_CALL carries providerCallId', m.msg);
    const ans = await http('POST', `/api/mock-telephony/calls/${id}/answer`);
    if (ans.status !== 202) fail(5, 'mock-telephony answer -> 202', `${ans.status} ${ans.text.slice(0, 200)}`);
    await sleep(300); // let the async ANSWERED webhook land before the DTMF
    const dt = await http('POST', `/api/mock-telephony/calls/${id}/dtmf`, { body: { digit: '1' } });
    if (dt.status !== 202) fail(5, 'mock-telephony dtmf -> 202', `${dt.status} ${dt.text.slice(0, 200)}`);
    log(`step 5: call ${id} answered, DTMF 1 sent`);
  },

  6: async () => {
    if (!S.caseId) fail(6, 'earlier steps ran (caseId known)', 'no case');
    const m = await waitMsg(6, `CLOSE card for case ${S.caseId}`, (x) => x.kind === 'CLOSE' && x.msg.caseId === S.caseId, 15_000);
    const c = m.msg;
    if (c.ackChannel !== 'IVR' || c.ackChoice !== 'STOPPED')
      fail(6, 'CLOSE with ackChannel=IVR and ackChoice=STOPPED', `ackChannel=${c.ackChannel} ackChoice=${c.ackChoice} closeReason=${c.closeReason}`);
    record(6, 'cancel -> CLOSE (IVR ack)', m.at - S.cancelAt);
    const r = await http('POST', `/api/cases/${S.caseId}/receipts`, {
      headers: tablet,
      body: { kind: 'DISMISSED', deviceId: DEVICE, messageId: c.messageId, deviceAt: new Date().toISOString() },
    });
    if (r.status !== 202) fail(6, 'POST receipts DISMISSED -> 202', `${r.status} ${r.text.slice(0, 200)}`);
  },

  7: async () => {
    if (!S.caseId) fail(7, 'earlier steps ran (caseId known)', 'no case');
    const want = ['CASE_OPENED', 'CARD_DISPATCHED', 'CARD_DELIVERED', 'CARD_RENDERED', 'L1_TRIGGERED', 'IVR_ESCALATED', 'IVR_RESERVED', 'CALL_DIALING', 'CALL_ANSWERED', 'DTMF_ACKNOWLEDGED', 'CASE_CLOSED'];
    let kinds = [];
    // CASE_CLOSED / receipts are written asynchronously; allow a short settle window.
    for (let i = 0; i < 20; i++) {
      const r = await http('GET', `/api/cases/${S.caseId}/timeline`);
      if (r.status !== 200) fail(7, 'timeline 200', `${r.status} ${r.text.slice(0, 200)}`);
      kinds = (r.json?.events ?? []).map((e) => e.kind);
      let pos = 0;
      for (const k of kinds) if (k === want[pos]) pos++;
      if (pos === want.length) return log(`step 7: timeline contains all ${want.length} events in order`);
      await sleep(500);
    }
    let pos = 0;
    for (const k of kinds) if (k === want[pos]) pos++;
    fail(7, `timeline subsequence in order ${want.join(', ')}`, `missing/out-of-order from ${want[pos]}; actual: ${kinds.join(', ')}`);
  },

  8: async () => {
    const o = await createCancelledOrder(8, 'ack');
    const c = await pollCase(8, o.orderId, o.cancelAt, CASE_OPEN_MAX_MS + 5000);
    const ack = (key, choice = 'STOPPED') =>
      http('POST', `/api/cases/${c.caseId}/ack`, { headers: tablet, body: { choice, idempotencyKey: key, caseVersionSeen: c.caseVersion } });
    const key = crypto.randomUUID();
    const t = Date.now();
    const a1 = await ack(key);
    record(8, 'ACK request latency', Date.now() - t);
    if (Date.now() - o.cancelAt > 5000 + CASE_OPEN_MAX_MS) fail(8, 'ACK within 5 s of case visible', 'too slow');
    if (a1.status !== 200 || a1.json?.state !== 'ACKNOWLEDGED' || a1.json?.channel !== 'TABLET' || a1.json?.duplicate !== false)
      fail(8, 'first ACK -> 200 ACKNOWLEDGED channel=TABLET duplicate=false', `${a1.status} ${a1.text.slice(0, 200)}`);
    const a2 = await ack(key);
    if (a2.status !== 200 || a2.json?.duplicate !== true) fail(8, 'same idempotencyKey -> 200 duplicate=true', `${a2.status} ${a2.text.slice(0, 200)}`);
    const a3 = await ack(crypto.randomUUID());
    if (a3.status !== 409 || a3.json?.error !== 'ALREADY_ACKNOWLEDGED') fail(8, 'different key -> 409 ALREADY_ACKNOWLEDGED', `${a3.status} ${a3.text.slice(0, 200)}`);
    log('step 8: ACK 200, replay duplicate=true, new key 409 ALREADY_ACKNOWLEDGED');
  },

  9: async () => {
    const q = async (expr) => {
      const r = await http('GET', `/api/v1/query?query=${encodeURIComponent(expr)}`, { base: PROM });
      if (r.status !== 200) fail(9, `Prometheus query ${expr} -> 200`, `${r.status} ${r.text.slice(0, 200)}`);
      return r.json?.data?.result ?? [];
    };
    const want = ['nocook_case_open_seconds_count', 'nocook_card_delivery_seconds_count'];
    // Prometheus scrapes/OTLP-exports on an interval; wait up to 45 s for both series.
    const deadline = Date.now() + 45_000;
    let vals = {};
    for (;;) {
      for (const m of want) {
        const res = await q(`sum(${m})`);
        vals[m] = res.length ? Number(res[0].value[1]) : 0;
      }
      if (want.every((m) => vals[m] > 0)) break;
      if (Date.now() > deadline) fail(9, `${want.join(' and ')} > 0 in Prometheus`, JSON.stringify(vals));
      await sleep(3000);
    }
    log(`step 9: ${want.map((m) => `${m}=${vals[m]}`).join(', ')}`);
  },
};

// ---------- main ----------
const order = [...enabled].sort((a, b) => a - b);
const needMqtt = order.some((s) => s === 3 || (s >= 4 && s <= 6));
let exit = 0;
try {
  log(`smoke start: BASE=${BASE} MQTT=${MQTT_URL} PROM=${PROM} steps=${order.join(',')}`);
  if (needMqtt) await mqttConnect(); // before the cancel so no card is missed (QoS1, non-retained)
  for (const s of order) {
    const t = Date.now();
    log(`--- step ${s}`);
    await steps[s]();
    log(`step ${s} OK (${Date.now() - t} ms)`);
  }
  const partial = order.length < 9 ? ' (PARTIAL: steps ' + order.join(',') + ')' : '';
  console.log(`\nPASS${partial} in ${((Date.now() - T0) / 1000).toFixed(1)} s`);
  for (const t of timings) console.log(`  step ${t.step}: ${t.label.padEnd(44)} ${String(Math.round(t.ms)).padStart(6)} ms`);
} catch (e) {
  exit = 1;
  console.error(`\n${e instanceof StepFail ? e.message : 'FAIL (unexpected): ' + (e?.stack ?? e)}`);
  console.error(`after ${((Date.now() - T0) / 1000).toFixed(1)} s; timings so far: ${timings.map((t) => `s${t.step}=${Math.round(t.ms)}ms`).join(' ') || 'none'}`);
} finally {
  if (client) await new Promise((r) => client.end(true, {}, r));
}
process.exitCode = exit;
setTimeout(() => process.exit(exit), 1500).unref();

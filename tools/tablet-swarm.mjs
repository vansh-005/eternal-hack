// Q-06 — tablet swarm (LOAD_AND_CHAOS.md §3). One MQTT connection, simulates tablets for outlet-load-*.
// Does NOT answer calls: the mock gateway auto-plays outcomes for those outlets.
import http from 'node:http';
import { randomUUID } from 'node:crypto';
import mqtt from 'mqtt';

const MQTT_URL = process.env.MQTT_URL || 'mqtt://localhost:1883';
const API = new URL(process.env.API_URL || 'http://localhost:8080');
const PREFIX = process.env.OUTLET_PREFIX || 'outlet-load-';

const agent = new http.Agent({ keepAlive: true, maxSockets: 256 });
const rnd = (lo, hi) => lo + Math.random() * (hi - lo);
const hash = (s) => { let h = 2166136261; for (let i = 0; i < s.length; i++) { h ^= s.charCodeAt(i); h = Math.imul(h, 16777619); } return h >>> 0; };
const pickChoice = () => { const r = Math.random(); return r < 0.7 ? 'STOPPED' : r < 0.9 ? 'NOT_STARTED' : 'ALREADY_COOKED'; };

let cards = 0, acks = 0, ackFail = 0, httpErr = 0, inflight = 0, maxInflight = 0;
let lastErr = '';
let handling = []; // ms, per 10 s window
const timers = new Map(); // caseId -> Timeout (pending ACK)

function post(path, o, body) {
  const data = JSON.stringify(body);
  return new Promise((resolve) => {
    inflight++; if (inflight > maxInflight) maxInflight = inflight;
    const req = http.request({
      agent, hostname: API.hostname, port: API.port || 80, path, method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(data), 'X-Outlet-Id': o.outletId, 'X-Device-Id': `swarm-${o.no}` },
    }, (res) => { res.resume(); res.on('end', () => { inflight--; resolve(res.statusCode); }); });
    req.on('error', (e) => { inflight--; httpErr++; lastErr = e.code || e.message; resolve(0); });
    req.end(data);
  });
}

const receipt = (o, kind, card) => post(`/api/cases/${card.caseId}/receipts`, o,
  { kind, deviceId: `swarm-${o.no}`, messageId: card.messageId, deviceAt: new Date().toISOString() });

function scheduleAck(o, card) {
  const b = hash(card.caseId) % 100;
  if (b >= 80) return; // never: case goes to IVR
  const [lo, hi] = b < 60 ? [5000, 25000] : [32000, 60000];
  const at = Date.parse(card.openedAt) + rnd(lo, hi);
  const t = setTimeout(async () => {
    timers.delete(card.caseId);
    const st = await post(`/api/cases/${card.caseId}/ack`, o,
      { choice: pickChoice(), idempotencyKey: randomUUID(), caseVersionSeen: card.caseVersion });
    if (st === 200) acks++; else ackFail++;
  }, Math.max(0, at - Date.now()));
  timers.set(card.caseId, t);
}

async function onCard(outletId, card) {
  const t0 = performance.now();
  const o = { outletId, no: parseInt(outletId.slice(PREFIX.length), 10) || 0 };
  cards++;
  if (card.kind === 'OPEN') {
    scheduleAck(o, card);
    await receipt(o, 'DELIVERED', card);
    handling.push(performance.now() - t0);
    setTimeout(() => receipt(o, 'RENDERED', card), rnd(16, 50));
  } else if (card.kind === 'CLOSE') {
    const t = timers.get(card.caseId);
    if (t) { clearTimeout(t); timers.delete(card.caseId); }
    await receipt(o, 'DISMISSED', card);
    handling.push(performance.now() - t0);
  }
}

const client = mqtt.connect(MQTT_URL, { protocolVersion: 5, clientId: `swarm-${randomUUID().slice(0, 8)}`, clean: true, reconnectPeriod: 1000 });
client.on('connect', () => {
  console.log(`[swarm] connected ${MQTT_URL}`);
  client.subscribe(['nocook/outlets/+/cards', 'nocook/outlets/+/calls'], { qos: 1 }, (err) => err && console.error('[swarm] subscribe', err));
});
client.on('error', (e) => console.error('[swarm] mqtt error', e.message));
client.on('message', (topic, payload) => {
  const parts = topic.split('/');
  const outletId = parts[2];
  if (!outletId || !outletId.startsWith(PREFIX)) return;
  if (parts[3] !== 'cards') return; // calls are answered by the mock gateway, never by the swarm
  let card; try { card = JSON.parse(payload.toString()); } catch { return; }
  onCard(outletId, card).catch((e) => { httpErr++; console.error('[swarm] handler', e.message); });
});

let last = Date.now(), lastCards = 0, lastAcks = 0;
setInterval(() => {
  const now = Date.now(), dt = (now - last) / 1000;
  const h = handling.sort((a, b) => a - b); handling = [];
  const p99 = h.length ? h[Math.min(h.length - 1, Math.floor(h.length * 0.99))] : 0;
  console.log(`[swarm] cards/s=${((cards - lastCards) / dt).toFixed(1)} acks/s=${((acks - lastAcks) / dt).toFixed(1)} ackFail=${ackFail} httpErr=${httpErr}${lastErr ? `(${lastErr})` : ''} pendingAcks=${timers.size} inflightMax=${maxInflight} p99handling=${p99.toFixed(1)}ms`);
  if (p99 > 200) console.warn(`[swarm] WARN lag: p99 handling ${p99.toFixed(0)}ms > 200ms (swarm is behind; card.delivery{client=swarm} inflated)`);
  last = now; lastCards = cards; lastAcks = acks; maxInflight = inflight;
}, 10000);

#!/usr/bin/env node
// Q-07: make demo-reset. DATA_MODEL.md section 5: truncate cases/outbox/orders as postgres, purge both
// queues through the RabbitMQ management API, reset chaos. results/ is kept.
import { spawnSync } from 'node:child_process';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import pg from 'pg';

const BASE = (process.env.BASE_URL ?? 'http://localhost:8080').replace(/\/$/, '');
const RABBIT = (process.env.RABBIT_URL ?? 'http://localhost:15672').replace(/\/$/, '');
const auth = { authorization: `Basic ${Buffer.from('nocook:nocook').toString('base64')}` };

// stop the firehose first so nothing refills the queues while we purge
await fetch(`${BASE}/oms/api/firehose`, { method: 'DELETE' }).then(() => console.log('firehose stopped')).catch((e) => console.warn(`firehose stop skipped: ${e.message}`));
await fetch(`${BASE}/api/ops/chaos/reset`, { method: 'POST', headers: { 'content-type': 'application/json' }, body: '{}' })
  .then((r) => console.log(`chaos reset: ${r.status}`)).catch((e) => console.warn(`chaos reset skipped: ${e.message}`));

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
await client.query(`truncate nocook.case_events, nocook.deadlines, nocook.outbox, nocook.call_attempts,
  nocook.webhook_events, nocook.ops_events, nocook.cases`);
await client.query('truncate oms.outbox, oms.orders');
await client.end();
console.log('truncated nocook.* and oms.outbox/orders');

for (const queue of ['nocook.cancellations', 'nocook.cancellations.dlq']) {
  const res = await fetch(`${RABBIT}/api/queues/%2F/${queue}/contents`, { method: 'DELETE', headers: auth });
  console.log(`purge ${queue}: ${res.status}`);
}
await new Promise((r) => setTimeout(r, 6000)); // management stats lag ~5 s
for (const queue of ['nocook.cancellations', 'nocook.cancellations.dlq']) {
  const q = await (await fetch(`${RABBIT}/api/queues/%2F/${queue}`, { headers: auth })).json();
  console.log(`${queue}: ready=${q.messages_ready} unacked=${q.messages_unacknowledged}`);
}
console.log('demo-reset done (results/ kept)');

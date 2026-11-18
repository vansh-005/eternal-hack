# frontend/CLAUDE.md

Loaded when you work under `frontend/`. The root `CLAUDE.md` still applies.

Read `docs/FRONTEND.md` before editing. Payload shapes come from `docs/API.md`; copy them into `src/api/types.ts` exactly, do not reshape.

## Rules

- All HTTP goes through `src/api/client.ts` (relative URLs: `/api/...`, `/oms/api/...`). Never hard-code `localhost`.
- MQTT goes through `src/mqtt/useMqtt.ts` only. Broker URL: `${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/mqtt`.
- Grafana base URL: `import.meta.env.VITE_GRAFANA_URL ?? \`http://${location.hostname}:3000\``.
- Every number rendered for judges uses `<ProofBadge kind="MEASURED|TESTED|SIMULATED" n={...} runId={...} />`.
- Timers on screen (countdowns) derive from server timestamps in the payload, never from when the message arrived.
- Tablet must work at 1024×768 and on a phone (judges may open it on their own phone).

## Commands

```bash
cd frontend
npm ci
npm run dev        # Vite on 5173, proxies /api, /oms, /mqtt to localhost:8080
npm run test       # vitest
npm run build      # used by the Docker image
npm run lint
```

# INFRA.md — Docker Compose, configs, Makefile (owner Q)

Target machine: 16 GB RAM, 8 cores. Everything runs locally; no network needed after `make prepull`.

## 1. Files

```
infra/
├── docker-compose.yml
├── .env                      pinned image tags (see §2) + secrets for local use
├── postgres/init.sql         roles + schemas (DATA_MODEL.md §0)
├── rabbitmq/rabbitmq.conf
├── mosquitto/mosquitto.conf
├── toxiproxy/toxiproxy.json
└── grafana/dashboards/       live-slos.json, reliability.json, load.json  (provisioned)
```

## 2. `infra/.env` (pin at H0, then never change during the event)

```dotenv
POSTGRES_IMAGE=postgres:18
RABBITMQ_IMAGE=rabbitmq:4.3-management
MOSQUITTO_IMAGE=eclipse-mosquitto:2
TOXIPROXY_IMAGE=ghcr.io/shopify/toxiproxy:<pin>
LGTM_IMAGE=grafana/otel-lgtm:<pin>
K6_IMAGE=grafana/k6:<pin>
NODE_IMAGE=node:24-alpine
MOCK_TELEPHONY_SECRET=dev-secret-change-me
INTERNAL_TOKEN=dev-internal-change-me
TRACING_SAMPLE=1.0
```

## 3. `infra/docker-compose.yml`

```yaml
name: nocook

x-nocook: &nocook
  build: { context: ../backend, dockerfile: Dockerfile, args: { MODULE: nocook-service } }
  image: nocook-service:local
  environment: &nocook-env
    NOCOOK_DB_URL: jdbc:postgresql://toxiproxy:15432/nocook
    OMS_BASE_URL: http://toxiproxy:18090
    RABBIT_HOST: rabbitmq
    MQTT_HOST: mosquitto
    OTLP_HTTP: http://lgtm:4318
    TOXIPROXY_URL: http://toxiproxy:8474
    GRAFANA_URL: http://lgtm:3000
    MOCK_TELEPHONY_SECRET: ${MOCK_TELEPHONY_SECRET}
    INTERNAL_TOKEN: ${INTERNAL_TOKEN}
    TRACING_SAMPLE: ${TRACING_SAMPLE:-1.0}
  volumes: [ "../results:/results:ro" ]
  depends_on:
    postgres: { condition: service_healthy }
    rabbitmq: { condition: service_healthy }
    mosquitto: { condition: service_started }
    toxiproxy: { condition: service_started }
    mock-oms: { condition: service_healthy }
    lgtm: { condition: service_started }
  healthcheck:
    test: ["CMD", "curl", "-fs", "http://localhost:8080/actuator/health"]
    interval: 5s
    timeout: 2s
    retries: 30
  restart: "no"            # a killed replica stays dead for the demo; `make up-replica R=2` brings it back

services:
  postgres:
    image: ${POSTGRES_IMAGE}
    environment: { POSTGRES_DB: nocook, POSTGRES_PASSWORD: postgres }
    ports: ["5432:5432"]
    volumes:
      - ./postgres/init.sql:/docker-entrypoint-initdb.d/01-init.sql:ro
      - pgdata:/var/lib/postgresql
    command: ["postgres", "-c", "max_connections=200", "-c", "shared_buffers=256MB"]
    healthcheck: { test: ["CMD-SHELL", "pg_isready -U postgres -d nocook"], interval: 3s, retries: 30 }

  rabbitmq:
    image: ${RABBITMQ_IMAGE}
    environment: { RABBITMQ_DEFAULT_USER: nocook, RABBITMQ_DEFAULT_PASS: nocook }
    ports: ["5672:5672", "15672:15672"]
    volumes: [ "./rabbitmq/rabbitmq.conf:/etc/rabbitmq/conf.d/90-nocook.conf:ro", "rabbitdata:/var/lib/rabbitmq" ]
    healthcheck: { test: ["CMD", "rabbitmq-diagnostics", "-q", "ping"], interval: 5s, retries: 30 }

  mosquitto:
    image: ${MOSQUITTO_IMAGE}
    ports: ["1883:1883", "9001:9001"]
    volumes: [ "./mosquitto/mosquitto.conf:/mosquitto/config/mosquitto.conf:ro" ]

  toxiproxy:
    image: ${TOXIPROXY_IMAGE}
    command: ["-host=0.0.0.0", "-config=/config/toxiproxy.json"]
    ports: ["8474:8474"]
    volumes: [ "./toxiproxy/toxiproxy.json:/config/toxiproxy.json:ro" ]

  lgtm:
    image: ${LGTM_IMAGE}
    ports: ["3000:3000", "4317:4317", "4318:4318", "9090:9090", "3200:3200"]
    environment:
      GF_SECURITY_ALLOW_EMBEDDING: "true"
      GF_AUTH_ANONYMOUS_ENABLED: "true"
      GF_AUTH_ANONYMOUS_ORG_ROLE: "Viewer"
      GF_DASHBOARDS_DEFAULT_HOME_DASHBOARD_PATH: /otel-lgtm/grafana/conf/provisioning/dashboards/custom/live-slos.json
    volumes:
      - ./grafana/dashboards:/otel-lgtm/grafana/conf/provisioning/dashboards/custom:ro
      - lgtmdata:/data

  mock-oms:
    build: { context: ../backend, dockerfile: Dockerfile, args: { MODULE: mock-oms } }
    image: mock-oms:local
    environment: { RABBIT_HOST: rabbitmq, OTLP_HTTP: "http://lgtm:4318", TRACING_SAMPLE: "${TRACING_SAMPLE:-1.0}" }
    ports: ["8090:8090"]
    depends_on:
      postgres: { condition: service_healthy }
      rabbitmq: { condition: service_healthy }
    healthcheck: { test: ["CMD", "curl", "-fs", "http://localhost:8090/actuator/health"], interval: 5s, retries: 30 }

  nocook-1: { <<: *nocook, environment: { <<: *nocook-env, REPLICA_ID: nocook-1 }, ports: ["8081:8080"] }
  nocook-2: { <<: *nocook, environment: { <<: *nocook-env, REPLICA_ID: nocook-2 }, ports: ["8082:8080"] }
  nocook-3: { <<: *nocook, environment: { <<: *nocook-env, REPLICA_ID: nocook-3 }, ports: ["8083:8080"] }

  web:
    build: { context: ../frontend }
    image: nocook-web:local
    ports: ["8080:80"]
    depends_on:
      nocook-1: { condition: service_healthy }
      nocook-2: { condition: service_healthy }
      nocook-3: { condition: service_healthy }
      mock-oms: { condition: service_healthy }
      mosquitto: { condition: service_started }

  k6:
    image: ${K6_IMAGE}
    profiles: ["load"]
    environment:
      K6_OTEL_EXPORTER_PROTOCOL: grpc
      K6_OTEL_GRPC_EXPORTER_ENDPOINT: lgtm:4317
      K6_OTEL_GRPC_EXPORTER_INSECURE: "true"
      K6_OTEL_METRIC_PREFIX: k6_
      K6_OTEL_SERVICE_NAME: k6
      BASE_URL: http://web
    volumes: [ "../load:/scripts:ro", "../results:/results" ]
    entrypoint: ["k6"]

  swarm:
    image: ${NODE_IMAGE}
    profiles: ["load"]
    working_dir: /tools
    command: ["node", "tablet-swarm.mjs"]
    environment: { MQTT_URL: "mqtt://mosquitto:1883", API_URL: "http://web", OUTLET_PREFIX: "outlet-load-", OUTLETS: "500" }
    volumes: [ "../tools:/tools" ]

volumes: { pgdata: {}, rabbitdata: {}, lgtmdata: {} }
```

`tools/` has its own `package.json` (deps: `mqtt`); `make up` runs `npm ci --prefix tools` once.

Postgres 18 images store data under `/var/lib/postgresql/<major>/…`; mounting `/var/lib/postgresql` is the 18+ convention. If the container refuses to start because of the volume layout, mount `/var/lib/postgresql/data` instead and note it.

## 4. Config files

`infra/rabbitmq/rabbitmq.conf`

```ini
# RabbitMQ sizes its memory alarm from host RAM inside containers; pin it.
vm_memory_high_watermark.absolute = 1GiB
disk_free_limit.absolute = 1GB
```

`infra/mosquitto/mosquitto.conf`

```conf
listener 1883
protocol mqtt
listener 9001
protocol websockets
allow_anonymous true
persistence false
max_queued_messages 10000
log_type error
log_type warning
```

Mosquitto 2 rejects anonymous clients once any listener is defined unless `allow_anonymous true` is set.

`infra/toxiproxy/toxiproxy.json`

```json
[
  { "name": "postgres", "listen": "0.0.0.0:15432", "upstream": "postgres:5432", "enabled": true },
  { "name": "oms",      "listen": "0.0.0.0:18090", "upstream": "mock-oms:8090", "enabled": true }
]
```

Toxic API used by `ChaosController`:

```bash
# +300 ms on DB responses
curl -s -X POST localhost:8474/proxies/postgres/toxics -d '{"name":"db_latency","type":"latency","stream":"downstream","attributes":{"latency":300,"jitter":20}}'
curl -s -X DELETE localhost:8474/proxies/postgres/toxics/db_latency
# OMS down / up
curl -s -X POST localhost:8474/proxies/oms -d '{"enabled":false}'
curl -s -X POST localhost:8474/proxies/oms -d '{"enabled":true}'
# reset everything
curl -s -X POST localhost:8474/reset
```

## 5. Makefile (repo root)

| Target | Command(s) |
|---|---|
| `prepull` | `docker compose -f infra/docker-compose.yml --profile load pull` + build images |
| `up` | `npm ci --prefix tools` (once) → `docker compose -f infra/docker-compose.yml up -d --build` → wait until `web` healthy → print URLs |
| `down` / `nuke` | `docker compose … down` / `down -v` |
| `ps`, `logs S=…` | passthrough |
| `up-replica R=2` | `docker compose … up -d nocook-$(R)` |
| `test` | `cd backend && ./mvnw -q verify` && `cd frontend && npm run test -- --run` |
| `test-domain` | `cd backend && ./mvnw -q -pl nocook-domain test` |
| `smoke` | `node tools/smoke.mjs` (TESTING.md §5) |
| `metric-names` | `node tools/metric-names.mjs > docs/METRIC_NAMES.lock.md` |
| `run P=P3 [REPLICAS=3] [NOTE="…"]` | `node tools/run.mjs --profile $(P)` (LOAD_AND_CHAOS.md §5) |
| `export RUN=…` | `node tools/export-run.mjs --run $(RUN)` |
| `sim` | `cd backend && ./mvnw -q -pl nocook-sim -am package -DskipTests && java -jar nocook-sim/target/nocook-sim.jar --seed 2026 --n 10000 --out ../results/sim-$$(date +%Y%m%d-%H%M)` |
| `screenshots RUN=…` | `node tools/screenshots.mjs --run $(RUN)` (Playwright; optional) |
| `demo-reset` | `node tools/demo-reset.mjs` (SQL from DATA_MODEL.md §5 + queue purge + chaos reset) |
| `scale1` | stop `nocook-2` and `nocook-3` for the 1-replica scale-out baseline |

## 6. Memory budget (approximate, check with `docker stats` at G0)

| Container | Limit / expected |
|---|---|
| nocook ×3 | 512 MB heap each, ~700 MB RSS each |
| mock-oms | 512 MB heap |
| postgres | ~400 MB |
| rabbitmq | ≤ 1 GB (watermark) |
| lgtm | 1–1.5 GB |
| k6 + swarm | ~300 MB |
| web, mosquitto, toxiproxy | < 100 MB total |

Total ≈ 6–7 GB. On an 8 GB laptop: run one nocook replica (`make scale1`), skip P4–P6.

## 7. Gotchas

- Run `docker compose` from the repo root with `-f infra/docker-compose.yml`, or `cd infra`; build contexts are relative to `infra/`.
- `web` must start after the three replicas (nginx resolves upstream names at boot).
- First `make up` builds Maven inside Docker (≈3–5 min). The `--mount=type=cache` keeps `.m2` between builds.
- Grafana anonymous users are Viewers; chaos annotations use admin basic auth from inside the Docker network.
- Venue Wi-Fi: nothing needs the internet after `make prepull`.

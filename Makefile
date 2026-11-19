SHELL := bash
.DEFAULT_GOAL := help

DC := docker compose -f infra/docker-compose.yml

# Force JDK 25 for ./mvnw when the Windows Temurin 25 install exists (a machine-wide JAVA_HOME may point at an older JDK).
# 8.3 short path: make's $(wildcard) cannot handle spaces.
JDK25 := C:/Progra~1/ECLIPS~1/jdk-25.0.5.8-hotspot
ifneq ($(wildcard $(JDK25)/bin/java.exe),)
  export JAVA_HOME := $(JDK25)
  export PATH := $(JDK25)/bin:$(PATH)
endif

.PHONY: help prepull up down nuke ps logs up-replica test test-domain smoke metric-names run export sim screenshots demo-reset scale1

help:
	@echo "targets: prepull up down nuke ps logs up-replica test test-domain smoke metric-names run export sim screenshots demo-reset scale1"

prepull:
	$(DC) --profile load pull --ignore-buildable
	$(DC) build

up:
	@test -d tools/node_modules || (test -f tools/package-lock.json && npm ci --prefix tools || npm install --prefix tools)
	$(DC) up -d --build
	@echo "waiting for web to be healthy..."
	@for i in $$(seq 1 120); do \
	  s=$$($(DC) ps --format '{{.Health}}' web 2>/dev/null | head -n1); \
	  if [ "$$s" = "healthy" ]; then break; fi; sleep 3; done
	@echo "App:        http://localhost:8080"
	@echo "Grafana:    http://localhost:3000 (admin/admin)"
	@echo "RabbitMQ:   http://localhost:15672 (nocook/nocook)"
	@echo "Prometheus: http://localhost:9090"

down:
	$(DC) down

nuke:
	$(DC) down -v

ps:
	$(DC) ps

logs:
	$(DC) logs -f --tail=200 $(S)

up-replica:
	$(DC) up -d nocook-$(R)

test:
	cd backend && ./mvnw -q verify
	cd frontend && npm run test -- --run

test-domain:
	cd backend && ./mvnw -q -pl nocook-domain test

smoke:
	node tools/smoke.mjs

metric-names:
	@node tools/metric-names.mjs > docs/METRIC_NAMES.lock.md

run:
	node tools/run.mjs --profile $(P) $(if $(FAULT),--fault $(FAULT)) $(if $(REPLICAS),--replicas $(REPLICAS)) $(if $(NOTE),--note "$(NOTE)")

export:
	node tools/export-run.mjs --run $(RUN)

demo-reset:
	node tools/demo-reset.mjs

sim:
	cd backend && ./mvnw -q -pl nocook-sim -am package -DskipTests && java -jar nocook-sim/target/nocook-sim.jar --seed 2026 --n 10000 --out ../results/sim-$$(date +%Y%m%d-%H%M)

screenshots:
	@echo "not yet implemented: screenshots (Q-09)"

scale1:
	$(DC) stop nocook-2 nocook-3

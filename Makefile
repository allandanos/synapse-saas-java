.PHONY: build test test-unit test-pg run run-pg run-rls worker jobs-run-once plans-sync conformance conformance-all
REF ?= ../synapse-saas
# The reference's scratch Postgres (docker compose --profile test) with this port's own database
# IPv6 loopback: an ssh tunnel may own the IPv4 listener on 5434
PG_TEST_URL ?= jdbc:postgresql://[::1]:5434/synapse_java
ADMIN_EMAIL ?= operator@platform.example.com
ADMIN_PASSWORD ?= operator-password-12345
# Local-disk file storage for the conformance run (gitignored)
STORAGE_ROOT ?= .storage

build: ## Compile + package
	mvn -q -B -DskipTests package

test: ## Everything: unit tests + @SpringBootTest journeys (Testcontainers unless SYNAPSE_TEST_JDBC_URL is set)
	mvn -q -B test

test-unit: ## Pure-logic tests only (no database, no Docker)
	mvn -q -B test -Dtest='!*JourneyTest,!*IT' -Dsurefire.failIfNoSpecifiedTests=false

test-pg: ## @SpringBootTest journeys against an existing scratch database instead of Testcontainers
	SYNAPSE_TEST_JDBC_URL=$(PG_TEST_URL) mvn -q -B test

run: ## Boot against the reference dev stack's Postgres (:5433)
	SYNAPSE_JDBC_URL=jdbc:postgresql://localhost:5433/synapse mvn -q -B spring-boot:run

run-pg: ## Boot on :8080 against the scratch database with a bootstrapped platform operator (what `make conformance` expects)
	SYNAPSE_JDBC_URL=$(PG_TEST_URL) SYNAPSE_BOOTSTRAP_ADMIN_EMAIL=$(ADMIN_EMAIL) SYNAPSE_BOOTSTRAP_ADMIN_PASSWORD=$(ADMIN_PASSWORD) \
		SYNAPSE_STORAGE_ROOT=$(STORAGE_ROOT) mvn -q -B spring-boot:run

run-rls: ## Boot on :8080 as the RLS-subject role with SYNAPSE_TENANT_ISOLATION=app_and_rls (see README: provision synapse_app first)
	SYNAPSE_JDBC_URL=$(PG_TEST_URL) SYNAPSE_DB_USER=synapse_app SYNAPSE_DB_PASSWORD=synapse_app SYNAPSE_TENANT_ISOLATION=app_and_rls \
		SYNAPSE_BOOTSTRAP_ADMIN_EMAIL=$(ADMIN_EMAIL) SYNAPSE_BOOTSTRAP_ADMIN_PASSWORD=$(ADMIN_PASSWORD) \
		mvn -q -B spring-boot:run

worker: ## The standalone worker: the cron jobs, no web server (the reference's arq worker)
	SYNAPSE_JDBC_URL=$(PG_TEST_URL) java -jar target/synapse-saas-0.1.0-SNAPSHOT.jar --worker

jobs-run-once: ## Run every worker job once and exit, printing `name: count` (the reference's `synapse-cli jobs run-once --all`)
	SYNAPSE_JDBC_URL=$(PG_TEST_URL) java -jar target/synapse-saas-0.1.0-SNAPSHOT.jar --jobs-run-once $(or $(JOBS),--all)

plans-sync: ## Sync plans.yaml into the scratch database once and exit (the reference's `synapse-cli plans sync`)
	SYNAPSE_JDBC_URL=$(PG_TEST_URL) java -jar target/synapse-saas-0.1.0-SNAPSHOT.jar --plans-sync --server.port=0

conformance: ## The reference repo's whole black-box suite against this port on :8080 — every module, no exclusions
	cd $(REF) && SYNAPSE_CONFORMANCE_API_URL=http://localhost:8080 \
		SYNAPSE_CONFORMANCE_ADMIN_EMAIL=$(ADMIN_EMAIL) SYNAPSE_CONFORMANCE_ADMIN_PASSWORD=$(ADMIN_PASSWORD) \
		uv run pytest tests/conformance -m "" --no-cov -q -p no:cacheprovider

conformance-all: conformance ## Alias kept for older notes; `conformance` is already the whole suite

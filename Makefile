.PHONY: build test test-unit test-pg run run-pg run-rls run-openfga worker jobs-run-once plans-sync plans-sync-stripe seed-dev \
	e2e e2e-sso authz-fga-write-model authz-fga-sync authz-fga-check conformance conformance-all
REF ?= ../synapse-saas
# The reference's scratch Postgres (docker compose --profile test) with this port's own database
# IPv6 loopback: an ssh tunnel may own the IPv4 listener on 5434
PG_TEST_URL ?= jdbc:postgresql://[::1]:5434/synapse_java
ADMIN_EMAIL ?= operator@platform.example.com
ADMIN_PASSWORD ?= operator-password-12345
# Local-disk file storage for the conformance run (gitignored)
STORAGE_ROOT ?= .storage
# This port's Redis (versioned caches, auth rate limits, OIDC login state)
REDIS_URL ?= redis://localhost:6390/0
# The shared local OpenFGA; every run creates its own store
OPENFGA_URL ?= http://localhost:8081
# The conformance suite registers many users from one address
RATE_LIMITS = SYNAPSE_AUTH_RATE_LIMIT_PER_IP=1000 SYNAPSE_AUTH_RATE_LIMIT_PER_IDENTITY=100

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
		SYNAPSE_STORAGE_ROOT=$(STORAGE_ROOT) SYNAPSE_REDIS_URL=$(REDIS_URL) $(RATE_LIMITS) mvn -q -B spring-boot:run

run-openfga: ## Same, with permission checks answered by OpenFGA (set STORE_ID/MODEL_ID from `make authz-fga-write-model`)
	SYNAPSE_JDBC_URL=$(PG_TEST_URL) SYNAPSE_BOOTSTRAP_ADMIN_EMAIL=$(ADMIN_EMAIL) SYNAPSE_BOOTSTRAP_ADMIN_PASSWORD=$(ADMIN_PASSWORD) \
		SYNAPSE_STORAGE_ROOT=$(STORAGE_ROOT) SYNAPSE_REDIS_URL=$(REDIS_URL) $(RATE_LIMITS) \
		SYNAPSE_AUTHZ_BACKEND=openfga SYNAPSE_OPENFGA_URL=$(OPENFGA_URL) \
		SYNAPSE_OPENFGA_STORE_ID=$(STORE_ID) SYNAPSE_OPENFGA_MODEL_ID=$(MODEL_ID) mvn -q -B spring-boot:run

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

plans-sync-stripe: ## Also push the catalog to Stripe (dry run unless APPLY=--apply), recording plans.provider_refs
	SYNAPSE_JDBC_URL=$(PG_TEST_URL) java -jar target/synapse-saas-0.1.0-SNAPSHOT.jar --plans-sync --stripe $(APPLY) --server.port=0

authz-fga-write-model: ## Write the catalog-generated OpenFGA model (STORE=<name> creates a store first, DSL=--dsl prints it)
	SYNAPSE_JDBC_URL=$(PG_TEST_URL) SYNAPSE_OPENFGA_URL=$(OPENFGA_URL) SYNAPSE_OPENFGA_STORE_ID=$(STORE_ID) \
		java -jar target/synapse-saas-0.1.0-SNAPSHOT.jar --authz-fga-write-model $(if $(STORE),--create-store=$(STORE),) $(DSL)

authz-fga-sync: ## Converge OpenFGA tuples to the RBAC state (ORG=<id> for one organization, else every membership)
	SYNAPSE_JDBC_URL=$(PG_TEST_URL) SYNAPSE_OPENFGA_URL=$(OPENFGA_URL) SYNAPSE_OPENFGA_STORE_ID=$(STORE_ID) \
		SYNAPSE_OPENFGA_MODEL_ID=$(MODEL_ID) SYNAPSE_AUTHZ_BACKEND=openfga \
		java -jar target/synapse-saas-0.1.0-SNAPSHOT.jar --authz-fga-sync $(if $(ORG),--org=$(ORG),--all)

authz-fga-check: ## Ask the store: may USER exercise PERMISSION in ORG? (CHECK=<user>,<org>,<permission>)
	SYNAPSE_JDBC_URL=$(PG_TEST_URL) SYNAPSE_OPENFGA_URL=$(OPENFGA_URL) SYNAPSE_OPENFGA_STORE_ID=$(STORE_ID) \
		SYNAPSE_OPENFGA_MODEL_ID=$(MODEL_ID) \
		java -jar target/synapse-saas-0.1.0-SNAPSHOT.jar --authz-fga-check=$(CHECK)

seed-dev: ## The demo org + one user per system role (the reference's `synapse-cli seed --dev`); refused in production
	SYNAPSE_JDBC_URL=$(PG_TEST_URL) java -jar target/synapse-saas-0.1.0-SNAPSHOT.jar --seed-dev

e2e: ## The REFERENCE console's Playwright journeys against this port (copies apps/web, boots MailHog + a seeded server, tears down)
	./scripts/e2e-console.sh

e2e-sso: ## The same journeys plus sso.spec.ts against a real Keycloak (23 passed, 0 skipped)
	KEYCLOAK=1 ./scripts/e2e-console.sh

conformance: ## The reference repo's whole black-box suite against this port on :8080 — every module, no exclusions
	cd $(REF) && SYNAPSE_CONFORMANCE_API_URL=http://localhost:8080 \
		SYNAPSE_CONFORMANCE_ADMIN_EMAIL=$(ADMIN_EMAIL) SYNAPSE_CONFORMANCE_ADMIN_PASSWORD=$(ADMIN_PASSWORD) \
		uv run pytest tests/conformance -m "" --no-cov -q -p no:cacheprovider

conformance-all: conformance ## Alias kept for older notes; `conformance` is already the whole suite

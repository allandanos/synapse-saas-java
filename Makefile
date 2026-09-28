.PHONY: build test test-unit test-pg run run-pg run-rls conformance conformance-all
REF ?= ../synapse-saas
# The reference's scratch Postgres (docker compose --profile test) with this port's own database
PG_TEST_URL ?= jdbc:postgresql://localhost:5434/synapse_java
ADMIN_EMAIL ?= operator@platform.example.com
ADMIN_PASSWORD ?= operator-password-12345

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
		mvn -q -B spring-boot:run

run-rls: ## Boot on :8080 as the RLS-subject role with SYNAPSE_TENANT_ISOLATION=app_and_rls (see README: provision synapse_app first)
	SYNAPSE_JDBC_URL=$(PG_TEST_URL) SYNAPSE_DB_USER=synapse_app SYNAPSE_DB_PASSWORD=synapse_app SYNAPSE_TENANT_ISOLATION=app_and_rls \
		SYNAPSE_BOOTSTRAP_ADMIN_EMAIL=$(ADMIN_EMAIL) SYNAPSE_BOOTSTRAP_ADMIN_PASSWORD=$(ADMIN_PASSWORD) \
		mvn -q -B spring-boot:run

conformance: ## The reference repo's black-box suite against this port on :8080 (milestone 2 modules)
	cd $(REF) && SYNAPSE_CONFORMANCE_API_URL=http://localhost:8080 \
		SYNAPSE_CONFORMANCE_ADMIN_EMAIL=$(ADMIN_EMAIL) SYNAPSE_CONFORMANCE_ADMIN_PASSWORD=$(ADMIN_PASSWORD) \
		uv run pytest tests/conformance/test_meta_and_health.py tests/conformance/test_auth.py tests/conformance/test_tenancy.py \
		tests/conformance/test_authorization.py tests/conformance/test_api_keys.py -m "" --no-cov -q -p no:cacheprovider

conformance-all: ## Every conformance module (later milestones will fail until implemented)
	cd $(REF) && SYNAPSE_CONFORMANCE_API_URL=http://localhost:8080 \
		SYNAPSE_CONFORMANCE_ADMIN_EMAIL=$(ADMIN_EMAIL) SYNAPSE_CONFORMANCE_ADMIN_PASSWORD=$(ADMIN_PASSWORD) \
		uv run pytest tests/conformance -m "" --no-cov -q -p no:cacheprovider

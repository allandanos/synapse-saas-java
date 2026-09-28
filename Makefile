.PHONY: build test run conformance
REF ?= ../synapse-saas

build: ## Compile + package
	mvn -q -B -DskipTests package

test: ## Unit + slice tests
	mvn -q -B test

run: ## Boot against the reference dev stack's Postgres (:5433)
	SYNAPSE_JDBC_URL=jdbc:postgresql://localhost:5433/synapse mvn -q -B spring-boot:run

conformance: ## The reference repo's black-box suite against this port on :8080
	cd $(REF) && SYNAPSE_CONFORMANCE_API_URL=http://localhost:8080 uv run pytest tests/conformance -m "" --no-cov -q

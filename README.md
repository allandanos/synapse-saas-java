# synapse-saas-java

Spring Boot 3.4 / Java 21 implementation of the **Synapse SaaS Framework
contract v1**. The reference implementation, the contract, and the acceptance
suite live in [`synapse-saas`](../synapse-saas) — see its
[ADR 0012](../synapse-saas/docs/adr/0012-polyglot-ports-contract-first.md) and
[porting guide](../synapse-saas/ports/README.md).

**Contract pinned at:** `synapse-saas@820ce2f` (`contracts/` is a snapshot of
that commit; re-copy when the reference's `contracts/CHANGELOG.md` gains an entry).

## Status

| Milestone | Scope | State |
|---|---|---|
| 1 | pure logic + core + probes/`/v1/meta` | **started** — `/healthz`, `/readyz`, `/v1/meta`, Flyway baseline, stateless security shell |
| 2 | identity, tenancy, authorization | — |
| 3 | subscriptions, entitlements, usage | — |
| 4 | billing, invoicing, worker | — |
| 5 | webhooks, files, flags, audit, agents | — |
| 6 | console parity (Playwright) | — |
| 7 | OIDC + OpenFGA, hardening | — |

A milestone is done when the corresponding `tests/conformance` modules pass
against this server (`make conformance`).

## Stack

Spring Boot 3.4 (Web, Validation, Security, Data JPA, Actuator) · Hibernate 6
(`@TenantId` for tenant entities, `JdbcClient` for the raw-SQL sites) · Flyway
(`V1__baseline.sql` = `contracts/schema-v1.sql`; later Alembic migrations are
mirrored as SQL) · Micrometer + Prometheus at `/metrics` · Testcontainers.

## Run

```bash
make build            # mvn package
make test             # slice tests (no database)
make run              # against the reference dev stack's Postgres on :5433
make conformance      # reference suite → http://localhost:8080
```

Environment: `SYNAPSE_JDBC_URL`, `SYNAPSE_DB_USER`, `SYNAPSE_DB_PASSWORD`,
`SYNAPSE_BILLING_PROVIDER`, `SYNAPSE_IDENTITY_PROVIDER`, `SYNAPSE_TENANT_ISOLATION`
(same names and values as the reference wherever the concept exists).

## Layout

```
src/main/java/dev/synapse/
  Application.java
  core/        config, problem documents, request context, DB + RLS GUCs, cache, outbox  (milestone 1)
  api/         controllers, one package per route family                                 (milestones 1–5)
src/main/resources/db/migration/V1__baseline.sql
contracts/     snapshot of the reference contract (openapi, events, problems, changelog)
```

Package coordinates: `dev.synapse:synapse-saas`. Licence: Apache-2.0.

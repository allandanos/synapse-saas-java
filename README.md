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
| 1 | pure logic + core + probes/`/v1/meta` | **done** — problem documents for every error, request context + `X-Request-Id`, RLS GUCs, transactional outbox + audit writers, Flyway baseline |
| 2 | identity, tenancy, authorization (RBAC), API keys | **done** — `test_meta_and_health`, `test_auth`, `test_tenancy`, `test_authorization`, `test_api_keys` pass (the one exception is `test_key_lifecycle`, which calls `/v1/usage/*` — milestone 3) |
| 3 | subscriptions, entitlements, usage | — |
| 4 | billing, invoicing, worker | — |
| 5 | webhooks, files, flags, audit, agents | — |
| 6 | console parity (Playwright) | — |
| 7 | OIDC + OpenFGA, hardening | — |

A milestone is done when the corresponding `tests/conformance` modules pass
against this server (`make conformance`).

### Deliberately left for later milestones

- Default-plan subscription on org creation, the `users` seat gauge and the
  seat limit on invites, `api_requests` metering of key-authenticated calls
  (milestone 3 — they need the plan catalog and usage counters).
- Redis-backed permission/membership caches and the auth rate limiter
  (`core/cache`, `core/rate_limit`): every check reads Postgres directly.
- OIDC login and OpenFGA (milestone 7). SSO-only users get the reference's
  401 `sso_url` problem from `/v1/auth/login`, but `/v1/auth/oidc/*` does not exist yet.

## Stack

Spring Boot 3.4 (Web, Validation, Security, JDBC, Actuator) · `JdbcClient` +
records over the fixed baseline schema · Flyway (`V1__baseline.sql` =
`contracts/schema-v1.sql`; later Alembic migrations are mirrored as SQL) ·
Spring Security's `Argon2PasswordEncoder` (argon2id, same parameters and
encoding as the reference, hashes interoperate) · `java-jwt` (HS256, same
claims) · Micrometer + Prometheus at `/metrics` · Testcontainers.

**Why no ORM.** The schema is Postgres-specific (citext, `text[]`, jsonb, RLS
policies, `SECURITY DEFINER` lookups, partitioned tables) and is never
generated from code, so a mapping layer would only translate. Repositories are
small `JdbcClient` classes per aggregate returning records; services are
`@Transactional` and commit before the controller serialises (there is no
commit-before-send middleware — see ADR 0012 §5).

## Run

```bash
make build            # mvn package
make test             # unit tests + @SpringBootTest journeys (Testcontainers, or SYNAPSE_TEST_JDBC_URL)
make test-unit        # pure-logic tests only, no database
make run              # against the reference dev stack's Postgres on :5433
make run-pg           # :8080 against the scratch DB with a bootstrapped operator (what `make conformance` expects)
make conformance      # reference suite (milestone-2 modules) → http://localhost:8080
```

Boot with the packaged jar:

```bash
SYNAPSE_JDBC_URL=jdbc:postgresql://localhost:5434/synapse_java \
SYNAPSE_BOOTSTRAP_ADMIN_EMAIL=operator@platform.example.com \
SYNAPSE_BOOTSTRAP_ADMIN_PASSWORD=operator-password-12345 \
java -jar target/synapse-saas-0.1.0-SNAPSHOT.jar
```

On boot the app applies the Flyway baseline, seeds the 21-permission catalog
and the five system roles idempotently (`SystemSeeder`, the reference's
`synapse-cli seed`), and creates-or-promotes the platform operator when the
bootstrap variables are set (`PlatformAdminBootstrap`; an existing account is
promoted, its password is left untouched). That operator is what the external
conformance suite logs in with (`SYNAPSE_CONFORMANCE_ADMIN_EMAIL/PASSWORD`).

### Row-level security mode

`SYNAPSE_TENANT_ISOLATION=app_and_rls` makes every transaction bind the
policies' GUCs (`app.current_user` after authentication, `app.current_tenant`
before the membership query, `app.rls_platform` on operator routes) with
`set_config(..., true)` right after `BEGIN` (`RlsTransactionManager`) and
whenever a service binds a tenant mid-transaction (create org, accept invite).
The API must then connect as a role that is subject to the policies — the
reference's `synapse-cli db provision-app-role` equivalent:

```sql
CREATE ROLE synapse_app LOGIN NOBYPASSRLS NOINHERIT PASSWORD 'synapse_app';
GRANT USAGE ON SCHEMA public TO synapse_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO synapse_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO synapse_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO synapse_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO synapse_app;
```

```bash
make run-rls   # :8080 as synapse_app with app_and_rls; `make conformance` passes the same modules
```

`RoleIsolationCheck` refuses to start when the pair is inconsistent: a
superuser / BYPASSRLS / table-owner role with `app_and_rls` (policies would
never apply), or a subject role with `app` (every tenant query would be empty).

### Environment

Same names and defaults as the reference wherever the concept exists
(`SYNAPSE_*` binds to `synapse.*` in `application.yaml`):

| Variable | Default | Notes |
|---|---|---|
| `SYNAPSE_JDBC_URL` | `jdbc:postgresql://localhost:5433/synapse` | JDBC form of `SYNAPSE_DATABASE_URL` |
| `SYNAPSE_DB_USER` / `SYNAPSE_DB_PASSWORD` | `synapse` / `synapse` | |
| `SYNAPSE_ENV` | `development` | `production` refuses the dev secret key |
| `SYNAPSE_SECRET_KEY` | dev-only default | HS256 signing key |
| `SYNAPSE_ACCESS_TOKEN_TTL_MINUTES` | `15` | |
| `SYNAPSE_REFRESH_TOKEN_TTL_DAYS` | `30` | opaque tokens, stored as SHA-256, rotated on refresh |
| `SYNAPSE_REFRESH_REUSE_GRACE_SECONDS` | `10` | replay outside the window revokes the chain (`token_reuse_detected`) |
| `SYNAPSE_TENANT_ISOLATION` | `app` | `app_and_rls` binds `app.current_user` / `app.current_tenant` / `app.rls_platform` on every transaction |
| `SYNAPSE_WEB_ORIGIN`, `SYNAPSE_WEB_ORIGINS` | `http://localhost:3000` | CORS; `X-Request-Id`, `X-Total-Count`, … are exposed |
| `SYNAPSE_COOKIE_SECURE` | derived | refresh cookie `synapse_rt` Secure flag |
| `SYNAPSE_BOOTSTRAP_ADMIN_EMAIL` / `..._PASSWORD` | unset | platform operator bootstrap (see above) |
| `SYNAPSE_BILLING_PROVIDER`, `SYNAPSE_IDENTITY_PROVIDER` | `manual`, `local` | reported by `/v1/meta` |

### Tests

- `mvn test` runs the pure-logic unit tests (permission catalog and role
  sets, JWT claims, argon2 interop with a reference hash, problem documents,
  ids/slugs, email validation, tenant resolution order) and the
  `@SpringBootTest` journeys in `ApiJourneyTest` (register → org → invite →
  accept → roles → API keys → operator suspension, and the problem-document
  contract) over a real Postgres.
- The journeys use Testcontainers (`pgvector/pgvector:pg17` — the baseline
  needs `vector` and `citext`) unless `SYNAPSE_TEST_JDBC_URL` points at an
  existing scratch database (`make test-pg`).

## Layout

```
src/main/java/dev/synapse/
  Application.java
  core/
    config/        SynapseProperties (SYNAPSE_* settings, production guardrail)
    errors/        DomainError hierarchy (status + title from contracts/problems.json + extras)
    problem/       RFC 7807 rendering: ProblemDocument, ProblemWriter, ValidationErrors, ApiExceptionHandler
    context/       request scope: RequestScopeFilter (X-Request-Id), RequestContextHolder, UserContext, TenantContext
    db/            JdbcClient helpers, RlsGucs + RlsTransactionManager (set_config on every BEGIN), Json
    outbox/        Events vocabulary + OutboxWriter (same transaction, audience public|internal)
    audit/         AuditService (actor resolution incl. API-key attribution)
    security/      Spring Security chain, BearerAuthenticationFilter (JWT | sk_ key), Principal
    web/           @RequirePermission / @RequireTenant / @PlatformAdminOnly + AccessInterceptor, argument resolvers
    ids/, pagination/, validation/
  identity/        users, refresh + reset tokens, PasswordHasher, JwtCodec, IdentityService, AuthController
  tenancy/         organizations, memberships, TenantResolver (X-Org-Id → X-Org-Slug → subdomain → JWT org), controllers
  authorization/   PermissionCatalog (transliterated), roles, AuthorizationService, PermissionGuard, SystemSeeder
  apikeys/         keys bounded by their creator, ApiKeyAuthenticator, controller
  bootstrap/       PlatformAdminBootstrap
  api/             probes + /v1/meta
src/main/resources/db/migration/V1__baseline.sql   (= contracts/schema-v1.sql)
src/test/java/dev/synapse/                          unit tests + journey/ApiJourneyTest + support/
contracts/                                          snapshot of the reference contract
```

## Notes on the baseline

`contracts/schema-v1.sql` is a `pg_dump` in dump order without the usual
`SET check_function_bodies = false` header, so its SQL-language functions
(`synapse_org_for_invite_token`, `synapse_org_for_provider_ref`) are created
before the tables they read. Flyway applies it with
`spring.flyway.init-sqls: SET check_function_bodies = false` so the file can
stay byte-identical to the contract.

## Known differences from the reference server

Behaviour a client can distinguish, kept deliberately:

- Unique-constraint violations (duplicate invite email, duplicate custom-role
  key) are a 409 `conflict` problem; the reference answers 500.
- Unknown paths and unsupported methods are `not_found` / `method_not_allowed`
  problem documents; the reference returns FastAPI's `{"detail": ...}`.
- `POST /v1/orgs/current/members/invite` returns the roles actually attached
  (`role_keys: ["member"]`); the reference's response shows `[]`.
- Deleting a custom role recomputes the affected members' permission sets;
  the reference leaves the denormalised keys stale until the next role write.

Package coordinates: `dev.synapse:synapse-saas`. Licence: Apache-2.0.

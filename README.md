# synapse-saas-java

Spring Boot 3.4 / Java 21 implementation of the **Synapse SaaS Framework
contract v1**. The reference implementation, the contract, and the acceptance
suite live in [`synapse-saas`](../synapse-saas) — see its
[ADR 0012](../synapse-saas/docs/adr/0012-polyglot-ports-contract-first.md) and
[porting guide](../synapse-saas/ports/README.md).

**Contract pinned at:** `synapse-saas@1184245` (`contracts/` is a snapshot of
that commit; re-copy when the reference's `contracts/CHANGELOG.md` gains an entry).

## Status

| Milestone | Scope | State |
|---|---|---|
| 1 | pure logic + core + probes/`/v1/meta` | **done** — problem documents for every error, request context + `X-Request-Id`, RLS GUCs, transactional outbox + audit writers, Flyway baseline |
| 2 | identity, tenancy, authorization (RBAC), API keys | **done** — `test_meta_and_health`, `test_auth`, `test_tenancy`, `test_authorization`, `test_api_keys` pass |
| 3 | subscriptions, entitlements, usage | **done** — plan catalog (`plans.yaml` → validated → synced at boot), default `free` subscription, trial/change/cancel/resume with the state machine and arrears proration, entitlement resolver + operator grants, counters/gauges/idempotency/atomic enforcement, `api_requests` metering of key auth, `users` seat gauge + invite cap. `test_subscriptions`, `test_usage_and_entitlements` and `test_api_keys` pass — except `test_feature_gate_problem_shape`, which needs `GET /v1/agents` (milestone 5) |
| 4 | billing, invoicing, worker | — |
| 5 | webhooks, files, flags, audit, agents | — |
| 6 | console parity (Playwright) | — |
| 7 | OIDC + OpenFGA, hardening | — |

A milestone is done when the corresponding `tests/conformance` modules pass
against this server (`make conformance`).

### Deliberately left for later milestones

- Billing beyond the plan change: customers, checkout, invoices, provider
  webhooks, the renewal/partition-maintenance worker (milestone 4). The
  provider capability table already drives `POST /v1/subscription/change`
  (hosted providers answer 409 `checkout_required` until a subscription was
  purchased through them); the "hosted provider holds the subscription"
  branch reaches `BillingProvider.changePlan`, a clearly marked seam.
- Redis-backed permission/membership/entitlement caches and the auth rate
  limiter (`core/cache`, `core/rate_limit`): every check reads Postgres
  directly; `EntitlementCache` is the drop-in seam.
- `GET /v1/agents` and the other feature-gated routes (milestone 5) — the
  gate itself (`@RequireFeature`, `FeatureGate`) is in place.
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
`synapse-cli seed`), creates-or-promotes the platform operator when the
bootstrap variables are set (`PlatformAdminBootstrap`; an existing account is
promoted, its password is left untouched), and syncs the plan catalog
(`PlanCatalogSyncRunner`, `SYNAPSE_AUTO_SYNC_PLANS=true`). That operator is
what the external conformance suite logs in with
(`SYNAPSE_CONFORMANCE_ADMIN_EMAIL/PASSWORD`).

### Plan catalog

`src/main/resources/config/plans.yaml` is a verbatim copy of the reference's
catalog (a unit test keeps them identical); `SYNAPSE_PLANS_FILE` points a
product at its own file. `PlanCatalog.fromRaw` mirrors the pydantic models —
field constraints, `extra="forbid"`, the `price_cents` xor `price: custom`
rule — and then the cross checks (unknown features/metrics, duplicate keys,
overage on unlimited metrics, public plans without a concrete price), reported
all at once as a `plan_catalog_invalid` problem. `PlanCatalogSync` projects it
onto `features`/`metrics`/`plans`/`plan_features`/`plan_limits`: upserts by
natural key, removed plans are archived, existing `plan_snapshot`s are never
rewritten. `java -jar … --plans-sync --server.port=0` syncs once and exits.

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
| `SYNAPSE_BILLING_PROVIDER`, `SYNAPSE_IDENTITY_PROVIDER` | `manual`, `local` | reported by `/v1/meta`; the billing provider decides how `POST /v1/subscription/change` behaves (capability table, ADR 0004) |
| `SYNAPSE_PLANS_FILE` | `classpath:config/plans.yaml` | the plan catalog (a filesystem path or `classpath:` resource) |
| `SYNAPSE_AUTO_SYNC_PLANS` | `true` | sync the catalog into the database at every boot (failure is fatal only in production) |
| `SYNAPSE_DEFAULT_PLAN_KEY` | `free` | the subscription every new organization starts on, and the plan an org without one resolves against |
| `SYNAPSE_GRACE_ON_PAST_DUE` | `true` | `past_due` keeps plan features |
| `SYNAPSE_BILLING_CURRENCY` | `PHP` | |
| `SYNAPSE_STRIPE_SECRET_KEY`, `SYNAPSE_PADDLE_SECRET_KEY`, `SYNAPSE_XENDIT_SECRET_KEY`, `SYNAPSE_PAYMONGO_SECRET_KEY` | unset | required when that provider is selected (409 `billing_provider_not_configured` otherwise); the provider HTTP integrations are milestone 4 |

### Tests

- `mvn test` runs the pure-logic unit tests (permission catalog and role
  sets, JWT claims, argon2 interop with a reference hash, problem documents,
  ids/slugs, email validation, tenant resolution order, and the milestone-3
  transliterations: catalog validation, subscription state machine, proration
  arithmetic, entitlement resolver matrix, `checkAgainst`) and the
  `@SpringBootTest` journeys over a real Postgres: `ApiJourneyTest` (register →
  org → invite → accept → roles → API keys → operator suspension, and the
  problem-document contract) and `BillingJourneyTest` (catalog → free plan →
  consume until 402 → trial → paid plans with proration → idempotent record →
  batch rollback → gauges + seats → operator grants + feature gate → key-auth
  metering → ten parallel consumers against a three-slot limit).
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
  apikeys/         keys bounded by their creator, ApiKeyAuthenticator (+ api_requests metering), controller
  subscriptions/   PlanCatalog (+ loader, sync, boot runner), Plan/Metric/Subscription records + repositories,
                   SubscriptionStateMachine, Proration, SubscriptionService, /v1/plans, /v1/subscription
  billing/         BillingCapability table, ManualBillingProvider, hosted-provider descriptors, BillingService.changePlan
  entitlements/    EntitlementResolver (pure), EntitlementService (+ cache seam), FeatureGate (@RequireFeature),
                   /v1/entitlements, operator /v1/admin/orgs/{id}/entitlements
  usage/           UsageService (record/consume/gauges/idempotency/soft limits), UsageRepository, /v1/usage/*
  bootstrap/       PlatformAdminBootstrap
  api/             probes + /v1/meta
src/main/resources/db/migration/V1__baseline.sql   (= contracts/schema-v1.sql)
src/main/resources/config/plans.yaml                (= the reference's config/plans.yaml)
src/test/java/dev/synapse/                          unit tests + journey/ApiJourneyTest + support/
contracts/                                          snapshot of the reference contract
```

## Notes on the baseline

`contracts/schema-v1.sql` is a `pg_dump` in dump order: its SQL-language
functions (`synapse_org_for_invite_token`, `synapse_org_for_provider_ref`) are
created before the tables they read, which only applies with
`check_function_bodies` off. The contract carries that `SET` line since
`synapse-saas@4de2026`; Flyway additionally runs it as `init-sqls` so the
baseline applies even when a copy of the file loses its header.

## Known differences from the reference server

Behaviour a client can distinguish, kept deliberately:

- Deleting a custom role recomputes the affected members' permission sets;
  the reference leaves the denormalised keys stale until the next role write.
- Any unexpected unique-constraint violation (not the invite-email and
  role-key cases, which the contract now answers with 409) is a 409
  `conflict` problem instead of a 500.
- `GET /v1/usage/summary?period=2026-13` (matches the `YYYY-MM` pattern but
  is not a month) is a 422 `validation_failed`; the reference raises from
  `strptime` and answers 500.
- API-key metering of `api_requests` runs in its own short transaction right
  after authentication (the reference uses a savepoint inside the request
  transaction); the observable contract — a metering failure never fails the
  request — is the same.

Package coordinates: `dev.synapse:synapse-saas`. Licence: Apache-2.0.

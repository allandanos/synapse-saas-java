# synapse-saas-java

Spring Boot 3.4 / Java 21 implementation of the **Synapse SaaS Framework
contract v1**. The reference implementation, the contract, and the acceptance
suite live in [`synapse-saas`](../synapse-saas) — see its
[ADR 0012](../synapse-saas/docs/adr/0012-polyglot-ports-contract-first.md) and
[porting guide](../synapse-saas/ports/README.md).

**Contract pinned at:** `synapse-saas@6272ab3` (`contracts/` is a snapshot of
that commit; re-copy when the reference's `contracts/CHANGELOG.md` gains an entry).

## Status

| Milestone | Scope | State |
|---|---|---|
| 1 | pure logic + core + probes/`/v1/meta` | **done** — problem documents for every error, request context + `X-Request-Id`, RLS GUCs, transactional outbox + audit writers, Flyway baseline |
| 2 | identity, tenancy, authorization (RBAC), API keys | **done** — `test_meta_and_health`, `test_auth`, `test_tenancy`, `test_authorization`, `test_api_keys` pass |
| 3 | subscriptions, entitlements, usage | **done** — plan catalog (`plans.yaml` → validated → synced at boot), default `free` subscription, trial/change/cancel/resume with the state machine and arrears proration, entitlement resolver + operator grants, counters/gauges/idempotency/atomic enforcement, `api_requests` metering of key auth, `users` seat gauge + invite cap. `test_subscriptions`, `test_usage_and_entitlements` and `test_api_keys` pass |
| 4 | billing providers, invoicing, notifications, worker | **done** — five providers behind one capability table (manual, Stripe, Paddle, Xendit, PayMongo) with real HTTP clients and webhook verification, checkout/confirm/portal, framework-native invoicing (draft → finalize → pay/void, overage + proration lines, PDFs), spend/revenue reports, signed provider-webhook ingest with an idempotency ledger, the outbox dispatcher + signed outbound deliveries (Fernet-encrypted endpoint secrets), SMTP notifications, and the seven cron jobs in-process or standalone. `test_billing` passes |
| 5 | webhooks, files, flags, audit, agents | **done** — webhook endpoint management + the delivery log + retry over the milestone-4 engine, org-scoped files on local disk or any S3-compatible bucket (direct multipart, presigned PUT/GET, the `storage_bytes` gauge both ways), feature flags with deterministic percentage rollouts and org/user overrides, the audit read route, and the agent registry behind the `agents` entitlement. **The entire `tests/conformance` suite passes** |
| 6 | console parity (Playwright) | — |
| 7 | OIDC + OpenFGA, hardening | — |

A milestone is done when the corresponding `tests/conformance` modules pass
against this server. `make conformance` runs the **whole** suite — as of
milestone 5 every module passes, with nothing deselected.

### Deliberately left for later milestones

- Redis-backed permission/membership/entitlement caches and the auth rate
  limiter (`core/cache`, `core/rate_limit`): every check reads Postgres
  directly; `EntitlementCache` is the drop-in seam. `/readyz` reports
  `redis: not_configured`, exactly as the reference does when the URL is unset.
- The OpenFGA tuple-sync consumer the outbox dispatcher leaves room for
  (milestone 7), and Stripe plan sync from the CLI
  (`StripeBillingProvider.upsertProductAndPrice` exists, nothing calls it yet).
- OIDC login and OpenFGA (milestone 7). SSO-only users get the reference's
  401 `sso_url` problem from `/v1/auth/login`, but `/v1/auth/oidc/*` does not exist yet.

## Stack

Spring Boot 3.4 (Web, Validation, Security, JDBC, Actuator) · AWS SDK v2 `s3`
(S3-compatible storage + its SigV4 presigner) · `JdbcClient` +
records over the fixed baseline schema · Flyway (`V1__baseline.sql` =
`contracts/schema-v1.sql`; later Alembic migrations are mirrored as SQL) ·
Spring Security's `Argon2PasswordEncoder` (argon2id, same parameters and
encoding as the reference, hashes interoperate) · `java-jwt` (HS256, same
claims) · OpenPDF (core Helvetica, no system deps) for invoice PDFs ·
`spring-boot-starter-mail` for SMTP · Micrometer + Prometheus at `/metrics` ·
Testcontainers + GreenMail.

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
make worker           # the standalone worker: cron jobs, no web server
make jobs-run-once    # every job once, printing `name: count` (JOBS="dispatch_outbox purge_expired" for a subset)
make conformance      # the reference's WHOLE suite (every module, no exclusions) → http://localhost:8080
```

Boot with the packaged jar:

```bash
SYNAPSE_JDBC_URL=jdbc:postgresql://localhost:5434/synapse_java \
SYNAPSE_BOOTSTRAP_ADMIN_EMAIL=operator@platform.example.com \
SYNAPSE_BOOTSTRAP_ADMIN_PASSWORD=operator-password-12345 \
SYNAPSE_STORAGE_ROOT=.storage \
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

### Worker

The seven maintenance jobs run **in-process with the API** by default
(`SYNAPSE_WORKER_ENABLED=true`), on the reference's cadences:

| Job | Cadence | What it does |
|---|---|---|
| `dispatch_outbox` | every 5 s | fan public events out to the org's subscribed `webhook_endpoints`, mark them published, then run the in-process email consumers |
| `deliver_webhooks` | every 15 s | POST due deliveries with `X-Synapse-Signature`, walk the backoff ladder, `exhausted` after 6 attempts |
| `rollup_usage` | hourly at :05 | rebuild the current period's counters from `usage_events` |
| `expire_entitlements` | hourly at :10 | revoke lapsed grants, emit `entitlement.expired`, invalidate caches |
| `advance_recurring_billing` | hourly at :20 | for locally billed providers: invoice the ENDED period through the invoicing engine, then roll the period forward |
| `ensure_partitions` | daily 03:30 | pre-create `usage_events_yYYYYmMM` three months ahead |
| `purge_expired` | daily 03:40 | retention: deliveries 30 d (exhausted 90 d), outbox 7 d, idempotency keys 90 d, audit logs `SYNAPSE_AUDIT_RETENTION_DAYS`; abandoned presigned uploads are soft-deleted and their reserved bytes released after twice `SYNAPSE_STORAGE_PRESIGN_SECONDS` |

Coordination needs **no extra table**: every tick takes
`pg_try_advisory_lock(hashtext('job:<name>'))` on its own connection and skips
if another worker holds it, and rows are claimed with `FOR UPDATE SKIP LOCKED`,
so N API instances and N workers can run the same jobs safely.

```bash
java -jar target/synapse-saas-0.1.0-SNAPSHOT.jar --worker                    # standalone: jobs only, no web server
java -jar target/synapse-saas-0.1.0-SNAPSHOT.jar --jobs-run-once --all       # one pass, prints `name: count`, then exits
java -jar target/synapse-saas-0.1.0-SNAPSHOT.jar --jobs-run-once purge_expired
SYNAPSE_WORKER_ENABLED=false java -jar target/…jar                           # API only (a separate worker deployment)
```

`--jobs-run-once` exits 2 on an unknown or empty job selection and 1 when a job
threw; `skipped (locked)` means another worker was already running it.

### Billing providers

`SYNAPSE_BILLING_PROVIDER` selects one of five; services branch on the
**capability table** (ADR 0004), never on the name:

| Provider | hosted_checkout | billing_portal | recurring_hosted | webhook_signed | client_confirm | Webhook authentication |
|---|---|---|---|---|---|---|
| `manual` | ✓ (internal page) | | | | ✓ | `X-Manual-Token` equals `SYNAPSE_MANUAL_WEBHOOK_TOKEN` |
| `stripe` | ✓ | ✓ | ✓ | ✓ | | `Stripe-Signature: t=…,v1=…`, HMAC-SHA256 over `"{t}.{body}"`, 300 s window |
| `paddle` | ✓ | | | ✓ | | `Paddle-Signature: ts=…;h1=…` over `"{ts}:{body}"` |
| `xendit` | ✓ | | | ✓ | | `X-Callback-Token` equals `SYNAPSE_XENDIT_WEBHOOK_TOKEN` |
| `paymongo` | ✓ | | | ✓ | | `Paymongo-Signature: t=…,v1=…` (Stripe's scheme) |

Only `manual` carries `client_confirm`, so only `manual` may activate a plan on
`POST /v1/billing/checkout/confirm`; every other provider answers 409
`checkout_confirm_not_allowed` and activates when its webhook arrives. Providers
WITHOUT `recurring_hosted` are billed by us — `advance_recurring_billing` renews
exactly those. Money is integer minor units end to end (ADR 0006); Xendit, which
speaks major units, converts with `BigDecimal`.

Provider webhooks land on `POST /v1/billing/webhooks/{provider}` (unauthenticated
by bearer — the signature IS the authentication). The controller reads the raw
bytes itself, `provider_webhook_events` makes replays a 200 no-op, and each
translated event applies in its own savepoint: a `DomainError` is recorded on the
ledger row and still answers 200, anything else 500s so the row rolls back and
the provider retries.

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
| `SYNAPSE_STRIPE_SECRET_KEY`, `SYNAPSE_PADDLE_SECRET_KEY`, `SYNAPSE_XENDIT_SECRET_KEY`, `SYNAPSE_PAYMONGO_SECRET_KEY` | unset | required when that provider is selected (409 `billing_provider_not_configured` otherwise) |
| `SYNAPSE_STRIPE_WEBHOOK_SECRET`, `SYNAPSE_PADDLE_WEBHOOK_SECRET`, `SYNAPSE_PAYMONGO_WEBHOOK_SECRET` | unset | HMAC secret for that provider's webhook signature |
| `SYNAPSE_XENDIT_WEBHOOK_TOKEN`, `SYNAPSE_MANUAL_WEBHOOK_TOKEN` | unset | static callback token; empty ⇒ that provider's ingest refuses everything |
| `SYNAPSE_MANUAL_PAY_TO_INSTRUCTIONS` | empty | payment instructions printed on unpaid invoice PDFs and mailed with them |
| `SYNAPSE_WORKER_ENABLED` | `true` | run the seven cron jobs in-process with the API |
| `SYNAPSE_AUDIT_RETENTION_DAYS` | `365` | `purge_expired` deletes `audit_logs` older than this |
| `SYNAPSE_S3_BUCKET` | unset | set ⇒ the S3-compatible backend; unset ⇒ local disk under `SYNAPSE_STORAGE_ROOT` |
| `SYNAPSE_S3_ENDPOINT_URL` | unset | MinIO / Cloudflare R2 (forces path-style addressing); empty ⇒ AWS |
| `SYNAPSE_S3_REGION` | `us-east-1` | |
| `SYNAPSE_S3_ACCESS_KEY_ID` / `SYNAPSE_S3_SECRET_ACCESS_KEY` | unset | unset ⇒ the SDK's default credential chain |
| `SYNAPSE_STORAGE_ROOT` | `.storage` | local-disk root; keys are always `{org_id}/…` |
| `SYNAPSE_STORAGE_PRESIGN_SECONDS` | `3600` | presigned URL lifetime; `purge_expired` reclaims presigned uploads never completed after twice this |
| `SYNAPSE_NOTIFIER` | `smtp` | `noop` logs instead of sending |
| `SYNAPSE_SMTP_HOST` / `_PORT` / `_FROM` / `_USERNAME` / `_PASSWORD` | unset / `1025` / `synapse@localhost` / unset | empty host ⇒ every send is logged and dropped |
| `SYNAPSE_SMTP_TLS` | `none` | `none` \| `starttls` \| `ssl`; AUTH over a plaintext channel is refused |

### Tests

- `mvn test` runs the pure-logic unit tests (permission catalog and role
  sets, JWT claims, argon2 interop with a reference hash, problem documents,
  ids/slugs, email validation, tenant resolution order, the milestone-3
  transliterations — catalog validation, subscription state machine, proration
  arithmetic, entitlement resolver matrix, `checkAgainst` — and the milestone-4
  ones: the webhook signature matrix for all five providers with digests pinned
  against the reference's `sign_payload`, `translate_webhook` fixtures per
  provider, the invoice transition table / numbering / period arithmetic /
  line reconciliation, the worker's batch, backoff and retention constants, the
  event audience, Fernet round-trips **plus a token written by the reference's
  `cryptography`**, and the provider HTTP clients against a local
  `StubProviderServer`; and the milestone-5 ones: the flag bucketing/rollout
  matrix with two bucket values pinned against the reference's `bucket_of`,
  storage key validation + org scoping + the local backend, the S3 presigned
  URL shape (path style for a custom endpoint, virtual-host for AWS), webhook
  endpoint URL validation, and the event vocabulary checked against
  `contracts/events.json`).
- `@SpringBootTest` journeys over a real Postgres: `ApiJourneyTest` (register →
  org → invite → accept → roles → API keys → operator suspension, and the
  problem-document contract), `BillingJourneyTest` (catalog → free plan →
  consume until 402 → trial → paid plans with proration → idempotent record →
  batch rollback → gauges + seats → operator grants + feature gate → key-auth
  metering → ten parallel consumers against a three-slot limit),
  `InvoicingJourneyTest` (manual checkout → confirm → draft → finalize → PDF →
  operator pay/void, overage priced from real usage, proration landing on the
  next draft, the renewal job invoicing an ended period and leaving hosted or
  cancelling subscriptions alone, cross-tenant 404s),
  `BillingWebhookJourneyTest` (unsigned 400 with no ledger row, apply once,
  replay 200 no-op, a business rejection recorded + 200, an unexpected failure
  500 with the ledger row rolled back) and `WorkerJourneyTest` (fan-out to a
  live HTTP endpoint with a signature the receiver verifies, internal events
  never fanned out, endpoint filters, the delivery ladder to `exhausted`, a
  poison outbox row dead-lettered on the eighth attempt, partition
  pre-creation, every retention window, and the invite/reset/invoice emails
  captured by an in-process GreenMail with the invoice PDF attached),
  `RegistryJourneyTest` (the agent registry invisible without its entitlement
  then full CRUD + a slug that stays taken after a delete, webhook endpoint
  creation showing its secret exactly once and never again with the
  `webhook.endpoint_created`/`_deleted` events and audit rows, a real outbox
  event producing a delivery that retry requeues, flags resolving user → org →
  global with rollouts and the operator-only surface answering 404 to tenants,
  and the audit route's filters, limits and API-key attribution),
  `StorageJourneyTest` (upload → list → download bytes → gauge up → delete →
  gauge down with the `file.uploaded`/`file.deleted` events and audit rows, the
  quota refusing an upload before a byte is written, the multipart rules and
  the 10 MiB cap, presign 409 on local disk, and the retention job releasing
  abandoned reservations) and `S3StorageJourneyTest` (the same surface against
  MinIO: presign-upload → the client's own PUT → complete → ready, and
  completing without a PUT answering 409 with the reservation released).
- The journeys use Testcontainers (`pgvector/pgvector:pg17` — the baseline
  needs `vector` and `citext`) unless `SYNAPSE_TEST_JDBC_URL` points at an
  existing scratch database (`make test-pg`). `S3StorageJourneyTest` does the
  same for MinIO: `SYNAPSE_TEST_S3_ENDPOINT` (plus
  `SYNAPSE_TEST_S3_ACCESS_KEY_ID` / `_SECRET_ACCESS_KEY`) points at a running
  one, otherwise Testcontainers starts `quay.io/minio/minio`; with neither, its
  two tests skip and the local-disk journey still covers the rest.

```bash
docker run -d --name minio -p 9010:9000 -e MINIO_ROOT_USER=minio -e MINIO_ROOT_PASSWORD=minio12345 \
  quay.io/minio/minio server /data
SYNAPSE_TEST_S3_ENDPOINT=http://localhost:9010 SYNAPSE_TEST_JDBC_URL=jdbc:postgresql://[::1]:5434/synapse_java_test mvn test
```

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
    web/           @RequirePermission / @RequireTenant / @RequireFeature / @RequireFlag / @PlatformAdminOnly +
                   AccessInterceptor (class- or method-level), argument resolvers
    ids/, pagination/, validation/
  identity/        users, refresh + reset tokens, PasswordHasher, JwtCodec, IdentityService, AuthController
  tenancy/         organizations, memberships, TenantResolver (X-Org-Id → X-Org-Slug → subdomain → JWT org), controllers
  authorization/   PermissionCatalog (transliterated), roles, AuthorizationService, PermissionGuard, SystemSeeder
  apikeys/         keys bounded by their creator, ApiKeyAuthenticator (+ api_requests metering), controller
  subscriptions/   PlanCatalog (+ loader, sync, boot runner), Plan/Metric/Subscription records + repositories,
                   SubscriptionStateMachine, Proration, SubscriptionService, /v1/plans, /v1/subscription
  billing/         BillingCapability table + BillingProvider (protocol DTOs in BillingRefs), Signatures, ProviderHttp,
                   providers/{Manual,Stripe,Paddle,Xendit,PayMongo}BillingProvider, BillingProviderRegistry,
                   BillingService (customers, checkout, portal, plan change), /v1/billing,
                   invoicing/ (Invoice + lines, transitions, numbering, InvoicingService, InvoicePdf, routes),
                   reporting/ (spend + revenue read models), webhooks/ (raw-body ingest + provider_webhook_events ledger)
  webhooks/        WebhookEndpoint/Delivery repositories, FernetCodec (secrets at rest), WebhookDeliveryService (envelope +
                   signature, endpoint CRUD, retry), WebhookUrls (HttpUrl validation), /v1/webhooks
  storage/         StorageBackend + LocalDiskStorage | S3Storage (AWS SDK v2 presigner), StorageKeys ({org_id}/… + traversal
                   guard), StoredFileRepository, FileService (gauge-first ordering, complete/release), /v1/files
  featureflags/    FlagBuckets (sha256 bucketing), FeatureFlagService (user → org → global), FlagGate (@RequireFlag),
                   /v1/feature-flags (operator CRUD + overrides) and /check/{key}
  agents/          Agent registry (ADR 0007): repository, service (events + audit), /v1/agents behind @RequireFeature("agents")
  audit/           AuditQueryRepository + /v1/audit (the rows core/audit writes)
  notifications/   Notifier seam, SmtpNotifier / NoopNotifier, NotificationHandlers (invite, reset, invoice, soft limit)
  worker/          Job + JobRegistry + AdvisoryLock, WorkerScheduler (@Scheduled cadences), JobsRunOnce,
                   jobs/{OutboxDispatch,DeliverWebhooks,RollupUsage,ExpireEntitlements,AdvanceRecurringBilling,EnsurePartitions,PurgeExpired}
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
- API-key metering of `api_requests` runs in its own short transaction right
  after authentication (the reference uses a savepoint inside the request
  transaction); the observable contract — a metering failure never fails the
  request — is the same.
- The delivery envelope's `created_at` is an ISO-8601 instant ending in `Z`
  (`2026-09-29T02:36:43.123456Z`); the reference renders the same moment as
  `+00:00`. Both parse identically.
- Invoice PDFs carry the same visible content as the reference's (same
  sections, same latin-1 sanitising, same money and quantity formatting) but a
  different byte layout — OpenPDF is not fpdf2.
- Presigned S3 URLs come from the AWS SDK v2 presigner, the reference's from
  botocore; both are SigV4 and interchangeable for a client, but the query
  parameter order and the exact signed-header set can differ.
- A multipart upload past the container's own ceiling is answered with the
  reference's 400 `storage_error` rather than a 413: the 10 MiB rule is
  enforced in code, and `MaxUploadSizeExceededException` is mapped to the same
  problem so the answer never depends on which layer noticed first.

Package coordinates: `dev.synapse:synapse-saas`. Licence: Apache-2.0.

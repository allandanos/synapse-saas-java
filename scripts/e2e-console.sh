#!/usr/bin/env bash
# Milestone 6 — run the REFERENCE console's Playwright journeys against this port.
#
# The console and its specs are never modified: this copies apps/web out of the
# reference repo, builds it with NEXT_PUBLIC_API_URL pointing at this server,
# boots MailHog + a freshly seeded server, runs the journeys and tears down.
#
# Everything is overridable so two ports can run side by side on one machine:
#
#   REF              reference repo                 ../synapse-saas
#   CONSOLE_DIR      console copy                   /tmp/synapse-console-java
#   CONSOLE_PORT     console port                   3300
#   API_PORT         this server's port             8080
#   JDBC_URL         database                       jdbc:postgresql://[::1]:5434/synapse_java
#   PG_CONTAINER     container used to (re)create it  synapse-saas-postgres-test-1
#   PG_DATABASE      database name                  synapse_java
#   MAILHOG_NAME     container name                 mailhog-java
#   MAILHOG_SMTP     SMTP port                      1035
#   MAILHOG_HTTP     JSON API port                  8035
#   RESET_DB         drop + recreate before seeding 1
#   KEEP_STACK       leave server/console/MailHog up on exit  0
#   SPECS            playwright arguments           (all)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REF="${REF:-$ROOT/../synapse-saas}"
CONSOLE_DIR="${CONSOLE_DIR:-/tmp/synapse-console-java}"
CONSOLE_PORT="${CONSOLE_PORT:-3300}"
API_PORT="${API_PORT:-8080}"
PG_CONTAINER="${PG_CONTAINER:-synapse-saas-postgres-test-1}"
PG_DATABASE="${PG_DATABASE:-synapse_java}"
JDBC_URL="${JDBC_URL:-jdbc:postgresql://[::1]:5434/$PG_DATABASE}"
MAILHOG_NAME="${MAILHOG_NAME:-mailhog-java}"
MAILHOG_SMTP="${MAILHOG_SMTP:-1035}"
MAILHOG_HTTP="${MAILHOG_HTTP:-8035}"
RESET_DB="${RESET_DB:-1}"
KEEP_STACK="${KEEP_STACK:-0}"
JAR="$ROOT/target/synapse-saas-0.1.0-SNAPSHOT.jar"
LOG_DIR="${LOG_DIR:-${TMPDIR:-/tmp}}"
API_LOG="$LOG_DIR/synapse-e2e-api-$API_PORT.log"
CONSOLE_LOG="$LOG_DIR/synapse-e2e-console-$CONSOLE_PORT.log"

api_pid=""
console_pid=""

step() { printf '\n\033[1m── %s\033[0m\n' "$*"; }

cleanup() {
  local status=$?
  if [ "$KEEP_STACK" = "1" ]; then
    echo "KEEP_STACK=1 — server ($api_pid), console ($console_pid) and $MAILHOG_NAME left running"
    return $status
  fi
  step "teardown"
  [ -n "$api_pid" ] && kill "$api_pid" 2>/dev/null || true
  [ -n "$console_pid" ] && kill "$console_pid" 2>/dev/null || true
  # `pnpm start` forks `next start`: also reap whatever still owns our ports,
  # so a re-run never collides with its own leftovers.
  for port in "$API_PORT" "$CONSOLE_PORT"; do
    lsof -ti "tcp:$port" 2>/dev/null | xargs -r kill 2>/dev/null || true
  done
  docker rm -f "$MAILHOG_NAME" >/dev/null 2>&1 || true
  return $status
}
trap cleanup EXIT

wait_for() { # url, label
  for _ in $(seq 1 60); do
    curl -sf -o /dev/null "$1" && return 0
    sleep 1
  done
  echo "timed out waiting for $2 ($1)" >&2
  return 1
}

[ -d "$REF/apps/web/e2e" ] || { echo "reference console not found at $REF/apps/web" >&2; exit 1; }

step "package"
(cd "$ROOT" && mvn -q -B -DskipTests package)

step "MailHog ($MAILHOG_NAME: smtp $MAILHOG_SMTP, api $MAILHOG_HTTP)"
docker rm -f "$MAILHOG_NAME" >/dev/null 2>&1 || true
docker run -d --name "$MAILHOG_NAME" -p "$MAILHOG_SMTP:1025" -p "$MAILHOG_HTTP:8025" mailhog/mailhog:latest >/dev/null
wait_for "http://localhost:$MAILHOG_HTTP/api/v2/messages" MailHog

if [ "$RESET_DB" = "1" ]; then
  step "database $PG_DATABASE (drop + create)"
  docker exec "$PG_CONTAINER" psql -U synapse -d postgres \
    -c "DROP DATABASE IF EXISTS $PG_DATABASE WITH (FORCE);" -c "CREATE DATABASE $PG_DATABASE;" >/dev/null
fi

# Server environment: the console's origin drives CORS and the refresh-cookie
# policy; MailHog is the SMTP sink the invoice journeys assert against.
export SYNAPSE_JDBC_URL="$JDBC_URL"
export SYNAPSE_WEB_ORIGIN="http://localhost:$CONSOLE_PORT"
export SYNAPSE_SMTP_HOST=localhost
export SYNAPSE_SMTP_PORT="$MAILHOG_SMTP"
export SYNAPSE_SMTP_FROM=billing@synapse.test
export SYNAPSE_BILLING_PROVIDER=manual
export SYNAPSE_AUTO_SYNC_PLANS=true
export SYNAPSE_STORAGE_ROOT="${SYNAPSE_STORAGE_ROOT:-$ROOT/.storage}"

step "seed (system catalog, plans, dev org)"
# Command substitution, not a pipe: a failing seed must fail the run, and the
# summary line is the only part of the boot log worth showing.
seed_output="$(java -jar "$JAR" --seed-dev)"
grep -E "^dev seed" <<<"$seed_output" || true

step "server on :$API_PORT (worker in-process: outbox dispatch every 5s)"
java -jar "$JAR" --server.port="$API_PORT" > "$API_LOG" 2>&1 &
api_pid=$!
wait_for "http://localhost:$API_PORT/healthz" "the API" || { tail -40 "$API_LOG"; exit 1; }

step "console copy at $CONSOLE_DIR (NEXT_PUBLIC_API_URL=http://localhost:$API_PORT)"
mkdir -p "$CONSOLE_DIR"
rsync -a --delete --exclude node_modules --exclude .next --exclude test-results \
  --exclude playwright-report "$REF/apps/web/" "$CONSOLE_DIR/"
(cd "$CONSOLE_DIR" && pnpm install --frozen-lockfile >/dev/null)
(cd "$CONSOLE_DIR" && NEXT_PUBLIC_API_URL="http://localhost:$API_PORT" pnpm build >/dev/null)

step "console on :$CONSOLE_PORT"
(cd "$CONSOLE_DIR" && exec env PORT="$CONSOLE_PORT" pnpm start) > "$CONSOLE_LOG" 2>&1 &
console_pid=$!
wait_for "http://localhost:$CONSOLE_PORT/login" "the console" || { tail -40 "$CONSOLE_LOG"; exit 1; }

step "playwright"
cd "$CONSOLE_DIR"
E2E_BASE_URL="http://localhost:$CONSOLE_PORT" \
E2E_API_URL="http://localhost:$API_PORT" \
MAILHOG_API_URL="http://localhost:$MAILHOG_HTTP" \
  pnpm exec playwright test ${SPECS:-}

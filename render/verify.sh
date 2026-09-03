#!/usr/bin/env bash
#
# Pre-deployment checks for the Render Dockerfiles.
#
# Three things, cheapest first:
#   1. the generated files still match the root Dockerfile   (no Docker needed)
#   2. every Dockerfile parses and its build plan resolves   (needs Docker/BuildKit)
#   3. each one selects the right module and port            (static, from the ARG lines)
#
# A full image build is deliberately not run here -- it compiles the whole Gradle module and
# takes minutes per service. Run that separately when you want it:
#
#   docker build -f render/gateway.Dockerfile -t pf-gateway:local .
#
set -euo pipefail

cd "$(dirname "$0")/.."

pass() { printf '  \033[32mok\033[0m      %s\n' "$1"; }
fail() { printf '  \033[31mFAIL\033[0m    %s\n' "$1"; FAILED=1; }
FAILED=0

echo "== 1. generated files match the root Dockerfile =="
if python render/generate.py --check; then
  :
else
  FAILED=1
fi

echo
echo "== 2. expected module and port per file =="
check_args() {
  local file="$1" module="$2" port="$3"
  local got_module got_port
  got_module="$(grep -oP '^ARG SERVICE_MODULE=\K.*' "$file" || true)"
  got_port="$(grep -oP '^ARG SERVICE_PORT=\K.*' "$file" || true)"
  if [ "$got_module" = "$module" ] && [ "$got_port" = "$port" ]; then
    pass "$(basename "$file") -> $module:$port"
  else
    fail "$(basename "$file") -> got '${got_module:-<unset>}:${got_port:-<unset>}', want '$module:$port'"
  fi
}
# Core synchronous path.
check_args render/gateway.Dockerfile      gateway-service          8080
check_args render/identity.Dockerfile     identity-service         8081
check_args render/merchant.Dockerfile     merchant-service         8082
check_args render/payment.Dockerfile      payment-service          8083
check_args render/sandbox.Dockerfile      sandbox-service          8094
# Kafka consumers.
check_args render/transaction.Dockerfile  transaction-service      8084
check_args render/audit.Dockerfile        audit-service            8091
check_args render/notification.Dockerfile notification-service     8092
check_args render/analytics.Dockerfile    analytics-service        8093
# Detachable extension.
check_args render/agentic.Dockerfile      agentic-commerce-service 8095

echo
echo "== 3. each Dockerfile parses and its build plan resolves =="
if ! docker info >/dev/null 2>&1; then
  echo "  skipped -- Docker is not running"
else
  for f in render/*.Dockerfile; do
    # `--call=outline` resolves the full build plan (stages, ARGs, base images, context paths)
    # without executing a single build step. It fails on a syntax error, an unresolvable base
    # image, or a COPY whose source is missing from the context -- which is exactly the class
    # of mistake these generated files could introduce.
    if docker build --call=outline -f "$f" . >/dev/null 2>&1; then
      pass "$(basename "$f") parses, plan resolves"
    elif docker build --check -f "$f" . >/dev/null 2>&1; then
      pass "$(basename "$f") parses (--check; older Buildx without --call)"
    else
      fail "$(basename "$f") failed to parse or resolve"
    fi
  done
fi

echo
if [ "$FAILED" -ne 0 ]; then
  echo "FAILED"
  exit 1
fi
echo "All checks passed."

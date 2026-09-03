#!/usr/bin/env bash
#
# Pre-deployment check for the external Kafka broker.
#
# Run this BEFORE the first Blueprint sync, and again whenever the broker or the
# `paymentflow-kafka` environment group changes.
#
# Why this exists
# ---------------
# Topics are created at boot by Spring's KafkaAdmin from the NewTopic beans. KafkaAdmin is not
# fatal-if-unavailable: when the broker refuses a topic the service logs `Could not configure
# topics` at ERROR and then starts up perfectly healthy. The producer then fails on send and the
# matching consumer idles for ever. A green deploy is NOT evidence that the event fabric works.
#
# That is not hypothetical. An Aiven free-tier service refuses this platform outright -- it caps
# a user at 5 topics and 2 partitions per topic, against the 10 topics and up to 6 partitions
# declared below -- and every service still reported healthy.
#
# Usage
# -----
#   render/verify-kafka.sh <bootstrap-server> <client.properties>          # check only
#   render/verify-kafka.sh <bootstrap-server> <client.properties> --create # check, then create
#
# <client.properties> is a standard Kafka client config holding the SASL settings, e.g.
#
#   security.protocol=SASL_SSL
#   sasl.mechanism=SCRAM-SHA-256
#   sasl.jaas.config=org.apache.kafka.common.security.scram.ScramLoginModule required \
#       username="..." password="...";
#
# It embeds a password, so it is a credential file: .gitignore already excludes
# `*-client.properties`. Keep it out of the repository.
#
# Needs Docker. The Kafka CLI runs from the apache/kafka image so nothing has to be installed.
set -euo pipefail

cd "$(dirname "$0")/.."

BOOTSTRAP="${1:-}"
PROPS="${2:-}"
MODE="${3:-check}"

if [ -z "$BOOTSTRAP" ] || [ -z "$PROPS" ]; then
  sed -n '3,32p' "$0" | sed 's/^# \{0,1\}//'
  exit 2
fi
if [ ! -f "$PROPS" ]; then
  echo "error: no such properties file: $PROPS" >&2
  exit 2
fi

KAFKA_IMAGE="${KAFKA_IMAGE:-apache/kafka:3.9.0}"

# Every topic the platform declares, and the partition count its NewTopic bean asks for.
# Sources: gateway/identity/merchant/payment/sandbox KafkaTopicConfig + notification's five.
# Keep in step with those beans -- the drift guard below fails the run if a bean is added.
REQUIRED="
api.request.events:6
webhook.deliveries:6
webhook.deliveries.retry:6
webhook.deliveries.dlq:3
payment.events:3
payment.events.retry:3
payment.events.dlq:3
identity.events:3
merchant.events:3
sandbox.scheduled.events:3
"

pass() { printf '  \033[32mok\033[0m      %s\n' "$1"; }
warn() { printf '  \033[33mwarn\033[0m    %s\n' "$1"; }
fail() { printf '  \033[31mFAIL\033[0m    %s\n' "$1"; FAILED=1; }
FAILED=0

kt() {
  # MSYS_NO_PATHCONV stops Git Bash on Windows from mangling the container-side paths.
  MSYS_NO_PATHCONV=1 docker run --rm \
    -v "$(cd "$(dirname "$PROPS")" && pwd)/$(basename "$PROPS")":/tmp/client.properties:ro \
    "$KAFKA_IMAGE" /opt/kafka/bin/kafka-topics.sh \
    --bootstrap-server "$BOOTSTRAP" --command-config /tmp/client.properties "$@"
}

echo "== 0. the topic table still matches the source =="
# The names and counts above are hand-maintained. This does not re-derive them (the names come
# from Java constants and from application.yaml, which bash has no business parsing), but it does
# catch the drift that actually happens: someone adds a NewTopic bean and never updates this file.
declared=$(grep -rc "public NewTopic" --include="*.java" \
             gateway-service/src/main identity-service/src/main merchant-service/src/main \
             notification-service/src/main payment-service/src/main sandbox-service/src/main \
             2>/dev/null | awk -F: '{s+=$2} END {print s+0}')
listed=$(echo "$REQUIRED" | grep -c ':')
if [ "$declared" -eq "$listed" ]; then
  pass "$listed topics listed, $declared NewTopic beans in the source"
else
  fail "$listed topics listed but $declared NewTopic beans in the source -- update REQUIRED in $0"
fi

echo
echo "== 1. broker reachable and credentials accepted =="
if ! docker info >/dev/null 2>&1; then
  echo "  error: Docker is not running -- this script runs the Kafka CLI from a container" >&2
  exit 2
fi
if ! EXISTING="$(kt --list 2>/dev/null)"; then
  fail "cannot list topics on $BOOTSTRAP -- check the host, port, and SASL settings in $PROPS"
  echo
  echo "FAILED"
  exit 1
fi
pass "authenticated to $BOOTSTRAP"

echo
echo "== 2. every declared topic exists with enough partitions =="
MISSING=""
for entry in $REQUIRED; do
  topic="${entry%%:*}"
  want="${entry##*:}"
  if echo "$EXISTING" | grep -qx "$topic"; then
    got="$(kt --describe --topic "$topic" 2>/dev/null \
             | grep -oE 'PartitionCount: [0-9]+' | head -1 | grep -oE '[0-9]+' || echo 0)"
    if [ "${got:-0}" -ge "$want" ]; then
      pass "$topic ($got partitions, needs $want)"
    else
      # KafkaAdmin will try to grow it on every boot and log an ERROR every time.
      fail "$topic has $got partitions, needs $want"
    fi
  else
    MISSING="$MISSING $topic:$want"
    fail "$topic is missing"
  fi
done

if [ -n "$MISSING" ] && [ "$MODE" = "--create" ]; then
  echo
  echo "== 3. creating the missing topics =="
  # Reset once: step 2's failures are what we are fixing. A failure recorded from here on is a
  # create that was refused, and must survive a later create that succeeds.
  FAILED=0
  for entry in $MISSING; do
    topic="${entry%%:*}"
    want="${entry##*:}"
    # Replication factor is deliberately NOT passed: omitted, the broker applies its own default,
    # which is the only portable choice. Providers disagree sharply and both directions are real
    # -- Aiven silently raises a requested 1 to its minimum of 2, while Redpanda Serverless
    # refuses anything but exactly 3 (`InvalidReplicationFactorException: replication factor must
    # be 3`). Hard-coding any value breaks on some broker; a single-broker dev cluster defaults
    # to 1 and a managed cluster to its own minimum, and both are correct.
    if out="$(kt --create --topic "$topic" --partitions "$want" 2>&1)"; then
      pass "created $topic ($want partitions)"
    else
      fail "could not create $topic: $(echo "$out" | grep -oE '[A-Za-z]*Exception.*' | head -1)"
    fi
  done
  echo
  # A create that reports success while the topic stays invisible is the signature of a
  # cluster-scoped-only ACL: CreateTopics is a CLUSTER operation and is allowed, but listing and
  # describing need Describe on the TOPIC resource, and a denied list is filtered to empty rather
  # than refused. Left unfixed the platform deploys green and moves no traffic, because producers
  # need Write on TOPIC and consumers need Read on TOPIC plus Read on GROUP.
  if [ "$FAILED" -eq 0 ] && [ -z "$(kt --list 2>/dev/null)" ]; then
    warn "every create succeeded but the topic list is empty -- the principal almost certainly"
    warn "has ACLs on CLUSTER only. Grant TOPIC and GROUP too, or nothing will flow:"
    warn "  kafka-acls.sh --add --allow-principal User:<user> --allow-host '*' \\"
    warn "                --operation All --topic '*' --group '*'"
  fi
  echo "  re-run without --create to confirm the final state"
  exit $FAILED
fi

echo
if [ "$FAILED" -ne 0 ]; then
  echo "FAILED -- this broker cannot host the platform as configured."
  echo
  echo "  If topics are merely missing, re-run with --create to provision them."
  echo "  If a create is refused with PolicyViolationException, the plan is too small:"
  echo "  the platform needs 10 topics and 39 partitions in total. See the Kafka note in"
  echo "  render.yaml for measured provider limits."
  echo "  If a create is refused with InvalidReplicationFactorException, the broker demands a"
  echo "  specific factor; this script leaves it to the broker default, so that means the"
  echo "  default itself is unusable -- check the cluster, not this script."
  echo "  If EVERY topic reports missing but creating one says it already exists, the principal"
  echo "  has ACLs on CLUSTER only -- grant TOPIC and GROUP as well (see above)."
  exit 1
fi
echo "All checks passed -- the broker can host the platform."

# Deployment

Three targets, deployed independently:

| Component | Target | Declared by |
|---|---|---|
| Ten backend services, Postgres, Key Value | Render | `render.yaml` |
| Developer Portal | Vercel | `developer-portal/` |
| Kafka | Redpanda Cloud Serverless (external) | not declared — see step 1 |

`mock-project/` is **local-only**. It is not tracked, not built, and never deployed.

Nothing here is committed, pushed, or synced automatically. Every step is manual and explicit.

---

## Step 1 — Kafka, before anything else

The platform needs **10 topics and 39 partitions**. Getting this wrong fails silently, so it is
the first step and it is verified rather than assumed.

Spring's `KafkaAdmin` creates the topics at boot from the `NewTopic` beans, and it is **not**
fatal-if-unavailable. When a broker refuses a topic the service logs `Could not configure topics`
at ERROR and then starts up **perfectly healthy** — while the producer fails on every send and
the matching consumer idles for ever. A green deploy is not evidence that the event fabric works.

| Topic | Partitions | Declared by |
|---|---|---|
| `api.request.events` | 6 | gateway-service |
| `webhook.deliveries` | 6 | notification-service |
| `webhook.deliveries.retry` | 6 | notification-service |
| `webhook.deliveries.dlq` | 3 | notification-service |
| `payment.events` | 3 | payment-service |
| `payment.events.retry` | 3 | notification-service |
| `payment.events.dlq` | 3 | notification-service |
| `identity.events` | 3 | identity-service |
| `merchant.events` | 3 | merchant-service |
| `sandbox.scheduled.events` | 3 | sandbox-service |

### 1a. Create the cluster

Redpanda Cloud → **Serverless** → create a cluster. The free tier allows 5,000 partitions, far
more than the 39 needed. Then, on the cluster's **Security** page, create a user with mechanism
**SCRAM-SHA-256**. Collect the **bootstrap server** (`host:port`) and the credentials.

> ⚠ **Grant ACLs on TOPIC and GROUP, not just CLUSTER.** A cluster-scoped grant alone looks like
> it works and is not enough — this cost a debugging cycle. `CreateTopics` is a CLUSTER operation
> so topic creation *succeeds*, but listing and describing need `Describe` on the **TOPIC**
> resource, and a denied list comes back filtered to **empty** rather than refused. The symptom is
> unmistakable once you know it: every topic reports missing, yet creating one says
> `TopicExistsException`. Left unfixed the platform deploys green and moves nothing — producers
> need `Write` on TOPIC, consumers need `Read` on TOPIC and `Read` on GROUP.
>
> ```
> kafka-acls.sh --add --allow-principal User:<user> --allow-host '*' \
>               --operation All --topic '*' --group '*'
> ```

> ⚠ **Do not pin a replication factor.** Providers disagree in both directions: Aiven silently
> raises a requested 1 to its minimum of 2, while Redpanda Serverless refuses anything but
> exactly 3 (`InvalidReplicationFactorException: replication factor must be 3`). The script omits
> the flag so the broker applies its own default. The `.replicas(1)` beans are still fine, because
> `KafkaAdmin` only ever *increases partitions* — it never reconciles replication factor, so a
> pre-created topic is left alone.

> **Measured provider limits.** Aiven's *free* tier cannot host this platform: it enforces a hard
> cap of 5 user topics and 2 partitions per topic, and returns `PolicyViolationException` beyond
> that. Confirmed against a live free-tier service — with every service still reporting healthy.
> Aiven's *Dev* tier (≈$35/month, one-click upgrade) allows 20 topics and 100 partitions and is
> sufficient. Upstash Kafka was discontinued on 2025-03-11 and is not an option.

### 1b. Verify the broker before deploying

Write a client properties file — it holds a password, so keep it out of the repository
(`.gitignore` already excludes `*-client.properties`):

```properties
security.protocol=SASL_SSL
sasl.mechanism=SCRAM-SHA-256
sasl.jaas.config=org.apache.kafka.common.security.scram.ScramLoginModule required \
    username="..." password="...";
```

Then check the broker, and provision the topics:

```bash
render/verify-kafka.sh <bootstrap-server> redpanda-client.properties           # check
render/verify-kafka.sh <bootstrap-server> redpanda-client.properties --create  # provision
render/verify-kafka.sh <bootstrap-server> redpanda-client.properties           # confirm
```

Do not continue until the check prints `All checks passed`. The script needs Docker and runs the
Kafka CLI from a container, so nothing has to be installed.

Provisioning ahead of the deploy is **load-bearing**, not belt-and-braces. Leaving it to
`KafkaAdmin` would fail on Redpanda: the beans ask for `.replicas(1)` and the broker demands 3, so
every create would be refused — non-fatally, exactly the silent failure described above. Creating
the topics here, at the broker's own default replication factor with the declared partition
counts, means `KafkaAdmin` finds them already correct at boot and does nothing at all.

---

## Step 2 — Render environment groups

Create both in the dashboard **before** the first Blueprint sync. `render.yaml` references them
with `fromGroup` and deliberately does not declare them: a Blueprint-declared group cannot hold a
`sync: false` value, and one shared group guarantees every service sees the *same* secret — a
per-service copy that drifts surfaces as a confusing 401 on internal-context verification.

**`paymentflow-shared`**

| Key | Value |
|---|---|
| `PAYMENTFLOW_INTERNAL_CONTEXT_SECRET` | HMAC every service uses to trust a gateway-asserted merchant context |
| `PAYMENTFLOW_WEBHOOK_SECRET_ENCRYPTION_KEY` | webhook signing-secret encryption |

**`paymentflow-kafka`** — from step 1:

| Key | Value |
|---|---|
| `SPRING_KAFKA_BOOTSTRAP_SERVERS` | `host:9092` from Redpanda (Serverless uses 9092) |
| `SPRING_KAFKA_SECURITY_PROTOCOL` | `SASL_SSL` |
| `SPRING_KAFKA_PROPERTIES_SASL_MECHANISM` | `SCRAM-SHA-256` |
| `SPRING_KAFKA_PROPERTIES_SASL_JAAS_CONFIG` | `org.apache.kafka.common.security.scram.ScramLoginModule required username="..." password="...";` |

These four are all the application needs — no code change, no extra properties, and no truststore
(Redpanda's certificate is publicly trusted; `Verify return code: 0`).

Verified end to end against the live Redpanda cluster. Given exactly these four variables and
nothing else, `transaction-service` authenticated, discovered the group coordinator, joined
`transaction-service-payment.events` and took all three partitions; `payment-service` did the same
on `sandbox.scheduled.events` **and logged nothing whatsoever from `KafkaAdmin`** — against the
under-provisioned Aiven broker the same image logged two ERRORs at that point. Zero `KafkaAdmin`
output is the signal that the topics match what the beans declare.

---

## Step 3 — Render Blueprint sync

```bash
render/verify.sh    # Dockerfiles match the root Dockerfile, parse, and select the right module
```

Then point Render at `render.yaml` and sync. It creates one Postgres, one Key Value instance,
eight private services, and two public ones.

**Deploy identity-service first** — everything else validates JWTs against the JWKS it publishes.
Render will bring services up in dependency order, but if you deploy manually, start there.

> `identity-service` is pinned to `numInstances: 1` on purpose. It leaves `private-key`/
> `public-key` unset and mints an ephemeral RSA keypair at startup, so a second replica would
> publish a different JWKS and validation would fail non-deterministically. The same property
> means a restart invalidates every JWT already issued.

Values marked `sync: false` in `render.yaml` must be filled in the dashboard. They are credentials
and future URLs, deliberately not committed.

---

## Step 4 — Developer Portal on Vercel

Vercel, not Render. Root directory `developer-portal/`. Required environment:

| Key | Value |
|---|---|
| `PF_GATEWAY_URL` | the gateway's **public** Render URL |
| `AGENTIC_SERVICE_URL` | the agentic service's **public** Render URL |
| `INTERNAL_CONTEXT_SECRET` | the same value as `PAYMENTFLOW_INTERNAL_CONTEXT_SECRET` |
| `PORTAL_SESSION_SECRET` | 32 bytes, base64 or hex — enforced in production only |
| `PORTAL_PUBLIC_ORIGIN` | the portal's own Vercel origin |
| `PORTAL_ADDITIONAL_ORIGINS` | optional, comma-separated absolute origins |

The portal validates all of these eagerly at boot (`src/lib/env.ts`) and refuses to start if one is
missing — a missing session secret would otherwise generate a new one per process and log every
user out on each deploy, with nothing in the logs to explain it.

Once the portal has an origin, set `PAYMENTFLOW_GATEWAY_CORS_ALLOWED_ORIGINS` on gateway-service to
that origin. CORS is defence in depth here — the portal calls the gateway server-side, so a wrong
value will not obviously break anything.

---

## Step 5 — agentic-commerce-service

Deployed separately, from the same Blueprint, as a **public** `web` service. It is a detachable
extension that sits above the platform: it owns no ledger and no payment correctness, and reaches
PaymentFlow only through the public gateway contract. Nothing in the core depends on it.

It is public rather than private because the portal calls it directly and the portal lives on
Vercel, outside Render's private network — a `pserv` would be unreachable. It is not unprotected:
every route authenticates through `InternalContextFilter`. See the block comment on its service in
`render.yaml` for the alternative if you would rather it were private.

Dashboard values: `SPRING_DATASOURCE_URL`, `PAYMENTFLOW_AGENT_API_KEY` (a real `sk_...` key, minted
through the platform after seeding), and `ANTHROPIC_API_KEY`. A blank LLM key makes the service
fall back to its scripted client, so the demo degrades rather than breaks.

---

## Step 6 — Verify after deploying

1. `render/verify-kafka.sh <bootstrap> <props>` — still `All checks passed`.
2. Check the logs of gateway, identity, merchant, payment, notification and sandbox for
   `Could not configure topics`. **Zero occurrences.** This is the check that catches a broker
   that is too small, and it is the one a green dashboard will not do for you.
3. Drive a payment through the gateway and confirm it appears in the transaction, audit and
   analytics projections — that is the event fabric working end to end.

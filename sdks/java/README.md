# PaymentFlow Java SDK

The official PaymentFlow API client for Java. One dependency-free jar over the public `/v1` API:
typed models, a typed exception hierarchy, automatic idempotency keys that survive a retry,
backoff that reads `Retry-After` instead of guessing, transparent pagination, and webhook
signature verification.

> **Status: publish-ready, not published.** The package is complete, built, and tested, but not
> on Maven Central. Until it is, depend on it from a local build (`./gradlew publishToMavenLocal`)
> or a source checkout. Publishing workflows land in the next milestone.

- **Group / artifact:** `dev.paymentflow:paymentflow`
- **Version:** `0.1.0` — SDK semver, independent of the dated API revision
- **API revision:** generated against `2026-08-01`
- **Java:** 17 or newer

## Install

Once published:

```xml
<dependency>
  <groupId>dev.paymentflow</groupId>
  <artifactId>paymentflow</artifactId>
  <version>0.1.0</version>
</dependency>
```

```kotlin
implementation("dev.paymentflow:paymentflow:0.1.0")
```

Meanwhile, from a checkout of this repo:

```bash
cd sdks/java && ./gradlew publishToMavenLocal   # installs dev.paymentflow:paymentflow:0.1.0 into ~/.m2
```

then add `mavenLocal()` to your repositories.

## First payment

```java
import dev.paymentflow.PaymentFlow;
import dev.paymentflow.model.PaymentResponse;
import dev.paymentflow.resources.Payments;

PaymentFlow client = PaymentFlow.builder()
    .apiKey(System.getenv("PAYMENTFLOW_API_KEY"))   // sk_test_… — server-side only
    .baseUrl("https://api.paymentflow.dev")
    .build();

PaymentResponse payment = client.payments().create(
    Payments.params()
        .amountMinor(1000)          // integer minor units: 1000 = 10.00
        .currency("USD")
        .description("Order A-1234")
        .paymentMethodToken("pm_card_visa"));

PaymentResponse authorized = client.payments().authorize(payment.id());
if ("failed".equals(authorized.status())) {
    throw new IllegalStateException(authorized.failureReason());   // the acquirer's own reason
}
PaymentResponse captured = client.payments().capture(payment.id());

PaymentResponse check = client.payments().retrieve(payment.id());   // -> status "captured"
```

`PaymentFlow.fromEnvironment()` is the same thing with everything read from `PAYMENTFLOW_API_KEY`.

## The namespaces

`client.payments()`, `client.refunds()`, `client.balance()`, `client.balanceTransactions()`,
`client.events()`, `client.analytics()`, `client.usage()`, `client.requestLogs()`,
`client.webhookEndpoints()`, `client.webhookDeliveries()`, `client.testHelpers()`. Each method is
exactly one HTTP request; there is no `createAndCapture`, because a method that makes two
chargeable calls behind one name has failure modes you cannot reason about.

Refunds are created through `client.payments().refund(id, ...)`, which returns the **payment** —
the refund is the newest entry in its `refunds()` list.

## Idempotency

Every payment mutation (`create`, `authorize`, `capture`, `refund`, `void`) is sent with an
`Idempotency-Key`. The SDK generates one **once per call** and reuses it across every retry, so a
retried request is deduplicated rather than charged twice. Supply your own when the retry must
survive your *process* restarting:

```java
client.payments().create(params,
    RequestOptions.builder().idempotencyKey(myDurableKey).build());
```

## Pagination

Iterating a list is already the paginating thing:

```java
for (PaymentResponse payment : client.payments().list(Payments.listParams().status("captured"), null)) {
    // every captured payment, across every page
}
```

A `break` stops making requests there. `page.toList(max)` collects into memory when that is safe
(the cap is required). The M19 lists are `CursorPage`; `/v1/webhook_deliveries` and
`/v1/test/decisions` are `OffsetPage`, which also reports a total.

## Errors

Branch on the exception class, not the status code — a 409 is both a retryable
`IdempotencyException` and a permanent `InvalidRequestException`, and the platform tells them
apart.

| class | when |
|---|---|
| `AuthenticationException` | key missing, malformed, or unrecognised |
| `PermissionException` | valid key, not allowed — wrong scope or mode |
| `InvalidRequestException` | understood and rejected; `param()` / `fieldErrors()` say where |
| `IdempotencyException` | an `Idempotency-Key` conflict; may succeed later |
| `RateLimitException` | rate limit or daily quota; `retryAfterSeconds()` |
| `ApiConnectionException` | no response — DNS, reset, client timeout |
| `ApiException` | a 5xx, or a success this SDK could not read |
| `WebhookSignatureException` / `WebhookTimestampException` / `WebhookPayloadException` | verification failed |

All extend `PaymentFlowException`; catching that alone is a complete handler. Every one carries
`requestId()` — quote it in a support request.

## Retries

`GET`, `DELETE`, and any request with an `Idempotency-Key` are retried on `429` and transient
`5xx` (not `501`). `Retry-After` is honoured up to 60 seconds; a longer one is not slept off
inside your request handler — it is raised as a `RateLimitException` carrying
`retryAfterSeconds()`. Backoff is full-jitter, 500 ms doubling to 8 s. Default budget is 3
retries; override per client (`.maxRetries(n)`) or per call.

## Webhooks

`Webhooks` is a static utility — a receiver often holds no API key and should not have to build
a client:

```java
import dev.paymentflow.Webhooks;
import dev.paymentflow.WebhookEvent;

WebhookEvent event = Webhooks.constructEvent(rawBodyBytes, signatureHeaderValue, whsecSecret);
switch (event.type()) {
    case "payment.captured" -> ...
    default -> { /* ignore unknown types */ }
}
```

Pass the **raw** bytes — parsing then re-serializing the JSON changes what the signature covers.
Verified against the same vectors the platform's own signer uses. Dedupe on `event.id()`.

## Building this SDK

```bash
cd sdks/java
./gradlew build                # compile, test, javadoc, jars, compile the examples
./gradlew publishToMavenLocal  # stage the artifact into ~/.m2 for inspection
```

`ContractParityTest` asserts the generated-equivalent layer (`Contract`, `Operations`,
`Vocabularies`, the response records) against `../shared/fixtures/*.json` — the same
language-neutral fixtures the Node and Python SDKs assert against.

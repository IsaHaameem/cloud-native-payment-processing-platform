"""Error handling.

Run: PAYMENTFLOW_API_KEY=sk_test_… python examples/04_error_handling.py

Catching ``PaymentFlowError`` alone is already a complete, correct handler. Everything below is
narrowing from there, for cases where you can do something more useful than log.
"""

from __future__ import annotations

from paymentflow import (
    ApiConnectionError,
    ApiError,
    AuthenticationError,
    IdempotencyError,
    InvalidRequestError,
    PaymentFlow,
    PaymentFlowError,
    PermissionDeniedError,
    RateLimitError,
)


def capture(client: PaymentFlow, payment_id: str) -> None:
    try:
        payment = client.payments.capture(payment_id)
        print(f"captured {payment.get('id')}")

    except RateLimitError as error:
        # The SDK already waited out anything short. Reaching here means the interval was
        # longer than it will block for — an exhausted daily quota clears at 00:00 UTC — so
        # schedule the work instead of retrying now.
        print(f"rate limited; retry in {error.retry_after_seconds}s")

    except IdempotencyError as error:
        # Distinct from InvalidRequestError despite sharing a 409: a concurrent request is
        # holding the same key, so this one *may* succeed later.
        print(f"idempotency conflict ({error.code}); safe to try again shortly")

    except InvalidRequestError as error:
        # A validation failure, an unknown id, or a state the payment cannot move from. It
        # will be rejected identically however many times it is sent.
        print(f"rejected: {error.code} {error.message}")
        if error.param is not None:
            print(f"  offending parameter: {error.param}")
        for field in error.field_errors:
            print(f"  {field.get('field')}: {field.get('message')}")
        if error.doc_url is not None:
            print(f"  {error.doc_url}")

    except AuthenticationError:
        print("the API key is missing, malformed, or revoked")

    except PermissionDeniedError as error:
        # Usually a missing scope, or a test key reaching for live data.
        print(f"not permitted: {error.message}")

    except ApiConnectionError as error:
        # No response at all, so whether the request took effect is genuinely unknown. This is
        # exactly why mutations carry an idempotency key: retrying with the same one is safe.
        print(f"no response after {error.attempts} attempt(s): {error.message}")

    except ApiError as error:
        # Not your fault. Quote the request id.
        print(f"platform error {error.status_code}, request {error.request_id}")

    except PaymentFlowError as error:
        print(f"{type(error).__name__}: {error.message} (request {error.request_id})")


def main() -> None:
    with PaymentFlow() as client:
        capture(client, "pay_does_not_exist")

        # Every response carries the identifiers needed to trace it, whether or not it failed —
        # `request_id` keys the matching row of `client.request_logs.list()`.
        page = client.payments.list(limit=1)
        print(f"request {page.meta.request_id}, revision {page.meta.api_version}")
        if page.meta.rate_limit is not None:
            print(f"quota: {page.meta.rate_limit.remaining} of {page.meta.rate_limit.limit} left")
        if page.meta.deprecated:
            print("this API revision is deprecated; see the Sunset header")


if __name__ == "__main__":
    main()

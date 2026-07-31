"""A webhook receiver, using only Python's standard library.

Run: PAYMENTFLOW_WEBHOOK_SECRET=whsec_… python examples/05_webhook_receiver.py

No framework, on purpose — the one thing that matters here is that the verification runs
against the **raw request body**, and every framework has its own way of getting in the way of
that. In Flask it is ``request.get_data()``, not ``request.get_json()``; in FastAPI,
``await request.body()``. ``json.loads`` followed by ``json.dumps`` does not round-trip bytes,
so a re-serialized body fails a signature that was perfectly valid.

Note there is no API key here. Verification needs the endpoint's signing secret and nothing
else, so a receiver never has to hold a secret key it would not otherwise need.
"""

from __future__ import annotations

import os
from http.server import BaseHTTPRequestHandler, HTTPServer
from typing import Set

from paymentflow import (
    SIGNATURE_HEADER,
    WebhookSignatureError,
    WebhookTimestampError,
    WebhookVerificationError,
    construct_event,
)

SECRET = os.environ.get("PAYMENTFLOW_WEBHOOK_SECRET", "")

#: Deliveries repeat — after a retry that actually succeeded, after a manual replay, or during
#: a partition. ``event["id"]`` is stable across all of those.
SEEN: Set[str] = set()


class Receiver(BaseHTTPRequestHandler):
    def do_POST(self) -> None:  # noqa: N802  (the stdlib's naming, not ours)
        length = int(self.headers.get("Content-Length", "0"))
        # Read as bytes and never decoded or parsed until it has been verified.
        body = self.rfile.read(length)
        signature = self.headers.get(SIGNATURE_HEADER, "")

        try:
            event = construct_event(body, signature, SECRET)
        except WebhookTimestampError as error:
            # A valid signature arriving late is a replayed delivery, or a clock that is wrong.
            # Worth distinguishing: one is an attack and the other is NTP.
            print(f"stale delivery, {error.skew_seconds}s of skew")
            self._reply(400, "stale")
            return
        except WebhookSignatureError as error:
            # This did not come from PaymentFlow, or did not arrive intact. Do not act on it.
            print(f"rejected: {error}")
            self._reply(400, "bad signature")
            return
        except WebhookVerificationError as error:
            print(f"verified but unusable: {error}")
            self._reply(400, "bad payload")
            return

        event_id = str(event["id"])
        if event_id in SEEN:
            self._reply(200, "duplicate")
            return
        SEEN.add(event_id)

        # Answer quickly, then do the work. Anything slower than 5 seconds counts as a failed
        # attempt and enters the retry schedule.
        self._reply(200, "ok")

        event_type = str(event["type"])
        resource = event["data"].get("object", {})
        if event_type == "payment.captured":
            print(f"captured {resource.get('id')} for {resource.get('amountMinor')}")
        elif event_type == "payment.failed":
            print(f"failed {resource.get('id')}")
        else:
            # New event types ship without a new API revision, so ignore what you do not know
            # rather than erroring on it.
            print(f"ignoring {event_type}")

    def _reply(self, status: int, body: str) -> None:
        payload = body.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, format: str, *args: object) -> None:
        """Silenced: this example prints what it decided, not every request line."""


def main() -> None:
    print("listening on :4242")
    HTTPServer(("", 4242), Receiver).serve_forever()


if __name__ == "__main__":
    main()

"""
Independent Python implementation of the PaymentFlow webhook signature (M18.4, D105/D136).

Written from the *specification* — not ported from the Java code — so that agreement
between the two is evidence the spec is implementable by a third party, which is the only
thing that actually protects an integrator. M22's Python SDK helper is expected to consume
this same vector file as its own fixture, so a divergence there fails loudly rather than
being discovered by a merchant.

Run:  python verify.py
Exits non-zero if any vector disagrees.
"""

import hashlib
import hmac
import json
import pathlib
import sys

DOC = json.loads(
    (pathlib.Path(__file__).parent / "webhook-signature-vectors.json").read_text(encoding="utf-8")
)


def sign(secret: str, timestamp: int, body: str) -> str:
    """signed_payload = "{timestamp}.{body}"; v1 = lowercase hex HMAC-SHA256(secret, signed_payload)."""
    payload = f"{timestamp}.{body}".encode("utf-8")
    return hmac.new(secret.encode("utf-8"), payload, hashlib.sha256).hexdigest()


def verify_header(body: str, header: str, secret: str, now_epoch: int, tolerance: int) -> bool:
    """What a merchant's own receiver would do: parse, recompute, compare in constant time."""
    timestamp = None
    candidates = []
    for part in header.split(","):
        key, _, value = part.strip().partition("=")
        if key == "t":
            try:
                timestamp = int(value)
            except ValueError:
                return False
        elif key == "v1":
            candidates.append(value)

    if timestamp is None or not candidates:
        return False
    if abs(now_epoch - timestamp) > tolerance:
        return False

    expected = sign(secret, timestamp, body)
    return any(hmac.compare_digest(expected, candidate) for candidate in candidates)


failures = 0

for vector in DOC["vectors"]:
    actual = sign(vector["secret"], vector["timestamp"], vector["body"])
    ok = actual == vector["expectedV1"]
    failures += 0 if ok else 1
    print(f"{'PASS' if ok else 'FAIL'}  {vector['name']}")
    if not ok:
        print(f"      expected {vector['expectedV1']}")
        print(f"      actual   {actual}")

# The replay window is the property the timestamp exists to enforce (D105) — assert it,
# rather than only asserting the hash matches.
v = DOC["vectors"][0]
header = f"t={v['timestamp']},v1={v['expectedV1']}"
checks = [
    ("accepts a signature inside the tolerance window", True,
     verify_header(v["body"], header, v["secret"], v["timestamp"] + 60, 300)),
    ("rejects a replayed signature outside the window", False,
     verify_header(v["body"], header, v["secret"], v["timestamp"] + 8000, 300)),
    ("rejects a signature under the wrong secret", False,
     verify_header(v["body"], header, "whsec_NotTheRightSecretAtAll", v["timestamp"], 300)),
    ("rejects a tampered body", False,
     verify_header(v["body"] + " ", header, v["secret"], v["timestamp"], 300)),
]
for name, expected, actual in checks:
    ok = expected == actual
    failures += 0 if ok else 1
    print(f"{'PASS' if ok else 'FAIL'}  {name}")

print("\nAll vectors agree (Python)." if failures == 0 else f"\n{failures} failure(s) (Python).")
sys.exit(0 if failures == 0 else 1)

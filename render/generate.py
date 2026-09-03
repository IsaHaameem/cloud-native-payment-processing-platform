#!/usr/bin/env python3
"""
Regenerates render/<service>.Dockerfile from the repository-root Dockerfile.

Why these files exist at all
----------------------------
The root Dockerfile is one parameterised recipe for every service in the monorepo. It takes
`SERVICE_MODULE` and `SERVICE_PORT` as build args and docker-compose.yml supplies them, one
service block per module.

Render's Docker builder takes a Dockerfile path and a build context and offers no way to pass
`--build-arg`. Pointed at the root Dockerfile, it would run `./gradlew ":bootJar"` with an empty
module name and fail before compiling anything.

So each file here is the root Dockerfile with exactly two lines changed -- the two ARG
declarations, given defaults -- plus a header. Nothing else differs, and nothing here is
maintained by hand: change the root Dockerfile, re-run this, commit the result. `render/verify.sh`
fails if the two ever drift.

Usage
-----
    python render/generate.py            # rewrite the five files
    python render/generate.py --check    # exit 1 if any file is stale, write nothing
"""

from __future__ import annotations

import argparse
import pathlib
import sys

# (filename stem, Gradle module, server port) -- ports match each service's application.yaml.
SERVICES: list[tuple[str, str, int]] = [
    # Core synchronous path.
    ("gateway", "gateway-service", 8080),
    ("identity", "identity-service", 8081),
    ("merchant", "merchant-service", 8082),
    ("payment", "payment-service", 8083),
    ("sandbox", "sandbox-service", 8094),
    # Kafka consumers -- the asynchronous projections.
    ("transaction", "transaction-service", 8084),
    ("audit", "audit-service", 8091),
    ("notification", "notification-service", 8092),
    ("analytics", "analytics-service", 8093),
    # Detachable extension, above the platform rather than inside it.
    ("agentic", "agentic-commerce-service", 8095),
]

MODULE_ARG = "ARG SERVICE_MODULE\n"
PORT_ARG = "ARG SERVICE_PORT\n"

HEADER = """\
# GENERATED -- do not edit by hand. Source of truth: ../Dockerfile
#
# Render's Docker builder accepts a Dockerfile path and a context but has no way to pass
# `--build-arg`, and ../Dockerfile leaves SERVICE_MODULE and SERVICE_PORT without defaults
# (docker-compose.yml supplies them per service). A build from ../Dockerfile on Render would
# therefore run `./gradlew ":bootJar"` against an empty module name and fail. This file is
# ../Dockerfile with those two ARGs defaulted for {module}, and nothing else changed.
#
# Regenerate every file in this directory after any change to ../Dockerfile:
#
#   python render/generate.py
#
# render/verify.sh asserts these stay byte-identical to ../Dockerfile apart from this
# header and the two ARG lines.
#
"""


def render(root_dockerfile: str, module: str, port: int) -> str:
    """The root Dockerfile with the two ARGs defaulted. Fails loudly if either is missing."""
    if MODULE_ARG not in root_dockerfile:
        raise SystemExit(
            "render/generate.py: %r not found in the root Dockerfile.\n"
            "It was probably given a default or renamed. Fix this script rather than "
            "hand-editing the generated files." % MODULE_ARG.strip()
        )
    if PORT_ARG not in root_dockerfile:
        raise SystemExit(
            "render/generate.py: %r not found in the root Dockerfile." % PORT_ARG.strip()
        )

    body = root_dockerfile.replace(
        MODULE_ARG, "ARG SERVICE_MODULE=%s\n" % module, 1
    ).replace(PORT_ARG, "ARG SERVICE_PORT=%d\n" % port, 1)
    return HEADER.format(module=module) + body


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--check",
        action="store_true",
        help="verify the generated files are current; write nothing, exit 1 on drift",
    )
    args = parser.parse_args()

    repo_root = pathlib.Path(__file__).resolve().parent.parent
    root_dockerfile = (repo_root / "Dockerfile").read_text(encoding="utf-8")

    stale: list[str] = []
    for stem, module, port in SERVICES:
        target = repo_root / "render" / ("%s.Dockerfile" % stem)
        expected = render(root_dockerfile, module, port)

        if args.check:
            actual = target.read_text(encoding="utf-8") if target.exists() else None
            if actual != expected:
                stale.append(target.name)
                print("  STALE   %s" % target.name)
            else:
                print("  ok      %s" % target.name)
        else:
            target.write_text(expected, encoding="utf-8", newline="")
            print("  wrote   %-24s SERVICE_MODULE=%-24s SERVICE_PORT=%d" % (target.name, module, port))

    if args.check and stale:
        print(
            "\n%d file(s) no longer match the root Dockerfile. Run `python render/generate.py`."
            % len(stale),
            file=sys.stderr,
        )
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

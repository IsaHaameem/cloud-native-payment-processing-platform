"""What this package promises an integrator, and what it deliberately does not.

The mirror of ``sdks/node/test/public-surface.test.mjs``. The two SDKs are meant to be
equivalent, and that has to include what they *decline* to expose: an SDK whose public surface
is whatever the code generator happened to name is one whose API changes every time the
generator is refactored.
"""

from __future__ import annotations

import importlib
import json
from pathlib import Path

import paymentflow

PACKAGE_ROOT = Path(__file__).resolve().parents[1]


#: The response models this package re-exports by name (D172). Types only: a caller has to be
#: able to name the object a method returned, but what they may name is a decision made in
#: ``__init__``, not one made by whatever the generator happened to call a schema.
RE_EXPORTED_MODELS = [
    "AnalyticsBucketResponse",
    "AnalyticsSummaryResponse",
    "ApiFieldError",
    "BalanceResponse",
    "BalanceTransactionResponse",
    "CurrencyBalance",
    "DecisionLogEntryResponse",
    "EventResponse",
    "PaymentResponse",
    "RefundResponse",
    "RequestLogResponse",
    "SimulationOverrideResponse",
    "TestCardResponse",
    "UsageBucketResponse",
    "UsageSummaryResponse",
    "WebhookDeliveryAttemptResponse",
    "WebhookDeliveryResponse",
    "WebhookEndpointCreatedResponse",
    "WebhookEndpointResponse",
]


def test_the_public_surface_is_exactly_what_dunder_all_names() -> None:
    # An exact list, not a subset. The point is to make growing the public API a deliberate
    # edit to ``__init__`` rather than a side effect of an import added somewhere else.
    assert sorted(paymentflow.__all__) == sorted(
        [
            "PaymentFlow",
            "API_VERSION",
            "DEFAULT_BASE_URL",
            "USER_AGENT",
            "VERSION",
            "PaymentFlowConfigurationError",
            "RateLimitMeta",
            "RequestOptions",
            "ResponseMeta",
            "CursorPage",
            "OffsetPage",
            "Page",
            "ApiConnectionError",
            "ApiError",
            "AuthenticationError",
            "IdempotencyError",
            "InvalidRequestError",
            "PaymentFlowError",
            "PermissionDeniedError",
            "RateLimitError",
            "WebhookPayloadError",
            "WebhookSignatureError",
            "WebhookTimestampError",
            "WebhookVerificationError",
            "DEFAULT_TOLERANCE_SECONDS",
            "SIGNATURE_HEADER",
            "construct_event",
            "sign_payload",
            "signature_header_for",
            "webhooks",
        ]
        + RE_EXPORTED_MODELS
    )

    # Everything named is actually importable. ``__all__`` is a list of strings, so a typo in
    # it is invisible until a user writes ``from paymentflow import *`` and gets an
    # AttributeError from inside the package.
    for name in paymentflow.__all__:
        assert hasattr(paymentflow, name), f"__all__ names {name}, which the package does not define"


def test_every_resource_namespace_named_by_the_milestone_exists_on_a_client() -> None:
    client = paymentflow.PaymentFlow(api_key="sk_test_surface")

    # The eleven the milestone specifies, in Python's spelling, plus the webhooks namespace.
    # A namespace renamed or dropped is a breaking change to every integrator at once, and is
    # not the sort of thing to notice in review.
    for name in [
        "payments",
        "refunds",
        "balance",
        "balance_transactions",
        "events",
        "analytics",
        "request_logs",
        "usage",
        "webhook_endpoints",
        "webhook_deliveries",
        "test_helpers",
        "webhooks",
    ]:
        assert hasattr(client, name), f"client.{name} exists"


def test_the_error_hierarchy_is_exported_as_classes_a_caller_can_branch_on() -> None:
    # Every one has to be a real class reachable from the package root, because the way a
    # caller distinguishes "fix your key" from "retry later" is ``except``.
    for name in [
        "PaymentFlowError",
        "AuthenticationError",
        "PermissionDeniedError",
        "InvalidRequestError",
        "IdempotencyError",
        "RateLimitError",
        "ApiConnectionError",
        "ApiError",
        "WebhookVerificationError",
        "WebhookSignatureError",
        "WebhookTimestampError",
        "WebhookPayloadError",
    ]:
        cls = getattr(paymentflow, name)
        assert isinstance(cls, type)
        assert issubclass(cls, paymentflow.PaymentFlowError), f"{name} narrows from PaymentFlowError"


def test_the_permission_error_does_not_shadow_the_builtin() -> None:
    # Python has a builtin ``PermissionError``. Exporting one of ours under that name would
    # mean ``from paymentflow import PermissionError`` silently stops a module catching
    # filesystem errors — a trap the Node SDK cannot have, which is why the two differ here
    # (D178).
    assert "PermissionError" not in paymentflow.__all__
    assert not hasattr(paymentflow, "PermissionError")
    assert paymentflow.PermissionDeniedError.__name__ == "PermissionDeniedError"


def test_nothing_generated_leaks_into_the_public_surface() -> None:
    generated_models = importlib.import_module("paymentflow._generated.models")
    generated_operations = importlib.import_module("paymentflow._generated.operations")

    generated_names = {
        name
        for module in (generated_models, generated_operations)
        for name in vars(module)
        if not name.startswith("_")
    }

    # The generated tree is real and non-empty — asserted first, so this test cannot pass by
    # comparing the public surface against nothing.
    assert "OPERATIONS" in generated_names
    assert len(generated_names) > 3

    # No generated *runtime value* is reachable from the package root. This is the half of the
    # rule that actually protects the API: the operation table and the enum value tuples are
    # implementation details, and a caller who reached for them would be depending on the
    # generator's naming.
    for name in ["OPERATIONS", "OperationDescriptor"] + [n for n in generated_names if n.endswith("_VALUES")]:
        assert name not in paymentflow.__all__, f"{name} is a generated value and is not public API"

    # The generated *types* that are re-exported are exactly the curated list, and every one of
    # them really does come from the generated models — so this cannot pass by listing names
    # that no longer exist.
    for name in RE_EXPORTED_MODELS:
        assert name in generated_names, f"{name} is listed as a re-exported model and is not one"
        assert name in paymentflow.__all__

    # And nothing generated is public *except* the curated list and the two identity constants.
    chosen = set(RE_EXPORTED_MODELS) | {"API_VERSION", "DEFAULT_BASE_URL"}
    for name in paymentflow.__all__:
        if name in chosen:
            continue
        if name == "ApiError":
            # The exception class shares a name with the generated error-body TypedDict. The
            # collision is deliberate — §7.1 names the class ``ApiError`` in all four languages
            # — and it resolves to the class, which is what the next assertion pins.
            assert issubclass(paymentflow.ApiError, paymentflow.PaymentFlowError)
            continue
        assert name not in generated_names, f"{name} is exported straight from the generated tree"


def test_the_generated_package_re_exports_nothing() -> None:
    import types

    generated = importlib.import_module("paymentflow._generated")

    # A generated ``__init__`` that re-exported its modules' contents would make every
    # regeneration a change to something importable, which is the opposite of the rule that
    # generated code is an implementation detail.
    #
    # Submodules are excluded, and not as a convenience: importing ``a.b`` binds ``b`` on
    # ``a`` as a language rule, so a package that imports nothing still grows those attributes
    # the moment anything reaches into it. What is asserted is that the ``__init__`` itself
    # defines no name — no ``__all__``, and nothing that is not a module Python bound there.
    # `annotations` is the same kind of artefact: `from __future__ import annotations` binds a
    # `__future__._Feature` under that name in every module that uses it, and every module here
    # does, because that is what keeps the annotations lazy on the oldest supported Python.
    assert not hasattr(generated, "__all__")
    assert [
        name
        for name, value in vars(generated).items()
        if not name.startswith("_")
        and name != "annotations"
        and not isinstance(value, types.ModuleType)
    ] == []


def test_the_package_version_and_the_manifest_agree() -> None:
    pyproject = (PACKAGE_ROOT / "pyproject.toml").read_text(encoding="utf-8")

    # Two places, one fact. The build backend reads the manifest, the User-Agent reads the
    # constant, and a release where they disagree publishes an SDK that misreports itself in
    # every row of the request log M20 records.
    assert f'version = "{paymentflow.VERSION}"' in pyproject
    assert paymentflow.USER_AGENT.startswith(f"paymentflow-python/{paymentflow.VERSION} ")


def test_the_typed_marker_ships_with_the_package() -> None:
    # PEP 561: without this file a consumer's type checker ignores every annotation in the
    # package and treats the whole SDK as ``Any``. It is one empty file and it is the entire
    # difference between "typed" and "has type hints nobody can see".
    assert (PACKAGE_ROOT / "src" / "paymentflow" / "py.typed").is_file()

    wheel_packages = json.dumps(
        [
            line
            for line in (PACKAGE_ROOT / "pyproject.toml").read_text(encoding="utf-8").splitlines()
            if "packages" in line
        ]
    )
    assert "src/paymentflow" in wheel_packages

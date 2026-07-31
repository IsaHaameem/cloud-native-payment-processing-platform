"""The Python half of the cross-language parity check.

``sdks/shared/fixtures`` is written by the same generator that writes both SDKs' models, and
both SDKs assert against it. This file is the mirror of ``sdks/node/test/parity.test.mjs``:
same fixtures, same facts, same assertions, so that "Node and Python describe the same
contract" is a test rather than a claim.

Asserting against the fixture rather than against Node directly is deliberate. A test that
shelled out to the other language would need both toolchains present to run either SDK's
suite, which is the prerequisite D136 keeps out of this repository.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any, Dict, List

import pytest

import paymentflow
from paymentflow._generated import models, operations

FIXTURES = Path(__file__).resolve().parents[2] / "shared" / "fixtures"


def _read(name: str) -> Dict[str, Any]:
    data: Dict[str, Any] = json.loads((FIXTURES / name).read_text(encoding="utf-8"))
    return data


CONTRACT = _read("contract.json")
ENUMS = _read("enums.json")
MODELS = _read("models.json")
OPERATIONS = _read("operations.json")


def test_the_fixtures_describe_a_contract_not_an_empty_file() -> None:
    # A parity suite whose fixtures silently emptied would pass every assertion below by
    # iterating over nothing. Checking the counts first is what stops this file being able to
    # pass by failing to do its job.
    assert CONTRACT["operationCount"] > 0
    assert CONTRACT["modelCount"] > 0
    assert CONTRACT["enumCount"] > 0
    assert len(OPERATIONS) == CONTRACT["operationCount"]
    assert len(MODELS) == CONTRACT["modelCount"]
    assert len(ENUMS) == CONTRACT["enumCount"]


def test_the_sdk_reports_the_api_revision_the_contract_names() -> None:
    assert paymentflow.API_VERSION == CONTRACT["apiVersion"]
    assert paymentflow.DEFAULT_BASE_URL == CONTRACT["baseUrl"]


def test_every_published_operation_is_addressable_with_the_same_shape() -> None:
    assert sorted(operations.OPERATIONS) == sorted(OPERATIONS)

    for operation_id, expected in OPERATIONS.items():
        actual = operations.OPERATIONS[operation_id]
        assert actual["method"] == expected["method"], f"{operation_id} method"
        assert actual["path"] == expected["path"], f"{operation_id} path"
        assert actual["tag"] == expected["tag"], f"{operation_id} tag"
        assert actual["success_status"] == expected["successStatus"], f"{operation_id} success status"
        assert actual["has_request_body"] == expected["hasRequestBody"], f"{operation_id} request body"
        assert list(actual["query_parameters"]) == expected["queryParameters"], operation_id


def _constant_name(alias: str) -> str:
    """``PaymentResponseMode`` -> ``PAYMENT_RESPONSE_MODE_VALUES``.

    The same derivation the TypeScript emitter uses, so a rename in one language fails the
    other language's suite too.
    """
    out: List[str] = []
    for index, character in enumerate(alias):
        if character.isupper() and index and (alias[index - 1].islower() or alias[index - 1].isdigit()):
            out.append("_")
        out.append(character.upper())
    return "".join(out) + "_VALUES"


@pytest.mark.parametrize("alias", sorted(ENUMS))
def test_every_enum_vocabulary_matches_the_contract(alias: str) -> None:
    constant = _constant_name(alias)
    assert hasattr(models, constant), f"{constant} is exported"
    assert list(getattr(models, constant)) == ENUMS[alias]


def test_every_model_declares_the_fields_the_contract_publishes() -> None:
    for name, fields in MODELS.items():
        model = getattr(models, name, None)
        assert model is not None, f"{name} is generated"
        # ``__annotations__`` is what a TypedDict records, and what a type checker reads. It
        # is also the only runtime evidence that the generated file describes the same object
        # the fixture does — a TypedDict is a plain dict at runtime and carries nothing else.
        assert sorted(model.__annotations__) == sorted(fields), name


def test_the_error_classification_is_a_vocabulary_both_sdks_can_map_to_exception_classes() -> None:
    # The single most load-bearing enum in either SDK: §7.1's typed error hierarchy branches
    # on it. M22.0 published it as a real enum so this could be checked rather than
    # transcribed.
    assert ENUMS["ApiErrorType"] == [
        "authentication_error",
        "permission_error",
        "invalid_request_error",
        "idempotency_error",
        "rate_limit_error",
        "api_error",
    ]

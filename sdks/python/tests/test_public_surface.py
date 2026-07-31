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


def test_the_public_surface_is_exactly_what_dunder_all_names() -> None:
    # An exact list, not a subset. The point is to make growing the public API a deliberate
    # edit to ``__init__`` rather than a side effect of an import added somewhere else.
    assert sorted(paymentflow.__all__) == [
        "API_VERSION",
        "DEFAULT_BASE_URL",
        "USER_AGENT",
        "VERSION",
    ]


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

    # ``API_VERSION`` and ``DEFAULT_BASE_URL`` are values this package re-exports under names
    # it chose; everything else generated stays behind the underscore.
    chosen = {"API_VERSION", "DEFAULT_BASE_URL"}
    for name in paymentflow.__all__:
        if name in chosen:
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

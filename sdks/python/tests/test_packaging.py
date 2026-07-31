"""What actually ships (M22.7).

The mirror of ``sdks/node/test/packaging.test.mjs``. Everything here asks a question that can
only be answered about the *distribution*, not about the source tree: what does a wheel
contain, does an sdist build at all, and is the ``py.typed`` marker inside the artefact rather
than merely next to the code.

Those failures are invisible from ``src/`` and total from a consumer's side — a wheel missing
``py.typed`` installs cleanly and silently turns every annotation in this package into ``Any``.

**Nothing here publishes.** ``python -m build`` writes to a temporary directory and the
artefacts are inspected and discarded. Publishing to a public index is irreversible and
effectively claims a name, so it needs an explicit decision rather than a passing build.
"""

from __future__ import annotations

import subprocess
import sys
import tarfile
import zipfile
from pathlib import Path
from typing import Dict, List

import pytest

import paymentflow

PACKAGE_ROOT = Path(__file__).resolve().parents[1]
MANIFEST = (PACKAGE_ROOT / "pyproject.toml").read_text(encoding="utf-8")


@pytest.fixture(scope="module")
def distributions(tmp_path_factory: pytest.TempPathFactory) -> List[Path]:
    """Builds a wheel and an sdist into a throwaway directory.

    Module-scoped because the build is the slow part and every assertion below reads the same
    two artefacts. ``python -m build`` is the PEP 517 front end, so this exercises the real
    ``hatchling`` backend rather than a hand-rolled approximation of it.
    """
    output = tmp_path_factory.mktemp("dist")
    result = subprocess.run(
        [sys.executable, "-m", "build", "--outdir", str(output), str(PACKAGE_ROOT)],
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        pytest.fail(f"`python -m build` failed:\n{result.stdout}\n{result.stderr}")
    return sorted(output.iterdir())


def test_the_manifest_refuses_publication() -> None:
    # `Private :: Do Not Upload` is a classifier PyPI rejects outright, so an accidental
    # `twine upload` fails at the index rather than succeeding irreversibly.
    assert "Private :: Do Not Upload" in MANIFEST


def test_the_package_has_exactly_one_runtime_dependency() -> None:
    # A payments SDK that drags in a transitive tree is a supply-chain liability for every
    # integrator. §7.2 makes this a design rule, and this is what keeps it one.
    dependencies = MANIFEST.split("dependencies = [")[1].split("]")[0]
    assert [line.strip().strip('",') for line in dependencies.strip().splitlines()] == ["httpx>=0.27"]


def test_the_floor_is_still_the_one_the_package_claims() -> None:
    assert 'requires-python = ">=3.9"' in MANIFEST


def test_both_distributions_build(distributions: List[Path]) -> None:
    names = [path.name for path in distributions]
    assert any(name.endswith(".whl") for name in names), f"a wheel was built: {names}"
    assert any(name.endswith(".tar.gz") for name in names), f"an sdist was built: {names}"

    # The version in the artefact name is the version the package reports. A release where
    # those disagree ships an SDK that misreports itself in every row of the request log.
    assert all(paymentflow.VERSION in name for name in names), names


def test_the_wheel_ships_the_package_the_generated_tree_and_py_typed(
    distributions: List[Path],
) -> None:
    wheel = next(path for path in distributions if path.suffix == ".whl")
    with zipfile.ZipFile(wheel) as archive:
        contents = archive.namelist()

    assert "paymentflow/__init__.py" in contents
    assert "paymentflow/_client.py" in contents
    assert "paymentflow/_transport.py" in contents
    assert "paymentflow/_webhooks.py" in contents
    assert "paymentflow/resources/payments.py" in contents

    # The generated models are an implementation detail and still have to ship — they are what
    # the annotations refer to, so a wheel without them installs a package whose types point at
    # modules that do not exist.
    assert "paymentflow/_generated/models.py" in contents
    assert "paymentflow/_generated/operations.py" in contents

    # PEP 561. Without this file a consumer's type checker ignores every annotation here and
    # treats the whole SDK as `Any` — the entire difference between "typed" and "has type hints
    # nobody can see".
    assert "paymentflow/py.typed" in contents


def test_the_wheel_ships_no_tests_examples_or_caches(distributions: List[Path]) -> None:
    wheel = next(path for path in distributions if path.suffix == ".whl")
    with zipfile.ZipFile(wheel) as archive:
        contents = archive.namelist()

    # `packages = ["src/paymentflow"]` is an allow-list, so this is really a test that nobody
    # has widened it. Shipping the tests doubles the install and invites a consumer to import
    # past the public surface.
    unwanted = [
        name
        for name in contents
        if name.startswith(("tests/", "examples/"))
        or "__pycache__" in name
        or name.endswith((".pyc", ".pyi.bak"))
    ]
    assert unwanted == [], f"only the package ships: {unwanted}"


def test_the_sdist_carries_the_readme_the_manifest_and_the_sources(
    distributions: List[Path],
) -> None:
    sdist = next(path for path in distributions if path.name.endswith(".tar.gz"))
    with tarfile.open(sdist) as archive:
        contents = [name.split("/", 1)[1] for name in archive.getnames() if "/" in name]

    # The README is the package's front page on any index that renders one, and `pyproject.toml`
    # declares it — an sdist without it fails to build from source with a confusing error about
    # a missing readme rather than an obvious one about a missing file.
    assert "README.md" in contents
    assert "pyproject.toml" in contents
    assert "src/paymentflow/__init__.py" in contents
    assert "src/paymentflow/py.typed" in contents


def test_the_wheel_installs_and_imports_in_a_clean_interpreter(
    distributions: List[Path], tmp_path: Path
) -> None:
    wheel = next(path for path in distributions if path.suffix == ".whl")
    target = tmp_path / "site"

    # Installed with `--target` and no dependencies, then imported with *only* that directory
    # and httpx's location on the path. This is the check that a consumer's `pip install`
    # produces something importable — a package that works from `src/` because pytest put it on
    # the path can still be broken as an installed artefact.
    install = subprocess.run(
        [sys.executable, "-m", "pip", "install", "--quiet", "--no-deps", "--target", str(target), str(wheel)],
        capture_output=True,
        text=True,
    )
    assert install.returncode == 0, install.stderr

    program = (
        "import paymentflow, sys;"
        "assert paymentflow.PaymentFlow is not None;"
        "assert paymentflow.construct_event is not None;"
        "c = paymentflow.PaymentFlow(api_key='sk_test_installed');"
        "assert c.payments is not None and c.webhook_endpoints is not None;"
        "print(paymentflow.VERSION)"
    )
    run = subprocess.run(
        [sys.executable, "-c", program],
        capture_output=True,
        text=True,
        cwd=str(tmp_path),
        env={**_clean_env(), "PYTHONPATH": str(target)},
    )
    assert run.returncode == 0, f"{run.stdout}\n{run.stderr}"
    assert run.stdout.strip() == paymentflow.VERSION


def _clean_env() -> Dict[str, str]:
    """The current environment, minus anything that would put the source tree on the path."""
    import os

    env = {key: value for key, value in os.environ.items() if key != "PYTHONPATH"}
    return env

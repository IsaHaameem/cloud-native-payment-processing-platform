"""Proves the package really runs on the oldest Python it claims to support.

``requires-python = ">=3.9"`` is a promise made to every integrator on an older runtime, and
§7.2 makes it deliberate: an SDK runs in its users' environments, not in ours. Nothing else in
this repository checks it. mypy cannot — current mypy refuses ``python_version = "3.9"``
outright — and it would be the wrong tool anyway: what breaks at a version floor is syntax and
runtime API, not types. A generated module that used a newer construct would import fine here,
on 3.13, and fail on the first line for the users the floor exists for.

So the check is made against the grammar itself. ``ast.parse`` takes a ``feature_version``,
which makes CPython's own parser reject anything the target version could not parse — the same
parser, the same answer, no second implementation to keep in step.
"""

from __future__ import annotations

import ast
import re
from pathlib import Path
from typing import List, Tuple

import pytest

PACKAGE = Path(__file__).resolve().parents[1] / "src" / "paymentflow"

#: Read from the manifest rather than written here, so the floor has one declaration. A test
#: that hardcoded 3.9 would keep passing after someone raised or lowered `requires-python`.
_MANIFEST = (PACKAGE.parents[1] / "pyproject.toml").read_text(encoding="utf-8")
_FLOOR_MATCH = re.search(r'requires-python\s*=\s*">=(\d+)\.(\d+)"', _MANIFEST)
assert _FLOOR_MATCH is not None, "pyproject.toml no longer declares requires-python as >=X.Y"
FLOOR: Tuple[int, int] = (int(_FLOOR_MATCH.group(1)), int(_FLOOR_MATCH.group(2)))

MODULES: List[Path] = sorted(PACKAGE.rglob("*.py"))


def test_there_are_modules_to_check() -> None:
    # The guard on the guard. A glob that matched nothing would make every parametrised case
    # below vanish and the suite still report green.
    assert len(MODULES) >= 5


@pytest.mark.parametrize("module", MODULES, ids=lambda path: path.name)
def test_every_shipped_module_parses_under_the_declared_floor(module: Path) -> None:
    source = module.read_text(encoding="utf-8")

    ast.parse(source, filename=str(module), feature_version=FLOOR)


@pytest.mark.parametrize("module", MODULES, ids=lambda path: path.name)
def test_no_module_uses_a_runtime_generic_older_pythons_lack(module: Path) -> None:
    tree = ast.parse(module.read_text(encoding="utf-8"), filename=str(module))

    # PEP 585 (`dict[str, int]`) and PEP 604 (`int | None`) are legal *syntax* on 3.9 — the
    # parser accepts both — but only evaluate lazily inside an annotation, which is what
    # `from __future__ import annotations` buys. Anywhere else they are evaluated at import
    # time and raise `TypeError` on 3.9. The parse above therefore cannot catch this, and it is
    # the mistake a future edit is most likely to make, because it looks correct and works on
    # every interpreter a developer is likely to have installed.
    #
    # Asserted unconditionally rather than only below some floor. Both rules are good practice
    # at any version, and a version-conditional branch here would be a test that quietly stops
    # testing — which is worse than one that is occasionally redundant.
    assert any(
        isinstance(node, ast.ImportFrom)
        and node.module == "__future__"
        and any(alias.name == "annotations" for alias in node.names)
        for node in tree.body
    ), f"{module.name} must import annotations from __future__ to keep its annotations lazy"

    for node in ast.walk(tree):
        if isinstance(node, ast.AnnAssign) or isinstance(node, ast.arg):
            # Annotations are strings under the future import; nothing in them is evaluated.
            continue
        if isinstance(node, ast.BinOp) and isinstance(node.op, ast.BitOr):
            pytest.fail(f"{module.name} evaluates a PEP 604 union outside an annotation")
        if isinstance(node, ast.Subscript) and isinstance(node.value, ast.Name):
            assert node.value.id not in {"list", "dict", "set", "tuple", "type", "frozenset"}, (
                f"{module.name} subscripts the builtin `{node.value.id}` outside an annotation, "
                f"which raises TypeError before Python 3.9"
            )

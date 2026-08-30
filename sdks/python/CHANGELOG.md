# Changelog

All notable changes to the PaymentFlow Python SDK. This package follows [semantic
versioning](https://semver.org), independent of the dated PaymentFlow API revision (§7.3).

## [Unreleased]

### Added
- M22.5–M22.7: the same client as the Node SDK, in Python — same options, same defaults, same
  retry rules and backoff constants, same tolerance window, same error classification, same
  page shapes, spelled the way Python spells things. Wheel and sdist verification that installs
  into a clean interpreter. Cross-language parity is asserted by `SdkParityTest` in
  `sdks/shared`.
- M26: publish-ready packaging — `authors`, `keywords`, `[project.urls]`, and per-minor Python
  classifiers. A GitHub Actions release workflow (`.github/workflows/sdk-release-python.yml`)
  publishes on a `sdks/python/vX.Y.Z` tag via PyPI Trusted Publishing (OIDC).

### Not built
- The async client §7.2 calls for. `async`/`await` colours every function it touches, so it
  needs a second transport and a second copy of all eleven namespaces — its own sub-milestone.

### Publishing
- Publish-ready, **not published**. `pyproject.toml` keeps the `Private :: Do Not Upload`
  classifier — `twine` and the PyPI publish action refuse a distribution that carries it, which
  makes removing it the last deliberate step before a real release. See `sdks/PUBLISHING.md`.

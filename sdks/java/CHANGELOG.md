# Changelog

All notable changes to the PaymentFlow Java SDK. This project follows [semantic
versioning](https://semver.org), independent of the dated PaymentFlow API revision.

## [Unreleased]

### Added
- Initial release (M26). Full client over the `2026-08-01` API revision: the eleven resource
  namespaces across all 31 published operations, a typed exception hierarchy, once-per-call
  idempotency keys reused across retries, `Retry-After`-aware full-jitter backoff, per-call
  timeouts, transparent cursor and offset pagination, and `Webhooks.constructEvent` verified
  against the platform's shared signature vectors.
- Zero runtime dependencies. A small internal JSON reader/writer stands in for the one thing the
  JDK has no answer for.
- `ContractParityTest` asserts the generated-equivalent layer against `sdks/shared/fixtures`.

### Publishing
- Publish-ready, **not published**. `dev.paymentflow:paymentflow` is not on Maven Central.

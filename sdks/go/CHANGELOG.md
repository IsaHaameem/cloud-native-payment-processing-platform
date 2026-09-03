# Changelog

All notable changes to the PaymentFlow Go SDK. This module follows [semantic
versioning](https://semver.org), independent of the dated PaymentFlow API revision.

## [Unreleased]

### Added
- Initial release (M26). Full client over the `2026-08-01` API revision: the eleven resource
  services across all 31 published operations, `context.Context` on every call, a typed error
  hierarchy branchable with `errors.As` (plus `AsError` for uniform handling), once-per-call
  idempotency keys reused across retries, `Retry-After`-aware full-jitter backoff that respects
  the caller's context, per-call timeouts, transparent pagination via `iter.Seq2` and `Next`,
  and `ConstructEvent` verified against the platform's shared signature vectors.
- Zero dependencies — the standard library covers HTTP, JSON, HMAC-SHA256 and `crypto/rand`.
- `parity_test.go` asserts the generated-equivalent layer against `sdks/shared/fixtures`.

### Publishing
- Publish-ready, **not tagged**. No `sdks/go/v0.1.0` tag exists yet; Go needs no registry
  account.

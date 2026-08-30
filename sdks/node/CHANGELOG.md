# Changelog

All notable changes to the PaymentFlow Node/TypeScript SDK. This package follows [semantic
versioning](https://semver.org), independent of the dated PaymentFlow API revision (§7.3).

## [Unreleased]

### Added
- M22.2–M22.4: the full client over the `2026-08-01` API revision — configuration, native
  `fetch` transport, authentication, once-per-call idempotency keys reused across retries, the
  retry engine, timeouts, trace-id propagation, the typed error hierarchy, transparent cursor
  and offset pagination, all eleven resource namespaces over every one of the 31 published
  operations, `webhooks.constructEvent` against the shared signature vectors, dual ESM/CJS
  build, a README whose snippets compile, and six examples.
- M26: publish-ready packaging — `repository`, `homepage`, `bugs`, `author`, `keywords`, and
  `publishConfig` (public access + provenance). A GitHub Actions release workflow
  (`.github/workflows/sdk-release-node.yml`) publishes on a `sdks/node/vX.Y.Z` tag via npm
  trusted publishing (OIDC).

### Publishing
- Publish-ready, **not published**. `package.json` keeps `"private": true` — `npm publish`
  refuses it, which makes removing that flag the last deliberate step before a real release.
  See `sdks/PUBLISHING.md`.

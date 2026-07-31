# paymentflow — the PaymentFlow SDK for Node.js

The official client for the PaymentFlow payments API.

> **M22.1.** This package currently exposes its own identity and nothing else — the client,
> the resources, the retry loop and the webhook helpers are M22.2 onwards. It is not
> published; see [Status](#status).

## Requirements

- Node 18 or newer, for the built-in `fetch`. That is the whole reason for the floor: the SDK
  has **zero runtime dependencies**, and a payments SDK that drags in a transitive dependency
  tree is a supply-chain liability for every integrator.
- ESM and CommonJS are both supported. The dual build is plain `tsc` twice plus a ten-line
  script that writes the `{"type": ...}` marker each output directory needs; a bundler would
  have been the largest dependency in the tree.

## What is here today

```ts
import { VERSION, API_VERSION, DEFAULT_BASE_URL, USER_AGENT } from 'paymentflow';
```

| Export | Meaning |
|---|---|
| `VERSION` | this package's version, on its own semver track |
| `API_VERSION` | the dated API revision this build was generated against |
| `DEFAULT_BASE_URL` | the host the client will call unless told otherwise |
| `USER_AGENT` | how this SDK identifies itself in the request log |

`API_VERSION` is not `VERSION`. The API is versioned by date and the SDK by semver, and they
move for different reasons: a bug fix here is a patch release against an unchanged contract,
and a new contract revision changes nothing about this package by itself.

## Types

The types are half of what this package delivers, so `tsconfig.json` turns on every strictness
flag the compiler has. `exactOptionalPropertyTypes` in particular is load-bearing: the
contract distinguishes an absent field from an explicitly `null` one, and without that flag
TypeScript would let the two be confused.

Generated enum types stay **open** — `'created' | 'authorized' | … | (string & {})`. The union
gives an editor its completions; the intersection keeps the type from being closed, because
the platform ships new enum values without a new API revision and a closed union would make
the compiler reject one.

## Development

```bash
npm ci
npm run verify     # type-check, dual ESM/CJS build, then tests against the built output
```

Tests run against `dist/`, not `src/`. What a user installs is the built output, and a
packaging mistake — a wrong `exports` map, a missing declaration file, a module-system marker
that never got written — is invisible from the source tree and total from a consumer's.

`src/generated` is written by `./gradlew :sdks:shared:generateSdkSources` from
`docs/openapi.yaml` and is not edited by hand — `./gradlew :sdks:shared:verifySdkSources`
fails the build when what is committed no longer matches the contract. None of it is
re-exported from `src/index.ts`, and a test asserts that: what an integrator may rely on is
this package's decision, not a code generator's naming.

## Status

Not published to npm. `package.json` is marked `private`, which `npm publish` refuses.
Publishing to a public registry is irreversible and effectively claims a public name, so it
needs explicit approval rather than a passing build.

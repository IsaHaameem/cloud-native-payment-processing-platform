# Publishing the PaymentFlow SDKs

Every SDK is **publish-ready and unpublished**. This file is the complete list of what "publish
it for real" requires. Nothing here has been done — no package exists on any registry.

Verified on 2026-08-30: the name `paymentflow` is **available** on both
[npm](https://www.npmjs.com/package/paymentflow) (404) and
[PyPI](https://pypi.org/project/paymentflow/) (404). Claiming a name on a public registry is
irreversible, so the actual release is gated behind a deliberate step you take, per SDK.

---

## Versions and tags

All four SDKs are at **`0.1.0`**. A release is a git tag; the scheme is:

| SDK | Tag | What the tag does |
|---|---|---|
| Node | `sdks/node/v0.1.0` | triggers `sdk-release-node.yml` |
| Python | `sdks/python/v0.1.0` | triggers `sdk-release-python.yml` |
| Java | `sdks/java/v0.1.0` | triggers `sdk-release-java.yml` |
| Go | `sdks/go/v0.1.0` | triggers `sdk-release-go.yml` **and is the release itself** — Go resolves `…/sdks/go@v0.1.0` straight from this tag |

Each release workflow first asserts the tag version equals the version in the package manifest
(`package.json` / `pyproject.toml` / `build.gradle.kts` / `version.go`) and fails before
publishing if they disagree.

```bash
git tag sdks/go/v0.1.0 <commit> && git push origin sdks/go/v0.1.0
```

---

## The deliberate flag flip, per SDK

Each package carries a marker its registry refuses, so a release cannot happen by an accidental
tag. Removing the marker is the last manual step.

| SDK | Marker to remove | Where | Also update |
|---|---|---|---|
| Node | `"private": true` | `sdks/node/package.json` | `"license"` — `UNLICENSED` is fine for a private release but npm shows it on the page |
| Python | `"Private :: Do Not Upload"` classifier | `sdks/python/pyproject.toml` | `license` — same note |
| Java | none — signing is the gate (below) | — | Central review may query a `Proprietary` license on a public artifact |
| Go | none | — | — |

`SdkParityTest` in `:sdks:shared` asserts the Node and Python markers are present. When you
remove one, update that test (`neitherPackageIsConfiguredToBePublished`) in the same commit, or
`./gradlew build` fails.

---

## What YOU must configure on GitHub and the registries

### Node → npm (trusted publishing, no token)

1. **npm account** — you have one (`muhammadisahaameem`).
2. Publish once manually, or create the package as a placeholder, so it exists.
3. On npmjs.com → the `paymentflow` package → **Settings → Publishing access → Trusted
   publisher**:
   - Repository: `IsaHaameem/cloud-native-payment-processing-platform`
   - Workflow: `.github/workflows/sdk-release-node.yml`
   - Environment: `npm`
4. GitHub → repo **Settings → Environments → `npm`** → add yourself as a required reviewer (so
   the publish job waits for your approval).
5. Fallback (only if you skip trusted publishing): repo secret **`NPM_TOKEN`** (an npm
   automation token) and uncomment the `NODE_AUTH_TOKEN` env in the workflow.

No token in the repo is the intended path.

### Python → PyPI (Trusted Publishing, no token)

1. **PyPI account** — you have one (`isahameem`).
2. On pypi.org → **Your projects → (create/claim) `paymentflow` → Publishing → Add a pending
   publisher**:
   - Owner: `IsaHaameem`
   - Repository: `cloud-native-payment-processing-platform`
   - Workflow: `sdk-release-python.yml`
   - Environment: `pypi`
3. GitHub → **Settings → Environments → `pypi`** → add yourself as a required reviewer.
4. Fallback: repo secret **`PYPI_API_TOKEN`** and set `password:` in the workflow's publish step.

### Java → Maven Central (Central Portal)

Central needs an account, a **verified namespace**, and **GPG-signed** artifacts.

1. **Sonatype Central account** — register at <https://central.sonatype.com>.
2. **Verify the `dev.paymentflow` namespace.** Two options on the Central Portal:
   - a DNS TXT record on `paymentflow.dev` (if you own it), or
   - use a `io.github.isahaameem` namespace instead (verified by owning the GitHub account) —
     if you pick this, change `group` / `coordinates` in `sdks/java/build.gradle.kts` and the
     `dev.paymentflow` references in the POM.
3. **Generate a user token** (Central Portal → Account → Generate User Token) — a name and a
   value.
4. **Create a PGP key**: `gpg --gen-key`, publish it to a keyserver
   (`gpg --keyserver keyserver.ubuntu.com --send-keys <KEYID>`), export the private block
   (`gpg --armor --export-secret-keys <KEYID>`).
5. GitHub → repo secrets:
   - **`MAVEN_CENTRAL_USERNAME`** — the user token *name*
   - **`MAVEN_CENTRAL_PASSWORD`** — the user token *value*
   - **`SIGNING_KEY`** — the full ASCII-armoured private key block
   - **`SIGNING_PASSWORD`** — its passphrase
6. GitHub → **Settings → Environments → `maven-central`** → required reviewer.

Locally, `./gradlew publishToMavenLocal` stages the full artifact (jar + sources + javadoc +
POM + module metadata) into `~/.m2` with **no** credentials and signs nothing — that is the
publish-ready check. The workflow runs `publishAndReleaseToMavenCentral` (with
`automaticRelease = false` you can also stop at the staging repository and release by hand from
the Portal).

### Go → the module proxy

Nothing to configure. `sdk-release-go.yml`, on the tag, re-runs the checks, creates a GitHub
Release, and asks `proxy.golang.org` to fetch the version. `go get
github.com/IsaHaameem/cloud-native-payment-processing-platform/sdks/go@sdks/go/v0.1.0` works the
moment the tag is pushed; `@latest` works once the proxy has cached it (usually seconds).

---

## Summary of repository secrets

| Secret | SDK | Needed when |
|---|---|---|
| `NPM_TOKEN` | Node | only if you skip npm trusted publishing |
| `PYPI_API_TOKEN` | Python | only if you skip PyPI Trusted Publishing |
| `MAVEN_CENTRAL_USERNAME` | Java | always (Central) |
| `MAVEN_CENTRAL_PASSWORD` | Java | always (Central) |
| `SIGNING_KEY` | Java | always (Central) |
| `SIGNING_PASSWORD` | Java | always (Central) |

GitHub environments to create with a required reviewer: `npm`, `pypi`, `maven-central`.

---

## Publish-ready vs published — where things stand

| SDK | Build | Tests | Local dry-run | Workflow | Published? |
|---|---|---|---|---|---|
| Node | ✅ `npm run verify` | ✅ 108 | ✅ `npm pack` (156 files, dist only) | ✅ `sdk-release-node.yml` | **No** |
| Python | ✅ `python -m build` | ✅ 193 + `mypy` | ✅ `twine check` PASS (wheel + sdist) | ✅ `sdk-release-python.yml` | **No** |
| Java | ✅ `./gradlew build` | ✅ 30 | ✅ `publishToMavenLocal` (jar+sources+javadoc+POM) | ✅ `sdk-release-java.yml` | **No** |
| Go | ✅ `go build ./...` | ✅ 29 + `go vet` + `gofmt` | ✅ `go mod tidy` no-op (zero deps) | ✅ `sdk-release-go.yml` | **No** |

"Published" will only ever say Yes here once verified against the registry itself.

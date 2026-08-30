// The PaymentFlow Go SDK (M26).
//
// It lives inside the platform monorepo, in its own module, beside sdks/node, sdks/python and
// sdks/java. A `go get` of the path below resolves to this subdirectory; the release mechanism
// is a git tag of the form `sdks/go/vX.Y.Z` (see sdks/PUBLISHING.md) — Go needs no registry
// account, only a tag the proxy can fetch.
//
// Zero dependencies: the standard library has an HTTP client, JSON, HMAC-SHA256, and
// crypto/rand. A payments SDK that pulled a dependency tree would be a supply-chain liability
// for every integrator, which is the same rule the Node SDK keeps and the Python SDK bends only
// for httpx.
module github.com/IsaHaameem/cloud-native-payment-processing-platform/sdks/go

go 1.23

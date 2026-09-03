package paymentflow

import "runtime"

// Version is this package's own version. It moves on its own schedule, independent of the dated
// API revision in APIVersion: an SDK bug fix is a patch release against an unchanged API, and a
// new API revision does not by itself change anything about this package.
const Version = "0.1.0"

// userAgent is how this SDK identifies itself on every request. Not decoration: it is what
// makes SDK adoption measurable in the request log the platform already records.
var userAgent = "paymentflow-go/" + Version + " go/" + runtime.Version()

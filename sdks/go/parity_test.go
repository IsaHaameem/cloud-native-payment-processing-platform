package paymentflow

import (
	"encoding/json"
	"os"
	"path/filepath"
	"reflect"
	"sort"
	"strings"
	"testing"
)

// The Go SDK's generated-equivalent layer against ../shared/fixtures/*.json — the same
// language-neutral golden fixtures the Node, Python and Java SDKs assert against. This keeps
// contract.go, operations.go, vocabularies.go and models.go honest without a GoEmitter in
// :sdks:shared.

func fixture(t *testing.T, name string) map[string]any {
	t.Helper()
	raw, err := os.ReadFile(filepath.Join("..", "shared", "fixtures", name))
	if err != nil {
		t.Fatalf("read fixture %s: %v", name, err)
	}
	var m map[string]any
	if err := json.Unmarshal(raw, &m); err != nil {
		t.Fatalf("parse fixture %s: %v", name, err)
	}
	return m
}

func TestContractConstantsMatchTheFixture(t *testing.T) {
	c := fixture(t, "contract.json")
	if c["apiVersion"] != APIVersion {
		t.Errorf("apiVersion: fixture %v, code %q", c["apiVersion"], APIVersion)
	}
	if c["baseUrl"] != DefaultBaseURL {
		t.Errorf("baseUrl: fixture %v, code %q", c["baseUrl"], DefaultBaseURL)
	}
	if c["title"] != APITitle {
		t.Errorf("title: fixture %v, code %q", c["title"], APITitle)
	}
	if int(c["operationCount"].(float64)) != len(operations) {
		t.Errorf("operationCount: fixture %v, code %d", c["operationCount"], len(operations))
	}
}

func TestEveryOperationMatchesTheFixtureExactly(t *testing.T) {
	f := fixture(t, "operations.json")
	if len(f) != len(operations) {
		t.Fatalf("operation count: fixture %d, code %d", len(f), len(operations))
	}
	for id, raw := range f {
		spec := raw.(map[string]any)
		got, ok := operations[id]
		if !ok {
			t.Errorf("code is missing operation %s", id)
			continue
		}
		if spec["method"] != got.Method {
			t.Errorf("%s.method: %v vs %q", id, spec["method"], got.Method)
		}
		if spec["path"] != got.Path {
			t.Errorf("%s.path: %v vs %q", id, spec["path"], got.Path)
		}
		if spec["tag"] != got.Tag {
			t.Errorf("%s.tag: %v vs %q", id, spec["tag"], got.Tag)
		}
		if spec["successStatus"] != got.SuccessStatus {
			t.Errorf("%s.successStatus: %v vs %q", id, spec["successStatus"], got.SuccessStatus)
		}
		if spec["hasRequestBody"] != got.HasRequestBody {
			t.Errorf("%s.hasRequestBody: %v vs %v", id, spec["hasRequestBody"], got.HasRequestBody)
		}
		if !sameStrings(spec["queryParameters"], got.QueryParameters) {
			t.Errorf("%s.queryParameters: %v vs %v", id, spec["queryParameters"], got.QueryParameters)
		}
		if !sameStrings(spec["requiredHeaders"], got.RequiredHeaders) {
			t.Errorf("%s.requiredHeaders: %v vs %v", id, spec["requiredHeaders"], got.RequiredHeaders)
		}
	}
}

func TestTheIdempotencyKeyIsRequiredForExactlyFiveOperations(t *testing.T) {
	n := 0
	for _, d := range operations {
		if contains(d.RequiredHeaders, "Idempotency-Key") {
			n++
		}
	}
	if n != 5 {
		t.Fatalf("want 5 operations requiring Idempotency-Key, got %d", n)
	}
}

func TestEveryVocabularyMatchesTheFixture(t *testing.T) {
	f := fixture(t, "enums.json")
	for name, raw := range f {
		values := vocabulariesByFixtureName[name]
		var want []string
		for _, v := range raw.([]any) {
			want = append(want, v.(string))
		}
		if !reflect.DeepEqual(want, values) {
			t.Errorf("%s: fixture %v, code %v", name, want, values)
		}
	}
	if len(f) != len(vocabulariesByFixtureName) {
		t.Errorf("vocabulary count: fixture %d, code %d", len(f), len(vocabulariesByFixtureName))
	}
}

func TestEveryResponseModelHasExactlyTheFixturesFields(t *testing.T) {
	f := fixture(t, "models.json")
	checked := 0
	for name, raw := range f {
		if !isPublicResponse(name) {
			continue
		}
		typ, ok := responseModelTypes[name]
		if !ok {
			t.Errorf("no Go type registered for response model %s", name)
			continue
		}
		var want []string
		for _, v := range raw.([]any) {
			want = append(want, v.(string))
		}
		got := jsonTags(typ)
		sort.Strings(want)
		sort.Strings(got)
		if !reflect.DeepEqual(want, got) {
			t.Errorf("%s fields:\n  fixture %v\n  code    %v", name, want, got)
		}
		checked++
	}
	if checked < 18 {
		t.Fatalf("expected to check every response model, checked %d", checked)
	}
}

func isPublicResponse(name string) bool {
	if strings.HasPrefix(name, "CursorPage") || strings.HasPrefix(name, "PageResponse") {
		return false
	}
	return strings.HasSuffix(name, "Response") || name == "CurrencyBalance" || name == "ApiFieldError"
}

// responseModelTypes maps each fixture model name to its Go struct type.
var responseModelTypes = map[string]reflect.Type{
	"ApiFieldError":                  reflect.TypeOf(FieldError{}),
	"CurrencyBalance":                reflect.TypeOf(CurrencyBalance{}),
	"BalanceResponse":                reflect.TypeOf(Balance{}),
	"BalanceTransactionResponse":     reflect.TypeOf(BalanceTransaction{}),
	"AnalyticsBucketResponse":        reflect.TypeOf(AnalyticsBucket{}),
	"AnalyticsSummaryResponse":       reflect.TypeOf(AnalyticsSummary{}),
	"DecisionLogEntryResponse":       reflect.TypeOf(DecisionLogEntry{}),
	"EventResponse":                  reflect.TypeOf(Event{}),
	"PaymentResponse":                reflect.TypeOf(Payment{}),
	"RefundResponse":                 reflect.TypeOf(Refund{}),
	"RequestLogResponse":             reflect.TypeOf(RequestLog{}),
	"SimulationOverrideResponse":     reflect.TypeOf(SimulationOverride{}),
	"TestCardResponse":               reflect.TypeOf(TestCard{}),
	"UsageBucketResponse":            reflect.TypeOf(UsageBucket{}),
	"UsageSummaryResponse":           reflect.TypeOf(UsageSummary{}),
	"WebhookDeliveryAttemptResponse": reflect.TypeOf(WebhookDeliveryAttempt{}),
	"WebhookDeliveryResponse":        reflect.TypeOf(WebhookDelivery{}),
	"WebhookEndpointResponse":        reflect.TypeOf(WebhookEndpoint{}),
	"WebhookEndpointCreatedResponse": reflect.TypeOf(WebhookEndpointCreated{}),
}

func jsonTags(t reflect.Type) []string {
	var out []string
	for i := 0; i < t.NumField(); i++ {
		tag := t.Field(i).Tag.Get("json")
		if tag == "" || tag == "-" {
			continue
		}
		out = append(out, strings.Split(tag, ",")[0])
	}
	return out
}

func sameStrings(fixtureVal any, got []string) bool {
	var want []string
	if list, ok := fixtureVal.([]any); ok {
		for _, v := range list {
			want = append(want, v.(string))
		}
	}
	if len(want) == 0 && len(got) == 0 {
		return true
	}
	return reflect.DeepEqual(want, got)
}

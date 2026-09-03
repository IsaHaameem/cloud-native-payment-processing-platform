package paymentflow

import (
	"strings"
	"testing"
	"time"
)

func TestNewClientRejectsAMissingKeyWhenItIsBuilt(t *testing.T) {
	t.Setenv("PAYMENTFLOW_API_KEY", "")
	_, err := NewClient("")
	if err == nil || !strings.Contains(err.Error(), "PAYMENTFLOW_API_KEY") {
		t.Fatalf("want a missing-key error, got %v", err)
	}
}

func TestNewClientFallsBackToTheEnvironment(t *testing.T) {
	t.Setenv("PAYMENTFLOW_API_KEY", "sk_test_env")
	c, err := NewClient("")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if c.apiKey != "sk_test_env" {
		t.Fatalf("want key from env, got %q", c.apiKey)
	}
}

func TestNewClientRejectsAWhitespaceWrappedKey(t *testing.T) {
	if _, err := NewClient(" sk_test_x "); err == nil {
		t.Fatal("want a whitespace error")
	}
}

func TestNewClientRejectsANonAbsoluteBaseURL(t *testing.T) {
	if _, err := NewClient("sk_test_x", WithBaseURL("/v1")); err == nil {
		t.Fatal("want a base URL error")
	}
}

func TestNewClientRejectsANegativeTimeout(t *testing.T) {
	if _, err := NewClient("sk_test_x", WithTimeout(-time.Second)); err == nil {
		t.Fatal("want a timeout error")
	}
}

func TestNewClientRejectsANegativeRetryBudget(t *testing.T) {
	if _, err := NewClient("sk_test_x", WithMaxRetries(-1)); err == nil {
		t.Fatal("want a maxRetries error")
	}
}

func TestNewClientDefaultsAndTrailingSlashStripping(t *testing.T) {
	c, err := NewClient("sk_test_x", WithBaseURL("https://api.example.test/"))
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if c.BaseURL() != "https://api.example.test" {
		t.Fatalf("trailing slash not stripped: %q", c.BaseURL())
	}
	if c.APIVersion() != APIVersion {
		t.Fatalf("want default api version %q, got %q", APIVersion, c.APIVersion())
	}
}

func TestNewClientWiresAllElevenServices(t *testing.T) {
	c, err := NewClient("sk_test_x")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if c.Payments == nil || c.Refunds == nil || c.Balance == nil || c.BalanceTransactions == nil ||
		c.Events == nil || c.Analytics == nil || c.RequestLogs == nil || c.Usage == nil ||
		c.WebhookEndpoints == nil || c.WebhookDeliveries == nil || c.TestHelpers == nil {
		t.Fatal("a service namespace was left nil")
	}
}

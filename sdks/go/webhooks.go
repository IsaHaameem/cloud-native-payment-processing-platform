package paymentflow

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"regexp"
	"strconv"
	"strings"
	"time"
)

// SignatureHeader is the header every webhook delivery carries.
const SignatureHeader = "PaymentFlow-Signature"

// DefaultTolerance is the default clock-skew window: five minutes. Wide enough to survive
// ordinary drift between two servers, narrow enough that a captured delivery is not replayable
// for the rest of the afternoon.
const DefaultTolerance = 5 * time.Minute

// WebhookEvent is the envelope a verified delivery contains. It is not model.Event: /v1/events
// returns no apiVersion, a delivery does. ID (evt_ + 32 hex) is stable across retries and
// replays — dedupe on it. Data["object"] is the resource the event happened to; branch on Type
// before reading it.
type WebhookEvent struct {
	ID         string         `json:"id"`
	Object     string         `json:"object"`
	Type       string         `json:"type"`
	APIVersion string         `json:"apiVersion"`
	Created    string         `json:"created"`
	Mode       string         `json:"mode"`
	Data       map[string]any `json:"data"`
}

// DataObject returns Data["object"] as a map, or nil.
func (e WebhookEvent) DataObject() map[string]any {
	if e.Data == nil {
		return nil
	}
	obj, _ := e.Data["object"].(map[string]any)
	return obj
}

// WebhookSignatureError: the signature header was malformed, or nothing in it matched. Treat it
// as hostile — a body that fails verification did not come from PaymentFlow, or did not arrive
// intact, and either way must not be acted on.
type WebhookSignatureError struct{ Message string }

func (e *WebhookSignatureError) Error() string { return "paymentflow: " + e.Message }

// WebhookTimestampError: the signature was valid and its timestamp is outside the tolerance
// window. A different problem from a signature failure, with a different fix — usually a replay,
// but also what a clock skewed by minutes looks like.
type WebhookTimestampError struct {
	Message     string
	Timestamp   int64
	SkewSeconds int64
}

func (e *WebhookTimestampError) Error() string { return "paymentflow: " + e.Message }

// WebhookPayloadError: it verified and is not an event envelope. Reachable only from a platform
// defect.
type WebhookPayloadError struct{ Message string }

func (e *WebhookPayloadError) Error() string { return "paymentflow: " + e.Message }

var digitsRE = regexp.MustCompile(`^\d+$`)

// ConstructEvent verifies a delivery and returns its event.
//
// payload must be the RAW request body, exactly as received — the signature covers the bytes
// that were sent, and parsing then re-serializing the JSON does not round-trip them. In net/http
// that is the []byte from io.ReadAll(r.Body), read before anything decodes it.
//
// tolerance of 0 uses DefaultTolerance. Pass a negative value and it is rejected.
func ConstructEvent(payload []byte, signatureHeader, secret string, tolerance time.Duration) (*WebhookEvent, error) {
	return constructEventAt(payload, signatureHeader, secret, tolerance, time.Now())
}

func constructEventAt(payload []byte, signatureHeader, secret string, tolerance time.Duration, now time.Time) (*WebhookEvent, error) {
	if tolerance == 0 {
		tolerance = DefaultTolerance
	}
	if secret == "" {
		return nil, &WebhookSignatureError{"No signing secret. Pass the endpoint's whsec_… value."}
	}
	if signatureHeader == "" {
		return nil, &WebhookSignatureError{"No " + SignatureHeader + " header on the request."}
	}
	if tolerance < 0 {
		return nil, &WebhookSignatureError{"tolerance must not be negative."}
	}

	timestamp, candidates, err := parseSignatureHeader(signatureHeader)
	if err != nil {
		return nil, err
	}

	// Signature before timestamp, deliberately: checking the window first would let anyone with
	// the URL and a stopwatch learn whether a body was correctly signed by observing which
	// error came back, and would report a garbage header as "too old".
	expected := sign(secret, timestamp, payload)
	matched := false
	for _, candidate := range candidates {
		if hmac.Equal([]byte(candidate), []byte(expected)) {
			matched = true
			break
		}
	}
	if !matched {
		return nil, &WebhookSignatureError{
			"The signature does not match. Either the secret is wrong, or the payload is not the " +
				"raw request body — re-serializing the JSON changes the bytes the signature covers.",
		}
	}

	skew := now.Unix() - timestamp
	if skew < 0 {
		skew = -skew
	}
	if skew > int64(tolerance.Seconds()) {
		return nil, &WebhookTimestampError{
			Message: fmt.Sprintf("The delivery's timestamp is %ds away from now, outside the %ds tolerance. "+
				"This is a replayed delivery, or a clock is wrong.", skew, int64(tolerance.Seconds())),
			Timestamp:   timestamp,
			SkewSeconds: skew,
		}
	}

	return parseWebhookEvent(payload)
}

// SignPayload computes the v1 value for a body: lowercase hex HMAC-SHA256 over "{timestamp}.{body}".
// Exported so a caller can build a signed request in their own tests without reimplementing the
// specification — the moment they would get it subtly wrong and write a test that passes
// against their own mistake.
func SignPayload(secret string, timestamp int64, payload []byte) string {
	return sign(secret, timestamp, payload)
}

// SignatureHeaderFor builds a full header value, for the same reason SignPayload is exported.
func SignatureHeaderFor(secret string, timestamp int64, payload []byte) string {
	return fmt.Sprintf("t=%d,v1=%s", timestamp, sign(secret, timestamp, payload))
}

func sign(secret string, timestamp int64, body []byte) string {
	mac := hmac.New(sha256.New, []byte(secret))
	mac.Write([]byte(strconv.FormatInt(timestamp, 10) + "."))
	mac.Write(body)
	return hex.EncodeToString(mac.Sum(nil))
}

func parseSignatureHeader(header string) (int64, []string, error) {
	var timestamp int64 = -1
	var candidates []string
	for _, element := range strings.Split(header, ",") {
		sep := strings.IndexByte(element, '=')
		if sep < 0 {
			continue
		}
		key := strings.TrimSpace(element[:sep])
		value := strings.TrimSpace(element[sep+1:])
		switch key {
		case "t":
			if !digitsRE.MatchString(value) {
				return 0, nil, &WebhookSignatureError{"The " + SignatureHeader + " header's timestamp is not an integer."}
			}
			timestamp, _ = strconv.ParseInt(value, 10, 64)
		case "v1":
			if value != "" {
				candidates = append(candidates, value)
			}
		}
		// A future v2 alongside v1 is how this scheme would gain a second algorithm; an
		// unfamiliar field is skipped rather than rejected.
	}
	if timestamp < 0 {
		return 0, nil, &WebhookSignatureError{"The " + SignatureHeader + " header has no t= timestamp."}
	}
	if len(candidates) == 0 {
		return 0, nil, &WebhookSignatureError{"The " + SignatureHeader + " header has no v1= signature."}
	}
	return timestamp, candidates, nil
}

func parseWebhookEvent(body []byte) (*WebhookEvent, error) {
	var generic map[string]any
	if err := json.Unmarshal(body, &generic); err != nil {
		return nil, &WebhookPayloadError{"The delivery verified but its body is not a JSON object."}
	}
	_, idOK := generic["id"].(string)
	_, typeOK := generic["type"].(string)
	_, dataOK := generic["data"].(map[string]any)
	if !idOK || !typeOK || !dataOK {
		return nil, &WebhookPayloadError{
			"The delivery verified but is not an event envelope — id, type and data are required.",
		}
	}
	var event WebhookEvent
	if err := json.Unmarshal(body, &event); err != nil {
		return nil, &WebhookPayloadError{"The delivery verified but its body is not an event envelope."}
	}
	return &event, nil
}

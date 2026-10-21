// Package digitarium answers as Digitarium (G2Pay) does to the original's psp-gateway adapter
// (eitheror-platform/services/psp-gateway/internal/digitarium/adapter.go), and sends its signed
// webhook. psp-stub serves it on 18109.
package digitarium

// The API as the adapter calls it:
//
//   POST {base}/api/v1/payments   Bearer apiKey; JSON {referenceId, paymentType: DEPOSIT,
//                                 paymentMethod: BASIC_CARD, amount (a number), currency,
//                                 returnUrl, webhookUrl}; the adapter treats 401 as a bad key and
//                                 needs result.redirectUrl (adapter.go:111-213)
//
// The webhook goes to the webhookUrl the request carried (<gateway>/v1/webhooks/digitarium), with
// header Signature = HMAC-SHA256(raw body, signingKey). G2Pay documents neither the encoding nor
// whether the body is the bare payment or wrapped in {"result": …}; the gateway accepts hex or any
// base64 (signature.go:21-52) and both shapes (adapter.go:84-98), so the stub sends either by
// control. The payment's status is read from `state`: COMPLETED captured, DECLINED or CANCELLED
// failed with errorCode and errorMessage, anything else open (adapter.go:215-227,
// handler/digitarium.go:39-158).

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/eitheror-io/ankka-casino/tests/stubs/internal/stubhttp"
)

// The default signingKey is the one the original's own webhook tests use
// (internal/handler/webhook_digitarium_test.go:46). It must equal the terminal's signingKey.
const defaultSigningKey = "test-signing-key-unit-only"

// Config is what the stub needs beyond the request.
type Config struct {
	// APIKey, when set, is the Bearer token a request must carry; empty, any non-empty token.
	APIKey string
	// SigningKey signs the webhook.
	SigningKey string
	// PublicURL is how a browser reaches this stub, for result.redirectUrl.
	PublicURL string
	// WebhookURL, when set, replaces the request's webhookUrl.
	WebhookURL string
	// Envelope is the webhook body's default shape: "flat" or "result".
	Envelope string
	// Delay is how long after the payment is created its webhook is sent.
	Delay time.Duration
}

// ConfigFromEnv reads DIGITARIUM_STUB_* variables.
func ConfigFromEnv(getenv func(string) string) Config {
	c := Config{
		APIKey: getenv("DIGITARIUM_STUB_API_KEY"), SigningKey: defaultSigningKey, PublicURL: "http://localhost:18109",
		WebhookURL: getenv("DIGITARIUM_STUB_WEBHOOK_URL"), Envelope: "flat", Delay: 500 * time.Millisecond,
	}
	if v := getenv("DIGITARIUM_STUB_SIGNING_KEY"); v != "" {
		c.SigningKey = v
	}
	if v := getenv("DIGITARIUM_STUB_PUBLIC_URL"); v != "" {
		c.PublicURL = v
	}
	if v := getenv("DIGITARIUM_STUB_WEBHOOK_BODY"); v == "flat" || v == "result" {
		c.Envelope = v
	}
	if v := getenv("DIGITARIUM_STUB_WEBHOOK_DELAY_MS"); v != "" {
		if n, err := strconv.Atoi(v); err == nil && n >= 0 {
			c.Delay = time.Duration(n) * time.Millisecond
		}
	}
	return c
}

// Start is the stub as psp-stub runs it.
func Start(getenv func(string) string) (*stubhttp.Stub, http.Handler) {
	stub := stubhttp.New("digitarium")
	return stub, NewProvider(stub, ConfigFromEnv(getenv)).Routes()
}

type paymentRequest struct {
	ReferenceId   string  `json:"referenceId"`
	PaymentType   string  `json:"paymentType"`
	PaymentMethod string  `json:"paymentMethod"`
	Amount        float64 `json:"amount"`
	Currency      string  `json:"currency"`
	ReturnUrl     string  `json:"returnUrl"`
	WebhookUrl    string  `json:"webhookUrl"`
}

type payment struct {
	id     string
	req    paymentRequest
	target string
}

// Provider answers G2Pay's API.
type Provider struct {
	stub   *stubhttp.Stub
	cfg    Config
	client *http.Client

	mu       sync.Mutex
	payments map[string]*payment // by referenceId, the gateway's txnRef, and by G2Pay id
}

func NewProvider(stub *stubhttp.Stub, cfg Config) *Provider {
	p := &Provider{stub: stub, cfg: cfg, client: &http.Client{Timeout: 10 * time.Second}, payments: map[string]*payment{}}
	stub.Supports(stubhttp.Unsigned, stubhttp.Unauthorised)
	stub.OnWebhook(p.send)
	return p
}

func (p *Provider) Routes() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("POST /api/v1/payments", p.create)
	mux.HandleFunc("GET /checkout/{id}", p.page)
	return mux
}

func apiError(w http.ResponseWriter, status int, msg string) {
	stubhttp.WriteJSON(w, status, map[string]any{"timestamp": now(), "status": status, "error": http.StatusText(status), "message": msg})
}

func now() string { return time.Now().UTC().Format(time.RFC3339) }

func (p *Provider) create(w http.ResponseWriter, r *http.Request) {
	control := p.stub.Control()
	token, ok := strings.CutPrefix(r.Header.Get("Authorization"), "Bearer ")
	if !ok || token == "" || (p.cfg.APIKey != "" && token != p.cfg.APIKey) || control.Outcome == stubhttp.Unauthorised {
		apiError(w, http.StatusUnauthorized, "invalid API key")
		return
	}
	var req paymentRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		apiError(w, http.StatusBadRequest, "invalid JSON body")
		return
	}
	if req.ReferenceId == "" || req.Currency == "" || req.Amount <= 0 || req.PaymentType == "" {
		apiError(w, http.StatusBadRequest, "referenceId, paymentType, a positive amount and currency are required")
		return
	}
	target := req.WebhookUrl
	if p.cfg.WebhookURL != "" {
		target = p.cfg.WebhookURL
	}
	pay := &payment{id: randomHex(16), req: req, target: target}
	p.mu.Lock()
	p.payments[req.ReferenceId] = pay
	p.payments[pay.id] = pay
	p.mu.Unlock()

	stubhttp.WriteJSON(w, http.StatusOK, map[string]any{
		"timestamp": now(),
		"status":    200,
		"result": map[string]any{
			"id":            pay.id,
			"referenceId":   req.ReferenceId,
			"paymentType":   req.PaymentType,
			"paymentMethod": req.PaymentMethod,
			"state":         "CHECKOUT",
			"amount":        req.Amount,
			"currency":      req.Currency,
			"redirectUrl":   strings.TrimRight(p.cfg.PublicURL, "/") + "/checkout/" + pay.id,
		},
	})

	if control.Outcome == stubhttp.Pending {
		p.stub.Hold(req.ReferenceId) // the PENDING webhook goes now; the next control settles it
	}
	go func() {
		time.Sleep(control.Delay(p.cfg.Delay))
		_, _ = p.send(req.ReferenceId, control)
	}()
}

// page stands in for the hosted payment page: it sends the player back to returnUrl.
func (p *Provider) page(w http.ResponseWriter, r *http.Request) {
	p.mu.Lock()
	pay, ok := p.payments[r.PathValue("id")]
	p.mu.Unlock()
	if !ok || pay.req.ReturnUrl == "" {
		http.NotFound(w, r)
		return
	}
	http.Redirect(w, r, pay.req.ReturnUrl, http.StatusFound)
}

// Body is the webhook body for a payment under a control: state COMPLETED; DECLINED with the
// code as errorCode (CANCELLED when the code is CANCELLED); PENDING; unsigned is COMPLETED. The
// shape is the payment object, bare or under "result".
func (p *Provider) Body(pay *payment, c stubhttp.Control) ([]byte, error) {
	result := map[string]any{
		"id": pay.id, "referenceId": pay.req.ReferenceId, "paymentType": pay.req.PaymentType,
		"amount": pay.req.Amount, "currency": pay.req.Currency,
	}
	switch c.Outcome {
	case stubhttp.Success, stubhttp.Unsigned:
		result["state"] = "COMPLETED"
	case stubhttp.Failure:
		result["state"], result["errorCode"], result["errorMessage"] = "DECLINED", "05", "Do not honour"
		switch c.Code {
		case "":
		case "CANCELLED":
			result["state"], result["errorCode"], result["errorMessage"] = "CANCELLED", "CANCELLED", "Cancelled by the payer"
		default:
			result["errorCode"] = c.Code
		}
	case stubhttp.Pending:
		result["state"] = "PENDING"
	default:
		return nil, fmt.Errorf("digitarium has no webhook for %q", c.Outcome)
	}
	envelope := c.Envelope
	if envelope == "" {
		envelope = p.cfg.Envelope
	}
	switch envelope {
	case "", "flat":
		return json.Marshal(result)
	case "result":
		return json.Marshal(map[string]any{"timestamp": now(), "status": 200, "result": result})
	default:
		return nil, fmt.Errorf("digitarium envelope is flat or result, not %q", envelope)
	}
}

// Signature is the Signature header: HMAC-SHA256 of the raw body under the signing key, hex by
// default, or base64 or base64url.
func Signature(key string, body []byte, encoding string) (string, error) {
	mac := hmac.New(sha256.New, []byte(key))
	mac.Write(body)
	sum := mac.Sum(nil)
	switch encoding {
	case "", "hex":
		return hex.EncodeToString(sum), nil
	case "base64":
		return base64.StdEncoding.EncodeToString(sum), nil
	case "base64url":
		return base64.RawURLEncoding.EncodeToString(sum), nil
	default:
		return "", fmt.Errorf("digitarium signatureEncoding is hex, base64 or base64url, not %q", encoding)
	}
}

// send delivers one payment's webhook now.
func (p *Provider) send(ref string, c stubhttp.Control) (stubhttp.Delivery, error) {
	p.mu.Lock()
	pay, ok := p.payments[ref]
	p.mu.Unlock()
	if !ok {
		return stubhttp.Delivery{}, stubhttp.ErrUnknownReference
	}
	body, err := p.Body(pay, c)
	if err != nil {
		return stubhttp.Delivery{}, err
	}
	header := map[string]string{}
	if c.Outcome != stubhttp.Unsigned {
		sig, err := Signature(p.cfg.SigningKey, body, c.SignatureEncoding)
		if err != nil {
			return stubhttp.Delivery{}, err
		}
		header["Signature"] = sig
	}
	if pay.target == "" {
		return stubhttp.Delivery{}, fmt.Errorf("no webhookUrl for %s", ref)
	}
	d := stubhttp.Post(p.client, pay.target, "application/json", header, body)
	log.Printf("digitarium stub: webhook for %s to %s answered %d (err=%v)", ref, pay.target, d.Status, d.Err)
	return d, nil
}

func randomHex(n int) string {
	b := make([]byte, n)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}

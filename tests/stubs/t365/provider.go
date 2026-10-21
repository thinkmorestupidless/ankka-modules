// Package t365 answers as T365 does to the original's psp-gateway T365 adapter, and posts its
// signed postbacks. psp-stub serves it on 18107.
package t365

// The T365 API as the original's adapter uses it
// (eitheror-platform/services/psp-gateway/internal/t365/adapter.go):
//
//   POST {T365_BASE_URL}/api/v1/external/payment/hosted
//        header apikey; JSON {amount, currency, callbackUrl, returnUrl, orderId, method, midId,
//        email, ip, country}
//   → {paymentUrl, status, message, id, orderId}; the adapter needs only a non-empty paymentUrl.
//
// The player is sent to paymentUrl; T365 then posts the outcome as JSON to callbackUrl
// ({gateway}/v1/webhooks/t365): {id, orderId, status, amount, currency, hash}, where hash is
// t365.Sign (internal/t365/signature.go) and status is SUCCESS, REJECTED, FAIL or PENDING
// (handler/webhook.go T365Webhook). The adapter does not poll and has no payout.

import (
	"crypto/sha256"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/eitheror-io/ankka-casino/tests/stubs/internal/stubhttp"
)

// Config is the stub's settings.
type Config struct {
	// Secret is the terminal's secretKey; T365 never receives it, so the stub must be told it to sign.
	Secret string
	// PublicURL is the stub's browser-reachable base for paymentUrl; empty means the request's Host.
	PublicURL string
	// WebhookURL, when set, replaces callbackUrl.
	WebhookURL string
	// Delay is how long after initiation the postback is sent.
	Delay time.Duration
}

// ConfigFromEnv reads T365_STUB_SECRET (default "t365-stub-secret"), T365_STUB_PUBLIC_URL,
// T365_STUB_WEBHOOK_URL and T365_STUB_CALLBACK_DELAY_MS (default 1000).
func ConfigFromEnv(getenv func(string) string) Config {
	c := Config{Secret: "t365-stub-secret", PublicURL: getenv("T365_STUB_PUBLIC_URL"), WebhookURL: getenv("T365_STUB_WEBHOOK_URL"), Delay: time.Second}
	if v := getenv("T365_STUB_SECRET"); v != "" {
		c.Secret = v
	}
	if v := getenv("T365_STUB_CALLBACK_DELAY_MS"); v != "" {
		if n, err := strconv.Atoi(v); err == nil && n >= 0 {
			c.Delay = time.Duration(n) * time.Millisecond
		}
	}
	return c
}

// Start is the stub as psp-stub runs it.
func Start(getenv func(string) string) (*stubhttp.Stub, http.Handler) {
	stub := stubhttp.New("t365")
	return stub, NewProvider(stub, ConfigFromEnv(getenv))
}

// Postback is the JSON T365 posts to callbackUrl.
type Postback struct {
	Id       string `json:"id"`
	OrderId  string `json:"orderId"`
	Status   string `json:"status"`
	Amount   string `json:"amount"`
	Currency string `json:"currency"`
	Hash     string `json:"hash"`
}

// Sign is T365's postback hash as the adapter computes it (internal/t365/signature.go:13-38):
// the secret padded with '0' to 16 characters, then SHA-256 of
// secretKey={padded}&amount={amount}&currency={currency}&id={orderId}&orderId={orderId}&status={status},
// in uppercase hex. Both id and orderId carry the merchant orderId.
func Sign(secret, amount, currency, orderId, status string) string {
	if len(secret) < 16 {
		secret += strings.Repeat("0", 16-len(secret))
	}
	in := fmt.Sprintf("secretKey=%s&amount=%s&currency=%s&id=%s&orderId=%s&status=%s",
		secret, amount, currency, orderId, orderId, status)
	return strings.ToUpper(fmt.Sprintf("%x", sha256.Sum256([]byte(in))))
}

type payment struct {
	id, orderId, amount, currency, callback, apiKey, returnUrl string
}

// Provider answers T365's routes and sends its postbacks.
type Provider struct {
	stub     *stubhttp.Stub
	cfg      Config
	client   *http.Client
	nextID   atomic.Int64
	mu       sync.Mutex
	payments map[string]*payment // by T365 id and by orderId, the gateway's dashless txnRef
	mux      *http.ServeMux
}

// NewProvider builds the T365 handler.
func NewProvider(stub *stubhttp.Stub, cfg Config) *Provider {
	p := &Provider{stub: stub, cfg: cfg, client: &http.Client{Timeout: 10 * time.Second}, payments: map[string]*payment{}}
	p.nextID.Store(900000)
	p.mux = http.NewServeMux()
	p.mux.HandleFunc("POST /api/v1/external/payment/hosted", p.hosted)
	p.mux.HandleFunc("GET /hosted/{id}", p.page)
	stub.Supports(stubhttp.Unauthorised)
	stub.OnWebhook(p.send)
	return p
}

func (p *Provider) ServeHTTP(w http.ResponseWriter, r *http.Request) { p.mux.ServeHTTP(w, r) }

func reject(w http.ResponseWriter, status int, message string) {
	stubhttp.WriteJSON(w, status, map[string]string{"status": "error", "message": message})
}

func (p *Provider) hosted(w http.ResponseWriter, r *http.Request) {
	control := p.stub.Control()
	apiKey := r.Header.Get("apikey")
	if apiKey == "" {
		reject(w, http.StatusUnauthorized, "apikey header is required")
		return
	}
	if control.Outcome == stubhttp.Unauthorised {
		reject(w, http.StatusUnauthorized, "invalid apikey")
		return
	}
	var req map[string]any
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		reject(w, http.StatusBadRequest, "invalid JSON body")
		return
	}
	str := func(k string) string { s, _ := req[k].(string); return s }
	for _, k := range []string{"amount", "currency", "callbackUrl", "returnUrl", "orderId", "method"} {
		if str(k) == "" {
			reject(w, http.StatusBadRequest, fmt.Sprintf("%q is required", k))
			return
		}
	}
	if req["midId"] == nil {
		reject(w, http.StatusBadRequest, `"midId" is required`)
		return
	}
	orderId := str("orderId")
	if n := len(orderId); n < 10 || n > 50 {
		reject(w, http.StatusBadRequest, `"orderId" must be 10 to 50 alphanumeric characters`)
		return
	}

	id := strconv.FormatInt(p.nextID.Add(1), 10)
	callback := str("callbackUrl")
	if p.cfg.WebhookURL != "" {
		callback = p.cfg.WebhookURL
	}
	pay := &payment{id: id, orderId: orderId, amount: str("amount"), currency: str("currency"), callback: callback, apiKey: apiKey, returnUrl: str("returnUrl")}
	p.mu.Lock()
	p.payments[id] = pay
	p.payments[orderId] = pay
	p.mu.Unlock()

	base := p.cfg.PublicURL
	if base == "" {
		base = "http://" + r.Host
	}
	stubhttp.WriteJSON(w, http.StatusOK, map[string]string{
		"id":         id,
		"orderId":    orderId,
		"status":     "PENDING",
		"message":    "Payment created",
		"paymentUrl": strings.TrimSuffix(base, "/") + "/hosted/" + id,
	})

	if control.Outcome == stubhttp.Pending {
		p.stub.Hold(orderId) // the PENDING postback goes now; the next control settles it
	}
	pb, _ := p.postback(pay, control)
	p.deliverLater(pay, pb, control.Delay(p.cfg.Delay))
}

// postback is the signed postback for a control: success SUCCESS; failure FAIL, or the code when
// it is REJECTED; pending PENDING.
func (p *Provider) postback(pay *payment, c stubhttp.Control) (Postback, error) {
	var status string
	switch c.Outcome {
	case stubhttp.Success:
		status = "SUCCESS"
	case stubhttp.Failure:
		status = "FAIL"
		if c.Code != "" {
			status = c.Code
		}
	case stubhttp.Pending:
		status = "PENDING"
	default:
		return Postback{}, fmt.Errorf("t365 has no postback for %q", c.Outcome)
	}
	return Postback{
		Id: pay.id, OrderId: pay.orderId, Status: status, Amount: pay.amount, Currency: pay.currency,
		Hash: Sign(p.cfg.Secret, pay.amount, pay.currency, pay.orderId, status),
	}, nil
}

// page is the hosted payment page: it sends the player straight back to the returnUrl.
func (p *Provider) page(w http.ResponseWriter, r *http.Request) {
	p.mu.Lock()
	pay, ok := p.payments[r.PathValue("id")]
	p.mu.Unlock()
	if !ok {
		http.NotFound(w, r)
		return
	}
	http.Redirect(w, r, pay.returnUrl, http.StatusFound)
}

func (p *Provider) post(pay *payment, pb Postback) stubhttp.Delivery {
	body, _ := json.Marshal(pb)
	return stubhttp.Post(p.client, pay.callback, "application/json", map[string]string{"apikey": pay.apiKey}, body)
}

// send posts one payment's postback now, once.
func (p *Provider) send(ref string, c stubhttp.Control) (stubhttp.Delivery, error) {
	p.mu.Lock()
	pay, ok := p.payments[ref]
	p.mu.Unlock()
	if !ok {
		return stubhttp.Delivery{}, stubhttp.ErrUnknownReference
	}
	pb, err := p.postback(pay, c)
	if err != nil {
		return stubhttp.Delivery{}, err
	}
	d := p.post(pay, pb)
	log.Printf("t365 stub: postback orderId=%s status=%s to %s answered %d (err=%v)", pb.OrderId, pb.Status, pay.callback, d.Status, d.Err)
	return d, nil
}

// deliverLater posts the postback after the delay, retrying until a 200, at most 5 times
// (T365 retries up to 5 times on a non-200; handler/webhook.go T365Webhook).
func (p *Provider) deliverLater(pay *payment, pb Postback, delay time.Duration) {
	go func() {
		time.Sleep(delay)
		for attempt := 1; attempt <= 5; attempt++ {
			d := p.post(pay, pb)
			if d.Err == nil && d.Status == http.StatusOK {
				log.Printf("t365 stub: postback orderId=%s status=%s delivered to %s", pb.OrderId, pb.Status, pay.callback)
				return
			}
			log.Printf("t365 stub: postback orderId=%s attempt %d to %s failed: %d %v", pb.OrderId, attempt, pay.callback, d.Status, d.Err)
			time.Sleep(time.Second)
		}
	}()
}

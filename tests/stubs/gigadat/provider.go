package main

import (
	"bytes"
	"crypto/rand"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"net/url"
	"strconv"
	"sync"
	"time"

	"github.com/eitheror-io/ankka-casino/tests/stubs/internal/stubhttp"
)

// Config is what the stub needs that the adapter's requests do not carry.
type Config struct {
	// WebhookUrl is where status webhooks go. Gigadat does not take it in the request: it is set
	// once per campaign in Gigadat's portal (the original's specs/199-interac-psp/spec.md, US2
	// scenario 4). The default is the gateway's own default address and route: port 8087
	// (internal/config/config.go:73) and POST /v1/webhooks/gigadat (cmd/psp-gateway/main.go:210).
	WebhookUrl string
	// WebhookBody is the body's encoding: "array" (a bare JSON array of {key,value}, as the
	// gateway handler's comment describes it, handler/gigadat.go:39-41) or "form" (a URL-encoded
	// form whose "data" field is that array, as the original's spec 199 describes it). The
	// gateway's parser reads both (gigadat/webhook.go:38-71).
	WebhookBody string
	// WebhookDelay is how long after the transaction starts its status webhooks are sent.
	WebhookDelay time.Duration
}

// ConfigFromEnv reads GIGADAT_STUB_WEBHOOK_URL, GIGADAT_STUB_WEBHOOK_BODY and
// GIGADAT_STUB_WEBHOOK_DELAY_MS.
func ConfigFromEnv(getenv func(string) string) Config {
	c := Config{
		WebhookUrl:   "http://localhost:8087/v1/webhooks/gigadat",
		WebhookBody:  "array",
		WebhookDelay: 1500 * time.Millisecond,
	}
	if v := getenv("GIGADAT_STUB_WEBHOOK_URL"); v != "" {
		c.WebhookUrl = v
	}
	if v := getenv("GIGADAT_STUB_WEBHOOK_BODY"); v == "form" || v == "array" {
		c.WebhookBody = v
	}
	if v := getenv("GIGADAT_STUB_WEBHOOK_DELAY_MS"); v != "" {
		if n, err := strconv.Atoi(v); err == nil && n >= 0 {
			c.WebhookDelay = time.Duration(n) * time.Millisecond
		}
	}
	return c
}

// tokenRequest is the body the adapter posts (gigadat/adapter.go:37-55).
type tokenRequest struct {
	Amount        float64 `json:"amount"`
	Campaign      string  `json:"campaign"`
	Currency      string  `json:"currency"`
	Email         string  `json:"email"`
	Mobile        string  `json:"mobile"`
	Name          string  `json:"name"`
	Province      string  `json:"province"`
	Sandbox       *bool   `json:"sandbox"`
	Site          string  `json:"site"`
	TransactionId string  `json:"transactionId"`
	Type          string  `json:"type"`
	UserId        string  `json:"userId"`
	UserIp        string  `json:"userIp"`
	BankAccount   string  `json:"acct"`
	FiCode        string  `json:"fi"`
	TransitNumber string  `json:"transit"`
}

type transaction struct {
	req       tokenRequest
	token     string
	outcome   stubhttp.Outcome
	finalised bool
}

// Webhook is one status delivery: the query the gateway reads (handler/gigadat.go:56-57) and the
// body's key/value pairs (gigadat/webhook.go:17-21).
type Webhook struct {
	TransactionId string
	Status        string
	Pairs         []KeyValue
}

// KeyValue is one entry of the body's array.
type KeyValue struct {
	Key   string `json:"key"`
	Value string `json:"value"`
}

// Provider holds the routes and the transactions.
type Provider struct {
	stub *stubhttp.Stub
	cfg  Config
	// Send delivers a webhook; replaced in tests.
	Send func(cfg Config, hook Webhook) error

	mu  sync.Mutex
	txs map[string]*transaction
}

// NewProvider makes the provider; webhooks go out over HTTP.
func NewProvider(stub *stubhttp.Stub, cfg Config) *Provider {
	return &Provider{stub: stub, cfg: cfg, Send: deliver, txs: map[string]*transaction{}}
}

// Handler is the provider's routes.
func (p *Provider) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("POST /api/payment-token/{campaignId}", p.paymentToken)
	mux.HandleFunc("GET /webflow", p.webflow)
	mux.HandleFunc("GET /webflow/deposit", p.webflowDeposit)
	return mux
}

func fail(w http.ResponseWriter, status int, msg string) {
	stubhttp.WriteJSON(w, status, map[string]any{"err": msg})
}

func (p *Provider) paymentToken(w http.ResponseWriter, r *http.Request) {
	// adapter.go:90 sends the access and security tokens as HTTP Basic credentials.
	if user, pass, ok := r.BasicAuth(); !ok || user == "" || pass == "" {
		fail(w, http.StatusUnauthorized, "missing access token or security token")
		return
	}
	var req tokenRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		fail(w, http.StatusBadRequest, "malformed JSON body")
		return
	}
	switch {
	case req.Type != "CPI" && req.Type != "ETO" && req.Type != "ACH":
		fail(w, http.StatusBadRequest, "type must be CPI, ETO or ACH")
		return
	case req.Currency != "CAD":
		fail(w, http.StatusBadRequest, "currency must be CAD")
		return
	case req.TransactionId == "" || req.UserId == "" || req.Site == "" || req.Amount <= 0:
		fail(w, http.StatusBadRequest, "transactionId, userId, site and a positive amount are required")
		return
	case req.Campaign != "" && req.Campaign != r.PathValue("campaignId"):
		fail(w, http.StatusBadRequest, "campaign does not match the path")
		return
	}

	tx := &transaction{req: req, token: newToken(req), outcome: p.stub.Next()}
	p.mu.Lock()
	p.txs[req.TransactionId] = tx
	p.mu.Unlock()

	// adapter.go:62-67 reads token and data.transactionId. Gigadat echoes the merchant's
	// transactionId, which is why the webhook's ?transactionId= is the gateway's own txnRef.
	stubhttp.WriteJSON(w, http.StatusOK, map[string]any{
		"token": tx.token,
		"data": map[string]any{
			"transactionId": req.TransactionId,
			"type":          req.Type,
			"amount":        req.Amount,
			"currency":      "CAD",
			"status":        "STATUS_PENDING",
			"campaign":      req.Campaign,
			"userId":        req.UserId,
			"sandbox":       req.Sandbox != nil && *req.Sandbox,
		},
	})

	// A CPI deposit completes when the player finishes at their bank; with no player, it
	// completes after the delay. ETO and ACH payouts wait for the finalize call.
	if req.Type == "CPI" {
		p.schedule(tx, false)
	}
}

func (p *Provider) lookup(r *http.Request) (*transaction, bool) {
	q := r.URL.Query()
	p.mu.Lock()
	defer p.mu.Unlock()
	tx, ok := p.txs[q.Get("transaction")]
	if !ok || tx.token != q.Get("token") {
		return nil, false
	}
	return tx, true
}

// webflow is the player's hosted page for CPI, and the "initiate" call for ETO and ACH
// (adapter.go:275-288, which needs 200).
func (p *Provider) webflow(w http.ResponseWriter, r *http.Request) {
	tx, ok := p.lookup(r)
	if !ok {
		http.Error(w, "unknown transaction or token", http.StatusNotFound)
		return
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	fmt.Fprintf(w, `<!DOCTYPE html><html><body><p>Gigadat stub: Interac %s for %.2f CAD.</p></body></html>`, tx.req.Type, tx.req.Amount)
}

// webflowDeposit is the finalize call for ETO and ACH (adapter.go:291-305, which needs 200); it
// starts the transfer, so the status webhooks follow.
func (p *Provider) webflowDeposit(w http.ResponseWriter, r *http.Request) {
	tx, ok := p.lookup(r)
	if !ok {
		http.Error(w, "unknown transaction or token", http.StatusNotFound)
		return
	}
	p.mu.Lock()
	first := !tx.finalised
	tx.finalised = true
	p.mu.Unlock()
	stubhttp.WriteJSON(w, http.StatusOK, map[string]any{"transactionId": tx.req.TransactionId, "status": "STATUS_INITED"})
	if first && tx.req.Type != "CPI" {
		p.schedule(tx, true)
	}
}

// schedule sends the transaction's webhooks after the delay: STATUS_INITED first for a payout,
// then the terminal status (none when the outcome is pending).
func (p *Provider) schedule(tx *transaction, inited bool) {
	hooks := p.Webhooks(tx.req, tx.outcome, inited)
	time.AfterFunc(p.cfg.WebhookDelay, func() {
		for _, h := range hooks {
			if err := p.Send(p.cfg, h); err != nil {
				log.Printf("gigadat stub: webhook %s for %s failed: %v", h.Status, h.TransactionId, err)
			}
		}
	})
}

// Webhooks are the deliveries one transaction gets for an outcome.
func (p *Provider) Webhooks(req tokenRequest, outcome stubhttp.Outcome, inited bool) []Webhook {
	amount := strconv.FormatFloat(req.Amount, 'f', -1, 64)
	cookie := randomHex(16)
	build := func(status, rcode string) Webhook {
		pairs := []KeyValue{
			{"transactionId", req.TransactionId},
			{"type", req.Type},
			{"amount", amount},
			{"cookie", cookie},
		}
		if rcode != "" { // rcode only at a final status (spec 199)
			pairs = append(pairs, KeyValue{"rcode", rcode})
		}
		if req.Type == "ETO" && rcode == "100" {
			pairs = append(pairs, KeyValue{"recipientName", req.Name}, KeyValue{"recipientEmail", req.Email})
		}
		return Webhook{TransactionId: req.TransactionId, Status: status, Pairs: pairs}
	}
	var out []Webhook
	if inited || outcome == stubhttp.Pending {
		out = append(out, build("STATUS_INITED", ""))
	}
	switch outcome {
	case stubhttp.Success:
		out = append(out, build("STATUS_SUCCESS", "100"))
	case stubhttp.Failure:
		out = append(out, build("STATUS_REJECTED", "231"))
	}
	return out
}

// Encode is the request a webhook is delivered as: URL, content type and body.
func Encode(cfg Config, h Webhook) (target, contentType string, body []byte, err error) {
	u, err := url.Parse(cfg.WebhookUrl)
	if err != nil {
		return "", "", nil, err
	}
	q := u.Query()
	q.Set("transactionId", h.TransactionId)
	q.Set("status", h.Status)
	u.RawQuery = q.Encode()
	arr, _ := json.Marshal(h.Pairs)
	if cfg.WebhookBody == "form" {
		return u.String(), "application/x-www-form-urlencoded", []byte(url.Values{"data": {string(arr)}}.Encode()), nil
	}
	return u.String(), "application/json", arr, nil
}

func deliver(cfg Config, h Webhook) error {
	target, ct, body, err := Encode(cfg, h)
	if err != nil {
		return err
	}
	client := &http.Client{Timeout: 10 * time.Second}
	resp, err := client.Post(target, ct, bytes.NewReader(body))
	if err != nil {
		return err
	}
	resp.Body.Close()
	if resp.StatusCode/100 != 2 {
		return fmt.Errorf("HTTP %d", resp.StatusCode)
	}
	return nil
}

// newToken is a JWT-shaped token (unsigned; the adapter forwards it and never reads it).
func newToken(req tokenRequest) string {
	enc := base64.RawURLEncoding
	header := enc.EncodeToString([]byte(`{"alg":"HS256","typ":"JWT"}`))
	claims, _ := json.Marshal(map[string]any{"transactionId": req.TransactionId, "type": req.Type, "iat": time.Now().Unix()})
	return header + "." + enc.EncodeToString(claims) + "." + enc.EncodeToString([]byte(randomHex(16)))
}

func randomHex(n int) string {
	b := make([]byte, n)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}

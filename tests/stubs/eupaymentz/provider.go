// Package eupaymentz answers as EuPaymentz (Secure1Gateway) does to the original's psp-gateway
// adapter (services/psp-gateway/internal/eupaymentz), so a deposit can run with no request leaving
// the machine. See README.md for the setting that points the gateway here. psp-stub serves it on
// 18104.
package eupaymentz

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"html"
	"log"
	"math/big"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/eitheror-io/ankka-casino/tests/stubs/internal/stubhttp"
)

// Config is what the stub needs that the adapter's requests do not carry.
type Config struct {
	// Passphrase is the terminal's "passphrase" property. The adapter signs account_sha with it
	// and the gateway verifies the webhook's resp_sha with it, so it must equal the passphrase on
	// the EUPAYMENTZ terminal the original routes to. Default "sk-test", the value the original's
	// own webhook test uses (services/psp-gateway/internal/handler/webhook_eupaymentz_test.go:84).
	Passphrase string
	// WebhookURL, when set, replaces merchant_url_callback.
	WebhookURL string
	// WebhookDelay is how long after processTx the outcome is posted to merchant_url_callback.
	WebhookDelay time.Duration
}

// ConfigFromEnv reads EUPAYMENTZ_STUB_PASSPHRASE, EUPAYMENTZ_STUB_WEBHOOK_URL and
// EUPAYMENTZ_STUB_WEBHOOK_DELAY_MS.
func ConfigFromEnv(getenv func(string) string) Config {
	c := Config{Passphrase: "sk-test", WebhookURL: getenv("EUPAYMENTZ_STUB_WEBHOOK_URL"), WebhookDelay: 1500 * time.Millisecond}
	if v := getenv("EUPAYMENTZ_STUB_PASSPHRASE"); v != "" {
		c.Passphrase = v
	}
	if v := getenv("EUPAYMENTZ_STUB_WEBHOOK_DELAY_MS"); v != "" {
		if n, err := strconv.Atoi(v); err == nil && n >= 0 {
			c.WebhookDelay = time.Duration(n) * time.Millisecond
		}
	}
	return c
}

// Start is the stub as psp-stub runs it.
func Start(getenv func(string) string) (*stubhttp.Stub, http.Handler) {
	stub := stubhttp.New("eupaymentz")
	return stub, NewProvider(stub, ConfigFromEnv(getenv)).Handler()
}

// processTxRequest is the body the adapter posts (eupaymentz/adapter.go, processTxRequest).
type processTxRequest struct {
	AccountId           string `json:"account_id"`
	AccountPassword     string `json:"account_password"`
	AccountSha          string `json:"account_sha"`
	ActionType          string `json:"action_type"`
	MerchantPaymentId   string `json:"merchant_payment_id"`
	CustEmail           string `json:"cust_email"`
	TransacAmount       string `json:"transac_amount"`
	TransacCurrencyCode string `json:"transac_currency_code"`
	CustomerIp          string `json:"customer_ip"`
	MerchantUrlReturn   string `json:"merchant_url_return"`
	MerchantUrlCallback string `json:"merchant_url_callback"`
}

// Webhook is the flat JSON EuPaymentz posts to merchant_url_callback (no {"success","data"}
// envelope; see the original's handler/webhook.go:1160-1173).
type Webhook struct {
	RespTransId                string  `json:"resp_trans_id"`
	RespTransMerchantId        string  `json:"resp_trans_merchant_id"`
	RespTransAmount            string  `json:"resp_trans_amount"`
	RespActionType             string  `json:"resp_action_type"`
	RespTransStatus            string  `json:"resp_trans_status"`
	RespTransDetailledStatus   *string `json:"resp_trans_detailled_status"`
	RespTransDescriptionStatus string  `json:"resp_trans_description_status"`
	RespMerchantData1          string  `json:"resp_merchant_data1"`
	RespSha                    string  `json:"resp_sha"`
	RespDescriptor             string  `json:"resp_descriptor"`
}

// Status codes. "00000" is captured and "PEND" is pending (eupaymentz/adapter.go MapStatus); any
// other code is a refusal — the adapter names the T0005*/S0005* families.
const (
	statusCaptured = "00000"
	statusPending  = "PEND"
	statusRefused  = "S00050001"
)

type hosted struct{ returnUrl string }

type payment struct {
	transId, amount, callback string
}

// Provider holds the routes and the hosted sessions.
type Provider struct {
	stub *stubhttp.Stub
	cfg  Config
	// Send posts a webhook; replaced in tests.
	Send func(url string, body []byte) stubhttp.Delivery

	mu       sync.Mutex
	hosted   map[string]hosted
	payments map[string]payment // by merchant_payment_id, the gateway's txnRef
}

// NewProvider makes the provider; webhooks go out over HTTP.
func NewProvider(stub *stubhttp.Stub, cfg Config) *Provider {
	p := &Provider{stub: stub, cfg: cfg, Send: postJSON, hosted: map[string]hosted{}, payments: map[string]payment{}}
	stub.OnWebhook(p.send)
	return p
}

// Handler is the provider's routes.
func (p *Provider) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("POST /api/v2/processTx", p.processTx)
	mux.HandleFunc("GET /api/v2/ProcessTxGetHosted", p.hostedPage)
	return mux
}

// Sign is resp_sha: SHA256(passphrase + resp_trans_id + resp_trans_amount + resp_trans_status),
// lower-case hex, as the original's eupaymentz/signature.go:23-25 verifies it.
func Sign(passphrase, transId, amount, status string) string {
	sum := sha256.Sum256([]byte(passphrase + transId + amount + status))
	return hex.EncodeToString(sum[:])
}

// accountSha is what the adapter sends (eupaymentz/signature.go:14-16):
// SHA256(passphrase + transac_amount + account_id + cust_email + customer_ip).
func accountSha(passphrase string, r processTxRequest) string {
	sum := sha256.Sum256([]byte(passphrase + r.TransacAmount + r.AccountId + r.CustEmail + r.CustomerIp))
	return hex.EncodeToString(sum[:])
}

func refuse(w http.ResponseWriter, code, description string) {
	// EuPaymentz answers request-level errors with success=false; the adapter only checks the flag.
	stubhttp.WriteJSON(w, http.StatusOK, map[string]any{
		"success": false,
		"data": map[string]any{
			"resp_trans_status":             code,
			"resp_trans_description_status": description,
			"ErrorsDescriptionCodes":        []string{description},
		},
	})
}

func (p *Provider) processTx(w http.ResponseWriter, r *http.Request) {
	var req processTxRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		refuse(w, "T00050000", "malformed JSON body")
		return
	}
	switch {
	case req.AccountId == "" || req.AccountPassword == "" || req.MerchantPaymentId == "" || req.TransacAmount == "":
		refuse(w, "T00050001", "missing account_id, account_password, merchant_payment_id or transac_amount")
		return
	case !strings.EqualFold(req.AccountSha, accountSha(p.cfg.Passphrase, req)):
		refuse(w, "T00050002", "account_sha does not match (is EUPAYMENTZ_STUB_PASSPHRASE the terminal's passphrase?)")
		return
	}

	control := p.stub.Control()
	transId := newTransId()
	transkey := randomHex(16)
	callback := req.MerchantUrlCallback
	if p.cfg.WebhookURL != "" {
		callback = p.cfg.WebhookURL
	}
	p.mu.Lock()
	p.hosted[transkey] = hosted{returnUrl: req.MerchantUrlReturn}
	p.payments[req.MerchantPaymentId] = payment{transId: transId, amount: req.TransacAmount, callback: callback}
	p.mu.Unlock()

	scheme := "http"
	if r.TLS != nil {
		scheme = "https"
	}
	redirect := fmt.Sprintf("%s://%s/api/v2/ProcessTxGetHosted?transkey=%s", scheme, r.Host, transkey)

	// Hosted mode always answers PEND with a redirect; the outcome arrives by callback.
	stubhttp.WriteJSON(w, http.StatusOK, map[string]any{
		"success": true,
		"data": map[string]any{
			"UrlToRedirecPostedParameters":  []any{},
			"resp_trans_id":                 transId,
			"resp_trans_merchant_id":        req.MerchantPaymentId,
			"resp_trans_amount":             req.TransacAmount,
			"resp_action_type":              "payment",
			"resp_trans_status":             statusPending,
			"resp_trans_detailled_status":   nil,
			"resp_trans_description_status": nil,
			"resp_merchant_data1":           "",
			"resp_merchant_data2":           "",
			"resp_merchant_data3":           "",
			"resp_merchant_data4":           "",
			"resp_sha":                      Sign(p.cfg.Passphrase, transId, req.TransacAmount, statusPending),
			"resp_descriptor":               "-",
			"ErrorsDescriptionCodes":        nil,
			"isDirectResult":                false,
			"UrlToRedirect":                 redirect,
			"UrlToRedirectMethod":           "GET",
			"ExtraData":                     nil,
		},
	})

	if control.Outcome == stubhttp.Pending {
		p.stub.Hold(req.MerchantPaymentId) // the payment stays at the hosted page: no callback
		return
	}
	if callback == "" {
		return
	}
	ref := req.MerchantPaymentId
	time.AfterFunc(control.Delay(p.cfg.WebhookDelay), func() { _, _ = p.send(ref, control) })
}

// Webhook builds the signed callback for one payment and control: success 00000, failure the
// control's code (default S00050001), pending PEND.
func (p *Provider) Webhook(merchantPaymentId, transId, amount string, c stubhttp.Control) (Webhook, error) {
	var status, description string
	switch c.Outcome {
	case stubhttp.Success:
		status, description = statusCaptured, "Transaction approved"
	case stubhttp.Failure:
		status, description = statusRefused, "Transaction refused by issuer"
		if c.Code != "" {
			status = c.Code
		}
	case stubhttp.Pending:
		status, description = statusPending, "Transaction pending"
	default:
		return Webhook{}, fmt.Errorf("eupaymentz has no callback for %q", c.Outcome)
	}
	return Webhook{
		RespTransId:                transId,
		RespTransMerchantId:        merchantPaymentId,
		RespTransAmount:            amount,
		RespActionType:             "payment",
		RespTransStatus:            status,
		RespTransDescriptionStatus: description,
		RespSha:                    Sign(p.cfg.Passphrase, transId, amount, status),
		RespDescriptor:             "-",
	}, nil
}

func (p *Provider) send(ref string, c stubhttp.Control) (stubhttp.Delivery, error) {
	p.mu.Lock()
	pay, ok := p.payments[ref]
	p.mu.Unlock()
	if !ok {
		return stubhttp.Delivery{}, stubhttp.ErrUnknownReference
	}
	hook, err := p.Webhook(ref, pay.transId, pay.amount, c)
	if err != nil {
		return stubhttp.Delivery{}, err
	}
	if pay.callback == "" {
		return stubhttp.Delivery{}, fmt.Errorf("no merchant_url_callback for %s", ref)
	}
	body, _ := json.Marshal(hook)
	d := p.Send(pay.callback, body)
	log.Printf("eupaymentz stub: webhook %s for %s to %s answered %d (err=%v)", hook.RespTransStatus, ref, pay.callback, d.Status, d.Err)
	return d, nil
}

// hostedPage stands in for the card page the player is redirected to: it sends them back.
func (p *Provider) hostedPage(w http.ResponseWriter, r *http.Request) {
	p.mu.Lock()
	h, ok := p.hosted[r.URL.Query().Get("transkey")]
	p.mu.Unlock()
	if !ok {
		http.Error(w, "unknown transkey", http.StatusNotFound)
		return
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	fmt.Fprintf(w, `<!DOCTYPE html><html><body><p>EuPaymentz stub hosted page.</p><a id="return" href="%s">Return to merchant</a></body></html>`,
		html.EscapeString(h.returnUrl))
}

var client = &http.Client{Timeout: 10 * time.Second}

func postJSON(url string, body []byte) stubhttp.Delivery {
	return stubhttp.Post(client, url, "application/json", nil, body)
}

// newTransId is EuPaymentz's id shape: "T" and 19 digits.
func newTransId() string {
	n, _ := rand.Int(rand.Reader, new(big.Int).Exp(big.NewInt(10), big.NewInt(19), nil))
	return fmt.Sprintf("T%019d", n)
}

func randomHex(n int) string {
	b := make([]byte, n)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}

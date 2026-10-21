// Package cozeni is the Cozeni stub: it answers the original psp-gateway's Cozeni adapter as
// Cozeni would (exchange rate, pay-in, payout) and calls the gateway back with the signed outcome.
// psp-stub serves it on 18102.
package cozeni

// The routes Cozeni answers for the original's adapter,
// eitheror-platform/services/psp-gateway/internal/cozeni/adapter.go:
//
//   GET  /pub/rate/{crypto}/{fiat}          (adapter.go:65-101)  {"rate": n}, fiat value of 1 crypto
//   POST /mxapi/{merchantId}/payintxn       (adapter.go:176-209) 200 with paymentAddress, or 422
//   POST /mxapi/{merchantId}/payouttxn      (adapter.go:351-389) 200 with txnId and resultCode
//   GET  /mxapi/{merchantId}/payintxn/{id}  status query (signature.go:16-21; the gateway never calls it)
//
// The outcome reaches the gateway by callback to the callbackUrl the adapter sends in the request
// body: <PSP_GATEWAY_BASE_URL>/v1/webhooks/cozeni for a pay-in (adapter.go:157) and
// .../v1/webhooks/cozeni/withdrawal for a payout (handler/payout.go:136). Cozeni's callback is
// application/x-www-form-urlencoded with the token in a "txnauthz" form field
// (handler/webhook.go:977-1032, 1271-1375). The token is SHA-256(merchantId + requestSalt +
// paymentRef + apiKey) (signature.go:9-14, 28-31) — exactly the X-cozeni-txnauthz header the
// adapter put on the request, so the stub returns that header's value and needs no API key.
//
// A completed pay-in whose amount differs from the invoice carries resultCode 02 (overpayment) or
// 03 (underpayment) with receivedAmount and convertAmount (adapter.go:254-256,
// handler/webhook.go:1066-1125).

import (
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"math/big"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/eitheror-io/ankka-casino/tests/stubs/internal/stubhttp"
)

// Config is what the stub needs beyond the request itself.
type Config struct {
	// APIKey, when set, makes the stub check X-cozeni-txnauthz against it as Cozeni does. Empty:
	// the header must be present but is not checked (the callback echoes it either way).
	APIKey string
	// WebhookURL, when set, replaces the callbackUrl's address up to /v1/webhooks/cozeni (a
	// payout's /withdrawal is kept).
	WebhookURL string
	// Delay is how long after initiation the callback is sent.
	Delay time.Duration
}

// ConfigFromEnv reads COZENI_STUB_* variables.
func ConfigFromEnv(getenv func(string) string) Config {
	c := Config{APIKey: getenv("COZENI_STUB_API_KEY"), WebhookURL: getenv("COZENI_STUB_WEBHOOK_URL"), Delay: 500 * time.Millisecond}
	if v := getenv("COZENI_STUB_WEBHOOK_DELAY_MS"); v != "" {
		if n, err := strconv.Atoi(v); err == nil {
			c.Delay = time.Duration(n) * time.Millisecond
		}
	}
	return c
}

// Start is the stub as psp-stub runs it.
func Start(getenv func(string) string) (*stubhttp.Stub, http.Handler) {
	stub := stubhttp.New("cozeni")
	return stub, NewProvider(stub, ConfigFromEnv(getenv)).Routes()
}

// Rates are the fiat value of one unit of each crypto, whatever the fiat. Anything else is 1.
var Rates = map[string]float64{"BTC": 65000, "ETH": 3000, "LTC": 80, "TRX": 0.12, "USDT": 1, "USDC": 1}

func rate(crypto string) float64 {
	if r, ok := Rates[strings.ToUpper(crypto)]; ok {
		return r
	}
	return 1
}

// Provider answers Cozeni's API.
type Provider struct {
	stub   *stubhttp.Stub
	cfg    Config
	client *http.Client

	mu   sync.Mutex
	txns map[int64]map[string]any
	refs map[string]*txn // by paymentRef, the gateway's reference
}

// txn is what the stub needs to call back for one pay-in or payout.
type txn struct {
	id       int64
	payout   bool
	ref      string
	token    string
	target   string
	amount   float64 // the crypto amount requested (pay-in) or the payout amount
	currency string
	convert  string
}

func NewProvider(stub *stubhttp.Stub, cfg Config) *Provider {
	p := &Provider{stub: stub, cfg: cfg, client: &http.Client{Timeout: 10 * time.Second},
		txns: map[int64]map[string]any{}, refs: map[string]*txn{}}
	stub.Supports(stubhttp.Duplicate, stubhttp.Underpayment, stubhttp.Overpayment)
	stub.OnWebhook(p.send)
	return p
}

func (p *Provider) Routes() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /pub/rate/{crypto}/{fiat}", func(w http.ResponseWriter, r *http.Request) {
		stubhttp.WriteJSON(w, http.StatusOK, map[string]float64{"rate": rate(r.PathValue("crypto"))})
	})
	mux.HandleFunc("POST /mxapi/{merchantId}/payintxn", p.payIn)
	mux.HandleFunc("POST /mxapi/{merchantId}/payouttxn", p.payOut)
	mux.HandleFunc("GET /mxapi/{merchantId}/payintxn/{txnId}", p.query)
	return mux
}

type payInRequest struct {
	AccountId       string  `json:"accountId"`
	RequestSalt     string  `json:"requestSalt"`
	RequestAmount   float64 `json:"requestAmount"`
	RequestCurrency string  `json:"requestCurrency"`
	ConvertCurrency string  `json:"convertCurrency"`
	PaymentRef      string  `json:"paymentRef"`
	CallbackUrl     string  `json:"callbackUrl"`
}

type payOutRequest struct {
	AccountId      string  `json:"accountId"`
	RequestSalt    string  `json:"requestSalt"`
	Amount         float64 `json:"amount"`
	Currency       string  `json:"currency"`
	PayoutCurrency string  `json:"payoutCurrency"`
	PaymentRef     string  `json:"paymentRef"`
	CallbackUrl    string  `json:"callbackUrl"`
	PaymentAddress string  `json:"paymentAddress"`
}

func reject(w http.ResponseWriter, code, text string) {
	stubhttp.WriteJSON(w, http.StatusUnprocessableEntity, map[string]string{"msgCode": code, "msgText": text})
}

// authorise checks X-cozeni-txnauthz as Cozeni does: SHA-256(merchantId+requestSalt+paymentRef+apiKey).
func (p *Provider) authorise(r *http.Request, salt, ref string) (string, bool) {
	token := r.Header.Get("X-cozeni-txnauthz")
	if token == "" {
		return "", false
	}
	if p.cfg.APIKey == "" {
		return token, true
	}
	want := Token(r.PathValue("merchantId"), salt, ref, p.cfg.APIKey)
	return token, subtle.ConstantTimeCompare([]byte(want), []byte(token)) == 1
}

// Token is Cozeni's txnauthz derivation (signature.go:9-14).
func Token(merchantId, salt, ref, apiKey string) string {
	h := sha256.Sum256([]byte(merchantId + salt + ref + apiKey))
	return hex.EncodeToString(h[:])
}

// The stub's answer to a repeated paymentRef under dupCheck. Cozeni's own code for it is not in
// the original (the adapter reports any 422 as "pay-in rejected <msgCode>: <msgText>").
const duplicateCode, duplicateText = "13", "Duplicate paymentRef"

func (p *Provider) payIn(w http.ResponseWriter, r *http.Request) {
	var req payInRequest
	body, _ := io.ReadAll(r.Body)
	if err := json.Unmarshal(body, &req); err != nil {
		reject(w, "10", "invalid request body")
		return
	}
	token, ok := p.authorise(r, req.RequestSalt, req.PaymentRef)
	if !ok {
		reject(w, "11", "invalid txnauthz")
		return
	}
	if req.AccountId == "" || req.RequestSalt == "" || req.PaymentRef == "" || req.RequestAmount <= 0 {
		reject(w, "12", "accountId, requestSalt, paymentRef and a positive requestAmount are required")
		return
	}
	control := p.stub.Control()
	if control.Outcome == stubhttp.Duplicate {
		reject(w, duplicateCode, duplicateText)
		return
	}
	txnId := newTxnId()
	answer := map[string]any{
		"txnId":           txnId,
		"status":          "PENDING",
		"paymentRef":      req.PaymentRef,
		"resultCode":      "01",
		"resultMsg":       "Pending",
		"requestAmount":   req.RequestAmount,
		"requestCurrency": req.RequestCurrency,
		"convertCurrency": req.ConvertCurrency,
		"conversionRate":  rate(req.RequestCurrency),
		"paymentAddress":  walletAddress(req.RequestCurrency),
		"validUntil":      time.Now().UTC().Add(time.Hour).Format(time.RFC3339),
	}
	p.remember(txnId, answer, &txn{id: txnId, ref: req.PaymentRef, token: token,
		target: stubhttp.Retarget(req.CallbackUrl, p.cfg.WebhookURL, "/v1/webhooks/cozeni"),
		amount: req.RequestAmount, currency: req.RequestCurrency, convert: req.ConvertCurrency})
	stubhttp.WriteJSON(w, http.StatusOK, answer)
	p.later(req.PaymentRef, control)
}

func (p *Provider) payOut(w http.ResponseWriter, r *http.Request) {
	var req payOutRequest
	body, _ := io.ReadAll(r.Body)
	if err := json.Unmarshal(body, &req); err != nil {
		reject(w, "10", "invalid request body")
		return
	}
	token, ok := p.authorise(r, req.RequestSalt, req.PaymentRef)
	if !ok {
		reject(w, "11", "invalid txnauthz")
		return
	}
	if req.PaymentAddress == "" || req.PaymentRef == "" || req.Amount <= 0 {
		reject(w, "92", "invalid address")
		return
	}
	control := p.stub.Control()
	if control.Outcome == stubhttp.Duplicate {
		reject(w, duplicateCode, duplicateText)
		return
	}
	txnId := newTxnId()
	answer := map[string]any{"txnId": txnId, "paymentRef": req.PaymentRef, "status": "PENDING", "resultCode": "01", "resultMsg": "Pending"}
	if control.Outcome == stubhttp.Failure {
		// handler/payout.go:174 → MapPayoutResultCode: anything but 00/01 is REJECTED at once.
		code := control.Code
		if code == "" {
			code = "93"
		}
		answer = map[string]any{"txnId": txnId, "paymentRef": req.PaymentRef, "status": "FAILED", "resultCode": code, "resultMsg": "Insufficient balance"}
	}
	p.remember(txnId, answer, &txn{id: txnId, payout: true, ref: req.PaymentRef, token: token,
		target: stubhttp.Retarget(req.CallbackUrl, p.cfg.WebhookURL, "/v1/webhooks/cozeni"),
		amount: req.Amount, currency: req.PayoutCurrency})
	stubhttp.WriteJSON(w, http.StatusOK, answer)
	if control.Outcome == stubhttp.Failure {
		return // rejected in the answer: no callback
	}
	p.later(req.PaymentRef, control)
}

// Form is the callback for one transaction under a control.
func (t *txn) Form(c stubhttp.Control) (url.Values, error) {
	form := url.Values{}
	form.Set("txnId", strconv.FormatInt(t.id, 10))
	form.Set("paymentRef", t.ref)
	form.Set("txnauthz", t.token)
	if !t.payout {
		form.Set("requestAmount", fmt.Sprintf("%.8f", t.amount))
		form.Set("requestCurrency", t.currency)
	}
	fiat := t.amount * rate(t.currency)
	completed := func(code, msg string, received, convert float64) {
		form.Set("status", "COMPLETED")
		form.Set("resultCode", code)
		form.Set("resultMsg", msg)
		form.Set("txnHash", "0x"+randomHex(32))
		if t.payout {
			return
		}
		form.Set("receivedAmount", or(c.ReceivedAmount, fmt.Sprintf("%.8f", received)))
		form.Set("convertAmount", or(c.ConvertAmount, fmt.Sprintf("%.2f", convert)))
		form.Set("convertCurrency", t.convert)
	}
	switch c.Outcome {
	case stubhttp.Success:
		completed("00", "Success", t.amount, fiat)
	case stubhttp.Overpayment:
		if t.payout {
			return nil, fmt.Errorf("a cozeni payout has no overpayment")
		}
		completed("02", "Overpayment", t.amount*1.1, fiat*1.1)
	case stubhttp.Underpayment:
		if t.payout {
			return nil, fmt.Errorf("a cozeni payout has no underpayment")
		}
		completed("03", "Underpayment", t.amount*0.9, fiat*0.9)
	case stubhttp.Failure:
		status, code := "CANCELLED", c.Code
		if t.payout {
			status = "FAILED"
		}
		if code == "" {
			code = "99"
		}
		form.Set("status", status)
		form.Set("resultCode", code)
		form.Set("resultMsg", "Payment not received before validUntil")
	case stubhttp.Pending:
		form.Set("status", "PENDING")
		form.Set("resultCode", "01")
		form.Set("resultMsg", "Pending")
	default:
		return nil, fmt.Errorf("cozeni has no callback for %q", c.Outcome)
	}
	return form, nil
}

func or(n json.Number, fallback string) string {
	if n != "" {
		return n.String()
	}
	return fallback
}

func (p *Provider) query(w http.ResponseWriter, r *http.Request) {
	id, _ := strconv.ParseInt(r.PathValue("txnId"), 10, 64)
	p.mu.Lock()
	txn, ok := p.txns[id]
	p.mu.Unlock()
	if !ok {
		reject(w, "20", "transaction not found")
		return
	}
	stubhttp.WriteJSON(w, http.StatusOK, txn)
}

func (p *Provider) remember(id int64, answer map[string]any, t *txn) {
	p.mu.Lock()
	p.txns[id] = answer
	p.refs[t.ref] = t
	p.mu.Unlock()
}

// later sends the callback after the delay; pending sends nothing until settled.
func (p *Provider) later(ref string, c stubhttp.Control) {
	if c.Outcome == stubhttp.Pending {
		p.stub.Hold(ref)
		return
	}
	go func() {
		time.Sleep(c.Delay(p.cfg.Delay))
		_, _ = p.send(ref, c)
	}()
}

// send delivers the callback for one paymentRef now and records the final state for the query.
func (p *Provider) send(ref string, c stubhttp.Control) (stubhttp.Delivery, error) {
	p.mu.Lock()
	t, ok := p.refs[ref]
	p.mu.Unlock()
	if !ok {
		return stubhttp.Delivery{}, stubhttp.ErrUnknownReference
	}
	form, err := t.Form(c)
	if err != nil {
		return stubhttp.Delivery{}, err
	}
	if t.target == "" {
		return stubhttp.Delivery{}, fmt.Errorf("txn %d has no callbackUrl", t.id)
	}
	p.mu.Lock()
	if answer, ok := p.txns[t.id]; ok {
		for _, k := range []string{"status", "resultCode", "resultMsg", "txnHash", "receivedAmount", "convertAmount"} {
			if v := form.Get(k); v != "" {
				answer[k] = v
			}
		}
	}
	p.mu.Unlock()
	d := stubhttp.Post(p.client, t.target, "application/x-www-form-urlencoded", nil, []byte(form.Encode()))
	log.Printf("cozeni stub: callback %s/%s for %s to %s answered %d (err=%v)", form.Get("status"), form.Get("resultCode"), ref, t.target, d.Status, d.Err)
	return d, nil
}

func newTxnId() int64 {
	n, _ := rand.Int(rand.Reader, big.NewInt(900_000_000))
	return n.Int64() + 100_000_000
}

func walletAddress(crypto string) string {
	switch strings.ToUpper(crypto) {
	case "BTC":
		return "bc1q" + randomHex(19)
	case "TRX":
		return "T" + randomHex(16)
	default:
		return "0x" + randomHex(20)
	}
}

func randomHex(n int) string {
	b := make([]byte, n)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}

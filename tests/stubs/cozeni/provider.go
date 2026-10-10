package main

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
	"os"
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
	// Delay is how long after initiation the callback is sent.
	Delay time.Duration
}

// ConfigFromEnv reads COZENI_STUB_* variables.
func ConfigFromEnv() Config {
	c := Config{APIKey: os.Getenv("COZENI_STUB_API_KEY"), Delay: 500 * time.Millisecond}
	if v := os.Getenv("COZENI_STUB_WEBHOOK_DELAY_MS"); v != "" {
		if n, err := strconv.Atoi(v); err == nil {
			c.Delay = time.Duration(n) * time.Millisecond
		}
	}
	return c
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
}

// Callback is one delivery the stub made.
type Callback struct {
	URL    string
	Form   url.Values
	Status int
	Err    error
}

func NewProvider(stub *stubhttp.Stub, cfg Config) *Provider {
	return &Provider{stub: stub, cfg: cfg, client: &http.Client{Timeout: 10 * time.Second}, txns: map[int64]map[string]any{}}
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
	p.remember(txnId, answer)
	stubhttp.WriteJSON(w, http.StatusOK, answer)

	form := url.Values{}
	form.Set("txnId", strconv.FormatInt(txnId, 10))
	form.Set("paymentRef", req.PaymentRef)
	form.Set("requestAmount", fmt.Sprintf("%.8f", req.RequestAmount))
	form.Set("requestCurrency", req.RequestCurrency)
	form.Set("txnauthz", token)
	switch p.stub.Next() {
	case stubhttp.Success:
		form.Set("status", "COMPLETED")
		form.Set("resultCode", "00")
		form.Set("resultMsg", "Success")
		form.Set("receivedAmount", fmt.Sprintf("%.8f", req.RequestAmount))
		form.Set("convertAmount", fmt.Sprintf("%.2f", req.RequestAmount*rate(req.RequestCurrency)))
		form.Set("convertCurrency", req.ConvertCurrency)
		form.Set("txnHash", "0x"+randomHex(32))
	case stubhttp.Failure:
		form.Set("status", "CANCELLED")
		form.Set("resultCode", "99")
		form.Set("resultMsg", "Payment not received before validUntil")
	default:
		return // pending: no callback until the scenario says so
	}
	p.later(txnId, req.CallbackUrl, form)
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
	txnId := newTxnId()
	outcome := p.stub.Next()
	answer := map[string]any{"txnId": txnId, "paymentRef": req.PaymentRef, "status": "PENDING", "resultCode": "01", "resultMsg": "Pending"}
	if outcome == stubhttp.Failure {
		// handler/payout.go:174 → MapPayoutResultCode: anything but 00/01 is REJECTED at once.
		answer = map[string]any{"txnId": txnId, "paymentRef": req.PaymentRef, "status": "FAILED", "resultCode": "93", "resultMsg": "Insufficient balance"}
	}
	p.remember(txnId, answer)
	stubhttp.WriteJSON(w, http.StatusOK, answer)
	if outcome != stubhttp.Success {
		return
	}
	form := url.Values{}
	form.Set("txnId", strconv.FormatInt(txnId, 10))
	form.Set("paymentRef", req.PaymentRef)
	form.Set("status", "COMPLETED")
	form.Set("resultCode", "00")
	form.Set("resultMsg", "Success")
	form.Set("txnHash", "0x"+randomHex(32))
	form.Set("txnauthz", token)
	p.later(txnId, req.CallbackUrl, form)
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

func (p *Provider) remember(id int64, txn map[string]any) {
	p.mu.Lock()
	p.txns[id] = txn
	p.mu.Unlock()
}

// later sends the callback after the delay and records the final state for the status query.
func (p *Provider) later(txnId int64, target string, form url.Values) {
	if target == "" {
		log.Printf("cozeni stub: txn %d has no callbackUrl — nothing sent", txnId)
		return
	}
	go func() {
		time.Sleep(p.cfg.Delay)
		p.mu.Lock()
		if txn, ok := p.txns[txnId]; ok {
			for _, k := range []string{"status", "resultCode", "resultMsg", "txnHash", "receivedAmount", "convertAmount"} {
				if v := form.Get(k); v != "" {
					txn[k] = v
				}
			}
		}
		p.mu.Unlock()
		result := Callback{URL: target, Form: form}
		resp, err := p.client.PostForm(target, form)
		if err == nil {
			result.Status = resp.StatusCode
			_ = resp.Body.Close()
		}
		result.Err = err
		log.Printf("cozeni stub: callback to %s answered %d (err=%v)", target, result.Status, err)
	}()
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

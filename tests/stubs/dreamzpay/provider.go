// Package dreamzpay is the DreamzPay stub: it stands in for DreamzPay's hosted checkout, which the
// original psp-gateway's relay page makes the player's browser POST to, and sends the signed
// notification back to the gateway. psp-stub serves it on 18103.
package dreamzpay

// DreamzPay is a hosted checkout the gateway never calls server-to-server. The adapter
// (eitheror-platform/services/psp-gateway/internal/dreamzpay/adapter.go:52-74) builds form fields;
// the gateway's relay page (handler/hpp.go:1006-1044, dreamzpay/relay.html) makes the player's
// browser POST them to {DREAMZPAY_BASE_URL}/transaction/Checkout. So the one route here is:
//
//   POST /transaction/Checkout  (form: memberId, totype, amount, currency, merchantTransactionId,
//                                merchantRedirectUrl, notificationUrl, checksum, terminalId)
//
// It answers with a page that auto-POSTs the browser back to merchantRedirectUrl (the return
// handler, handler/hpp.go:902-998, reads paymentId, status Y/N, amount, checksum), and sends the
// notification to notificationUrl (POST /v1/webhooks/dreamzpay, cmd/psp-gateway/main.go:163;
// handler/webhook.go:49-117, 137-243), form-encoded, with transactionStatus Y/N and the checksum
// MD5(paymentId|merchantTransactionId|amount|Y-or-N|secureKey) (dreamzpay/signature.go:16-26).

import (
	"crypto/md5" //nolint:gosec // DreamzPay mandates MD5
	"crypto/rand"
	"fmt"
	"html/template"
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

// The default secureKey is the one the original's k6 payments seed registers for DreamzPay
// (eitheror-platform/tests/k6/lib/payments.js:153). The Go e2e suite takes it from
// DREAMZPAY_SECURE_KEY (tests/e2e/internal/setup/setup.go:96-105), so set both to the same value.
const defaultSecureKey = "dreamzpay-test-key"

// Config is what the stub needs beyond the form itself.
type Config struct {
	// SecureKey is the terminal's secureKey: it checks the inbound form's checksum and signs the
	// return POST and the notification.
	SecureKey string
	// NotificationURL overrides the notificationUrl the form carries (empty: use the form's).
	NotificationURL string
	// Delay is how long after checkout the notification is sent.
	Delay time.Duration
}

// ConfigFromEnv reads DREAMZPAY_STUB_* variables.
func ConfigFromEnv(getenv func(string) string) Config {
	c := Config{
		SecureKey:       envOr(getenv, "DREAMZPAY_STUB_SECURE_KEY", defaultSecureKey),
		NotificationURL: envOr(getenv, "DREAMZPAY_STUB_NOTIFICATION_URL", getenv("DREAMZPAY_STUB_WEBHOOK_URL")),
		Delay:           500 * time.Millisecond,
	}
	if v := getenv("DREAMZPAY_STUB_WEBHOOK_DELAY_MS"); v != "" {
		if n, err := strconv.Atoi(v); err == nil {
			c.Delay = time.Duration(n) * time.Millisecond
		}
	}
	return c
}

// Start is the stub as psp-stub runs it.
func Start(getenv func(string) string) (*stubhttp.Stub, http.Handler) {
	stub := stubhttp.New("dreamzpay")
	return stub, NewProvider(stub, ConfigFromEnv(getenv)).Routes()
}

// Provider answers DreamzPay's checkout.
type Provider struct {
	stub   *stubhttp.Stub
	cfg    Config
	client *http.Client

	mu       sync.Mutex
	payments map[string]payment // by merchantTransactionId, the gateway's txnRef
}

type payment struct {
	paymentId, amount, currency, target string
}

func NewProvider(stub *stubhttp.Stub, cfg Config) *Provider {
	p := &Provider{stub: stub, cfg: cfg, client: &http.Client{Timeout: 10 * time.Second}, payments: map[string]payment{}}
	stub.OnWebhook(p.send)
	return p
}

func (p *Provider) Routes() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("POST /transaction/Checkout", p.checkout)
	return mux
}

func md5hex(parts ...string) string {
	return fmt.Sprintf("%x", md5.Sum([]byte(strings.Join(parts, "|")))) //nolint:gosec
}

// OutboundChecksum is what the adapter sends (dreamzpay/signature.go:9-14).
func OutboundChecksum(memberId, totype, amount, txnRef, redirectUrl, secureKey string) string {
	return md5hex(memberId, totype, amount, txnRef, redirectUrl, secureKey)
}

// InboundChecksum is what DreamzPay sends back (dreamzpay/signature.go:16-21).
func InboundChecksum(paymentId, txnRef, amount, status, secureKey string) string {
	return md5hex(paymentId, txnRef, amount, status, secureKey)
}

var returnPage = template.Must(template.New("return").Parse(`<!DOCTYPE html>
<html lang="en"><head><meta charset="UTF-8"><title>DreamzPay (stub)</title></head>
<body onload="document.getElementById('return-form').submit()">
<p>DreamzPay stub: payment {{.Outcome}}. Returning to the merchant…</p>
<form id="return-form" action="{{.Action}}" method="POST">
{{range $k, $v := .Fields}}<input type="hidden" name="{{$k}}" value="{{$v}}">
{{end}}</form></body></html>`))

// yn is DreamzPay's canonical status and descriptive status for a control.
func yn(c stubhttp.Control) (string, string, error) {
	switch c.Outcome {
	case stubhttp.Success:
		return "Y", "capturesuccess", nil
	case stubhttp.Failure:
		return "N", "capturefailed", nil
	default:
		return "", "", fmt.Errorf("dreamzpay has no notification for %q", c.Outcome)
	}
}

func (p *Provider) checkout(w http.ResponseWriter, r *http.Request) {
	if err := r.ParseForm(); err != nil {
		http.Error(w, "invalid form", http.StatusBadRequest)
		return
	}
	f := r.PostForm
	txnRef, amount, back := f.Get("merchantTransactionId"), f.Get("amount"), f.Get("merchantRedirectUrl")
	for _, k := range []string{"memberId", "totype", "amount", "currency", "merchantTransactionId", "merchantRedirectUrl", "checksum"} {
		if f.Get(k) == "" {
			http.Error(w, "DreamzPay: missing "+k, http.StatusBadRequest)
			return
		}
	}
	if f.Get("checksum") != OutboundChecksum(f.Get("memberId"), f.Get("totype"), amount, txnRef, back, p.cfg.SecureKey) {
		log.Printf("dreamzpay stub: checksum mismatch for %s — is DREAMZPAY_STUB_SECURE_KEY the terminal's secureKey?", txnRef)
		http.Error(w, "DreamzPay: checksum mismatch", http.StatusBadRequest)
		return
	}

	target := p.cfg.NotificationURL
	if target == "" {
		target = f.Get("notificationUrl")
	}
	pay := payment{paymentId: newPaymentId(), amount: amount, currency: f.Get("currency"), target: target}
	p.mu.Lock()
	p.payments[txnRef] = pay
	p.mu.Unlock()

	control := p.stub.Control()
	if control.Outcome == stubhttp.Pending {
		p.stub.Hold(txnRef)
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_, _ = fmt.Fprint(w, "<!DOCTYPE html><html><body><p>DreamzPay stub: payment pending.</p></body></html>")
		return
	}
	status, _, _ := yn(control)

	// The browser return carries the canonical Y/N in "status" (handler/hpp.go:913-918).
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	_ = returnPage.Execute(w, map[string]any{
		"Outcome": control.Outcome,
		"Action":  back,
		"Fields": map[string]string{
			"paymentId": pay.paymentId, "merchantTransactionId": txnRef, "amount": amount,
			"currency": pay.currency, "status": status, "checksum": InboundChecksum(pay.paymentId, txnRef, amount, status, p.cfg.SecureKey),
		},
	})
	go func() {
		time.Sleep(control.Delay(p.cfg.Delay))
		_, _ = p.send(txnRef, control)
	}()
}

// send delivers the notification for one payment now. It carries both a descriptive "status" and
// the canonical "transactionStatus", and the checksum is over the latter (handler/webhook.go:148-160).
func (p *Provider) send(txnRef string, c stubhttp.Control) (stubhttp.Delivery, error) {
	p.mu.Lock()
	pay, ok := p.payments[txnRef]
	p.mu.Unlock()
	if !ok {
		return stubhttp.Delivery{}, stubhttp.ErrUnknownReference
	}
	status, descriptive, err := yn(c)
	if err != nil {
		return stubhttp.Delivery{}, err
	}
	if pay.target == "" {
		return stubhttp.Delivery{}, fmt.Errorf("no notificationUrl for %s", txnRef)
	}
	note := url.Values{}
	note.Set("paymentId", pay.paymentId)
	note.Set("merchantTransactionId", txnRef)
	note.Set("amount", pay.amount)
	note.Set("currency", pay.currency)
	note.Set("status", descriptive)
	note.Set("transactionStatus", status)
	note.Set("checksum", InboundChecksum(pay.paymentId, txnRef, pay.amount, status, p.cfg.SecureKey))
	d := stubhttp.Post(p.client, pay.target, "application/x-www-form-urlencoded", nil, []byte(note.Encode()))
	log.Printf("dreamzpay stub: notification %s for %s to %s answered %d (err=%v)", status, txnRef, pay.target, d.Status, d.Err)
	return d, nil
}

func newPaymentId() string {
	n, _ := rand.Int(rand.Reader, big.NewInt(90_000_000))
	return strconv.FormatInt(n.Int64()+10_000_000, 10)
}

func envOr(getenv func(string) string, name, fallback string) string {
	if v := getenv(name); v != "" {
		return v
	}
	return fallback
}

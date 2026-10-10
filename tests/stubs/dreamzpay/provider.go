package main

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
	"os"
	"strconv"
	"strings"
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
func ConfigFromEnv() Config {
	c := Config{
		SecureKey:       envOr("DREAMZPAY_STUB_SECURE_KEY", defaultSecureKey),
		NotificationURL: os.Getenv("DREAMZPAY_STUB_NOTIFICATION_URL"),
		Delay:           500 * time.Millisecond,
	}
	if v := os.Getenv("DREAMZPAY_STUB_WEBHOOK_DELAY_MS"); v != "" {
		if n, err := strconv.Atoi(v); err == nil {
			c.Delay = time.Duration(n) * time.Millisecond
		}
	}
	return c
}

// Provider answers DreamzPay's checkout.
type Provider struct {
	stub   *stubhttp.Stub
	cfg    Config
	client *http.Client
}

// Notification is one server-to-server delivery the stub made.
type Notification struct {
	URL    string
	Form   url.Values
	Status int
	Err    error
}

func NewProvider(stub *stubhttp.Stub, cfg Config) *Provider {
	return &Provider{stub: stub, cfg: cfg, client: &http.Client{Timeout: 10 * time.Second}}
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

	outcome := p.stub.Next()
	if outcome == stubhttp.Pending {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_, _ = fmt.Fprint(w, "<!DOCTYPE html><html><body><p>DreamzPay stub: payment pending.</p></body></html>")
		return
	}
	yn, descriptive := "Y", "capturesuccess"
	if outcome == stubhttp.Failure {
		yn, descriptive = "N", "capturefailed"
	}
	paymentId := newPaymentId()
	checksum := InboundChecksum(paymentId, txnRef, amount, yn, p.cfg.SecureKey)

	// The browser return carries the canonical Y/N in "status" (handler/hpp.go:913-918).
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	_ = returnPage.Execute(w, map[string]any{
		"Outcome": outcome,
		"Action":  back,
		"Fields": map[string]string{
			"paymentId": paymentId, "merchantTransactionId": txnRef, "amount": amount,
			"currency": f.Get("currency"), "status": yn, "checksum": checksum,
		},
	})

	// The notification carries both a descriptive "status" and the canonical "transactionStatus",
	// and the checksum is over the latter (handler/webhook.go:148-160).
	target := p.cfg.NotificationURL
	if target == "" {
		target = f.Get("notificationUrl")
	}
	if target == "" {
		log.Printf("dreamzpay stub: no notificationUrl for %s — nothing sent", txnRef)
		return
	}
	note := url.Values{}
	note.Set("paymentId", paymentId)
	note.Set("merchantTransactionId", txnRef)
	note.Set("amount", amount)
	note.Set("currency", f.Get("currency"))
	note.Set("status", descriptive)
	note.Set("transactionStatus", yn)
	note.Set("checksum", checksum)
	go func() {
		time.Sleep(p.cfg.Delay)
		result := Notification{URL: target, Form: note}
		resp, err := p.client.PostForm(target, note)
		if err == nil {
			result.Status = resp.StatusCode
			_ = resp.Body.Close()
		}
		result.Err = err
		log.Printf("dreamzpay stub: notification to %s answered %d (err=%v)", target, result.Status, err)
	}()
}

func newPaymentId() string {
	n, _ := rand.Int(rand.Reader, big.NewInt(90_000_000))
	return strconv.FormatInt(n.Int64()+10_000_000, 10)
}

func envOr(name, fallback string) string {
	if v := os.Getenv(name); v != "" {
		return v
	}
	return fallback
}

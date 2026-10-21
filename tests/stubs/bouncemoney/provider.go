// Package bouncemoney is the BounceMoney stub: it answers the original psp-gateway's BounceMoney
// adapter as BounceMoney would, and sends the signed webhook back to the gateway. psp-stub serves
// it on 18101.
package bouncemoney

// The routes BounceMoney answers for the original's adapter,
// eitheror-platform/services/psp-gateway/internal/bouncemoney/adapter.go:
//
//   POST {base}/checkout/sessions   (adapter.go:148) — create a hosted checkout session; the adapter
//                                   requires HTTP 201 (adapter.go:170) and reads data.url
//                                   (adapter.go:73-81, 183).
//
// The adapter does not poll. The outcome reaches the gateway only by webhook, POST
// /v1/webhooks/bouncemoney (cmd/psp-gateway/main.go:213), signed
// "t=<unix>,v1=base64url(HMAC-SHA256(signingSecret, "<t>." + body))" (signature.go:35-63).

import (
	"crypto/ed25519"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"encoding/pem"
	"fmt"
	"io"
	"log"
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
	// SigningSecret signs the webhook. It must equal the terminal's "signingSecret" property the
	// gateway re-reads on every webhook (internal/handler/webhook.go:1501-1513).
	SigningSecret string
	// WebhookURL overrides where the webhook goes. Empty: derived from the request's redirectUrl,
	// which the adapter builds as <PSP_GATEWAY_BASE_URL>/v1/psp/checkout/bouncemoney/<txnRef>/return
	// (adapter.go:118) — the webhook route is on the same gateway at /v1/webhooks/bouncemoney.
	WebhookURL string
	// PublicKey, when set, verifies the adapter's Ed25519 request signature (signature.go:17-33).
	PublicKey ed25519.PublicKey
	// PublicURL is how a browser reaches this stub, for the hosted-page URL answered in data.url.
	PublicURL string
	// Delay is how long after the session is created the webhook is sent.
	Delay time.Duration
}

// The default signing secret is the one the original's own webhook tests use
// (internal/handler/webhook_bouncemoney_test.go:36). There is no dev default in its config:
// the secret is a terminal property, so whatever the local seed puts there must match this.
const defaultSigningSecret = "test-signing-secret-unit-only"

// ConfigFromEnv reads BOUNCEMONEY_STUB_* variables.
func ConfigFromEnv(getenv func(string) string) Config {
	c := Config{
		SigningSecret: envOr(getenv, "BOUNCEMONEY_STUB_SIGNING_SECRET", defaultSigningSecret),
		WebhookURL:    getenv("BOUNCEMONEY_STUB_WEBHOOK_URL"),
		PublicURL:     envOr(getenv, "BOUNCEMONEY_STUB_PUBLIC_URL", "http://localhost:18101"),
		Delay:         500 * time.Millisecond,
	}
	if v := getenv("BOUNCEMONEY_STUB_WEBHOOK_DELAY_MS"); v != "" {
		if n, err := strconv.Atoi(v); err == nil {
			c.Delay = time.Duration(n) * time.Millisecond
		}
	}
	if v := getenv("BOUNCEMONEY_STUB_PUBLIC_KEY"); v != "" {
		key, err := parsePublicKey(v)
		if err != nil {
			log.Fatalf("bouncemoney stub: BOUNCEMONEY_STUB_PUBLIC_KEY: %v", err)
		}
		c.PublicKey = key
	}
	return c
}

// Start is the stub as psp-stub runs it.
func Start(getenv func(string) string) (*stubhttp.Stub, http.Handler) {
	stub := stubhttp.New("bouncemoney")
	return stub, NewProvider(stub, ConfigFromEnv(getenv)).Routes()
}

// Provider answers BounceMoney's API.
type Provider struct {
	stub   *stubhttp.Stub
	cfg    Config
	client *http.Client

	mu       sync.Mutex
	sessions map[string]session // by referenceId, the gateway's txnRef
}

type session struct {
	id     string
	req    sessionRequest
	target string
}

func NewProvider(stub *stubhttp.Stub, cfg Config) *Provider {
	p := &Provider{stub: stub, cfg: cfg, client: &http.Client{Timeout: 10 * time.Second}, sessions: map[string]session{}}
	stub.Supports(stubhttp.Unsigned, stubhttp.Unauthorised)
	stub.OnWebhook(p.send)
	return p
}

type sessionRequest struct {
	FiatAmount        float64 `json:"fiatAmount"`
	Fiat              string  `json:"fiat"`
	ReferenceId       string  `json:"referenceId"`
	Description       string  `json:"description"`
	RedirectUrl       string  `json:"redirectUrl"`
	Email             string  `json:"email"`
	CustomerReference string  `json:"customerReference"`
	PaymentMethodId   string  `json:"paymentMethodId"`
}

// Routes answers on both {base}/checkout/sessions and the production path prefix /api/v1, so the
// stub works whether BOUNCEMONEY_BASE_URL names the host or the host plus /api/v1.
func (p *Provider) Routes() http.Handler {
	mux := http.NewServeMux()
	for _, prefix := range []string{"", "/api/v1"} {
		mux.HandleFunc("POST "+prefix+"/checkout/sessions", p.createSession)
	}
	mux.HandleFunc("GET /checkout/{id}", p.hostedPage)
	return mux
}

func (p *Provider) createSession(w http.ResponseWriter, r *http.Request) {
	body, _ := io.ReadAll(r.Body)
	control := p.stub.Control()

	if status, msg := p.checkAuth(r, body); status != 0 {
		stubhttp.WriteJSON(w, status, apiError(status, msg))
		return
	}
	if control.Outcome == stubhttp.Unauthorised {
		stubhttp.WriteJSON(w, http.StatusUnauthorized, apiError(http.StatusUnauthorized, "invalid API key"))
		return
	}
	var req sessionRequest
	if err := json.Unmarshal(body, &req); err != nil {
		stubhttp.WriteJSON(w, http.StatusBadRequest, apiError(http.StatusBadRequest, "invalid JSON body"))
		return
	}
	if req.ReferenceId == "" || req.Fiat == "" || req.RedirectUrl == "" {
		stubhttp.WriteJSON(w, http.StatusBadRequest, apiError(http.StatusBadRequest,
			map[string]string{"referenceId": "referenceId, fiat and redirectUrl are required"}))
		return
	}
	if req.FiatAmount < 15 {
		stubhttp.WriteJSON(w, http.StatusBadRequest, apiError(http.StatusBadRequest,
			map[string]string{"fiatAmount": "fiatAmount must not be less than 15"}))
		return
	}

	id := "cs_" + randomHex(12)
	target, err := p.webhookURL(req.RedirectUrl)
	if err != nil {
		log.Printf("bouncemoney stub: no webhook for %s: %v", req.ReferenceId, err)
	}
	p.mu.Lock()
	p.sessions[req.ReferenceId] = session{id: id, req: req, target: target}
	p.mu.Unlock()

	stubhttp.WriteJSON(w, http.StatusCreated, map[string]any{
		"statusCode": http.StatusCreated,
		"data": map[string]any{
			"id":          id,
			"url":         strings.TrimRight(p.cfg.PublicURL, "/") + "/checkout/" + id + "?redirect=" + url.QueryEscape(req.RedirectUrl),
			"status":      "open",
			"referenceId": req.ReferenceId,
			"fiatAmount":  req.FiatAmount,
			"fiat":        req.Fiat,
		},
	})

	if control.Outcome == stubhttp.Pending {
		p.stub.Hold(req.ReferenceId) // the session stays open and no webhook is sent
		return
	}
	if target == "" {
		return
	}
	go func() {
		time.Sleep(control.Delay(p.cfg.Delay))
		_, _ = p.send(req.ReferenceId, control)
	}()
}

// eventType is the webhook's type for a control: completed, or failed (code "expired" for
// checkout.session.expired); unsigned sends the completed event without its signature.
func eventType(c stubhttp.Control) (string, error) {
	switch c.Outcome {
	case stubhttp.Success, stubhttp.Unsigned:
		return "checkout.session.completed", nil
	case stubhttp.Failure:
		switch strings.TrimPrefix(c.Code, "checkout.session.") {
		case "", "failed":
			return "checkout.session.failed", nil
		case "expired":
			return "checkout.session.expired", nil
		default:
			return "", fmt.Errorf("bouncemoney failure code is failed or expired, not %q", c.Code)
		}
	case stubhttp.Pending:
		return "checkout.session.pending", nil
	default:
		return "", fmt.Errorf("bouncemoney has no webhook for %q", c.Outcome)
	}
}

// send delivers the webhook for one session now.
func (p *Provider) send(ref string, c stubhttp.Control) (stubhttp.Delivery, error) {
	p.mu.Lock()
	s, ok := p.sessions[ref]
	p.mu.Unlock()
	if !ok {
		return stubhttp.Delivery{}, stubhttp.ErrUnknownReference
	}
	typ, err := eventType(c)
	if err != nil {
		return stubhttp.Delivery{}, err
	}
	if s.target == "" {
		return stubhttp.Delivery{}, fmt.Errorf("no webhook address: redirectUrl %q has no host and BOUNCEMONEY_STUB_WEBHOOK_URL is unset", s.req.RedirectUrl)
	}
	body := webhookBody(typ, s.id, s.req)
	header := map[string]string{}
	if c.Outcome != stubhttp.Unsigned {
		header["X-BounceMoney-Signature"] = Sign(p.cfg.SigningSecret, time.Now().Unix(), body)
	}
	d := stubhttp.Post(p.client, s.target, "application/json", header, body)
	log.Printf("bouncemoney stub: webhook %s for %s to %s answered %d (err=%v)", typ, ref, s.target, d.Status, d.Err)
	return d, nil
}

// checkAuth answers as BounceMoney does to an unsigned or wrongly signed request.
func (p *Provider) checkAuth(r *http.Request, body []byte) (int, string) {
	keyId := r.Header.Get("X-BounceMoney-Key-Id")
	ts := r.Header.Get("X-BounceMoney-Timestamp")
	sig := r.Header.Get("X-BounceMoney-Signature")
	if keyId == "" || ts == "" || !strings.HasPrefix(sig, "v1=") {
		return http.StatusUnauthorized, "missing or malformed authentication headers"
	}
	if p.cfg.PublicKey == nil {
		return 0, ""
	}
	raw, err := base64.RawURLEncoding.DecodeString(strings.TrimPrefix(sig, "v1="))
	if err != nil || !ed25519.Verify(p.cfg.PublicKey, append([]byte(ts+"."), body...), raw) {
		return http.StatusUnauthorized, "invalid signature"
	}
	return 0, ""
}

func (p *Provider) webhookURL(redirectUrl string) (string, error) {
	if p.cfg.WebhookURL != "" {
		return p.cfg.WebhookURL, nil
	}
	u, err := url.Parse(redirectUrl)
	if err != nil || u.Scheme == "" || u.Host == "" {
		return "", fmt.Errorf("redirectUrl %q has no host and BOUNCEMONEY_STUB_WEBHOOK_URL is unset", redirectUrl)
	}
	base := u.Scheme + "://" + u.Host
	if i := strings.Index(u.Path, "/v1/psp/checkout/bouncemoney/"); i > 0 {
		base += u.Path[:i] // the gateway sits under a path prefix
	}
	return base + "/v1/webhooks/bouncemoney", nil
}

func webhookBody(eventType, sessionId string, req sessionRequest) []byte {
	status := strings.TrimPrefix(eventType, "checkout.session.")
	b, _ := json.Marshal(map[string]any{
		"id":      "evt_" + randomHex(12),
		"type":    eventType,
		"created": time.Now().Unix(),
		"data": map[string]any{
			"id":          sessionId,
			"referenceId": req.ReferenceId,
			"status":      status,
			"fiatAmount":  req.FiatAmount,
			"fiat":        req.Fiat,
		},
	})
	return b
}

// Sign is the X-BounceMoney-Signature header the gateway verifies (signature.go:35-63).
func Sign(secret string, ts int64, body []byte) string {
	t := strconv.FormatInt(ts, 10)
	mac := hmac.New(sha256.New, []byte(secret))
	mac.Write([]byte(t + "."))
	mac.Write(body)
	return "t=" + t + ",v1=" + base64.RawURLEncoding.EncodeToString(mac.Sum(nil))
}

// hostedPage stands in for the hosted checkout: it sends the player's browser back to the
// redirectUrl the adapter supplied, as BounceMoney does after payment.
func (p *Provider) hostedPage(w http.ResponseWriter, r *http.Request) {
	back := r.URL.Query().Get("redirect")
	if back == "" {
		http.Error(w, "checkout session not found", http.StatusNotFound)
		return
	}
	http.Redirect(w, r, back, http.StatusFound)
}

func apiError(status int, message any) map[string]any {
	return map[string]any{"statusCode": status, "error": http.StatusText(status), "message": message}
}

func parsePublicKey(s string) (ed25519.PublicKey, error) {
	block, _ := pem.Decode([]byte(strings.ReplaceAll(s, `\n`, "\n")))
	if block == nil {
		return nil, fmt.Errorf("not a PEM public key")
	}
	key, err := x509.ParsePKIXPublicKey(block.Bytes)
	if err != nil {
		return nil, err
	}
	ed, ok := key.(ed25519.PublicKey)
	if !ok {
		return nil, fmt.Errorf("not an Ed25519 key (%T)", key)
	}
	return ed, nil
}

func randomHex(n int) string {
	b := make([]byte, n)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}

func envOr(getenv func(string) string, name, fallback string) string {
	if v := getenv(name); v != "" {
		return v
	}
	return fallback
}

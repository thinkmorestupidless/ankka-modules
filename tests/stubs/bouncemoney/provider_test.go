package bouncemoney

import (
	"bytes"
	"crypto/ed25519"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/eitheror-io/ankka-casino/tests/stubs/internal/stubhttp"
)

// gateway stands in for psp-gateway's POST /v1/webhooks/bouncemoney and hands each delivery over.
type delivery struct {
	path, signature string
	body            []byte
}

func gateway(t *testing.T) (*httptest.Server, chan delivery) {
	t.Helper()
	got := make(chan delivery, 4)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		got <- delivery{r.URL.Path, r.Header.Get("X-BounceMoney-Signature"), body}
		w.WriteHeader(http.StatusOK)
	}))
	t.Cleanup(srv.Close)
	return srv, got
}

func start(t *testing.T) (*stubhttp.Stub, *httptest.Server, ed25519.PrivateKey) {
	t.Helper()
	pub, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	stub := stubhttp.New("bouncemoney")
	p := NewProvider(stub, Config{SigningSecret: defaultSigningSecret, PublicKey: pub, PublicURL: "http://bm.test", Delay: 10 * time.Millisecond})
	srv := httptest.NewServer(stub.Handler(p.Routes()))
	t.Cleanup(srv.Close)
	return stub, srv, priv
}

// initiate sends the request the adapter sends (adapter.go:120-157), signed as SignRequest does
// (signature.go:24-33): "v1=" + base64url(Ed25519(priv, "<ts>." + body)).
func initiate(t *testing.T, base string, priv ed25519.PrivateKey, redirect string) (*http.Response, map[string]any) {
	t.Helper()
	body, _ := json.Marshal(map[string]any{
		"fiatAmount": 50.0, "fiat": "AUD", "referenceId": "txn-abc-123",
		"description": "Deposit txn-abc-123", "redirectUrl": redirect,
	})
	ts := strconv.FormatInt(time.Now().Unix(), 10)
	sig := base64.RawURLEncoding.EncodeToString(ed25519.Sign(priv, append([]byte(ts+"."), body...)))
	req, _ := http.NewRequest(http.MethodPost, base+"/checkout/sessions", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-BounceMoney-Key-Id", "bmk_test")
	req.Header.Set("X-BounceMoney-Timestamp", ts)
	req.Header.Set("X-BounceMoney-Signature", "v1="+sig)
	req.Header.Set("Idempotency-Key", "txn-abc-123")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	var parsed map[string]any
	_ = json.NewDecoder(resp.Body).Decode(&parsed)
	return resp, parsed
}

// verifyWebhook re-implements the gateway's check, eitheror-platform
// services/psp-gateway/internal/bouncemoney/signature.go:43-63 (VerifyWebhook).
func verifyWebhook(secret string, body []byte, header string, now time.Time) bool {
	parts := map[string]string{}
	for _, part := range strings.Split(header, ",") {
		kv := strings.SplitN(strings.TrimSpace(part), "=", 2)
		if len(kv) == 2 {
			parts[kv[0]] = kv[1]
		}
	}
	tStr, ok1 := parts["t"]
	v1, ok2 := parts["v1"]
	if !ok1 || !ok2 {
		return false
	}
	ts, err := strconv.ParseInt(tStr, 10, 64)
	if err != nil || now.Unix()-ts < -300 || now.Unix()-ts > 300 {
		return false
	}
	mac := hmac.New(sha256.New, []byte(secret))
	mac.Write(append([]byte(tStr+"."), body...))
	return hmac.Equal([]byte(base64.RawURLEncoding.EncodeToString(mac.Sum(nil))), []byte(v1))
}

func await(t *testing.T, got chan delivery) delivery {
	t.Helper()
	select {
	case d := <-got:
		return d
	case <-time.After(3 * time.Second):
		t.Fatal("no webhook arrived")
		return delivery{}
	}
}

func TestHealthz(t *testing.T) {
	_, srv, _ := start(t)
	resp, err := http.Get(srv.URL + "/healthz")
	if err != nil || resp.StatusCode != http.StatusOK {
		t.Fatalf("healthz: %v %v", resp, err)
	}
}

func TestDepositAnswersTheShapeTheAdapterReadsAndSendsASignedWebhook(t *testing.T) {
	_, srv, priv := start(t)
	gw, got := gateway(t)

	resp, body := initiate(t, srv.URL, priv, gw.URL+"/v1/psp/checkout/bouncemoney/txn-abc-123/return")
	// adapter.go:170 requires 201; adapter.go:183 requires data.url.
	if resp.StatusCode != http.StatusCreated {
		t.Fatalf("status %d, body %v", resp.StatusCode, body)
	}
	data, _ := body["data"].(map[string]any)
	if u, _ := data["url"].(string); u == "" || data["referenceId"] != "txn-abc-123" || data["id"] == "" {
		t.Fatalf("data is %v", data)
	}

	d := await(t, got)
	if d.path != "/v1/webhooks/bouncemoney" {
		t.Fatalf("webhook went to %s", d.path)
	}
	if !verifyWebhook(defaultSigningSecret, d.body, d.signature, time.Now()) {
		t.Fatalf("the gateway would reject the signature %q", d.signature)
	}
	var event struct {
		Type string `json:"type"`
		Data struct {
			ReferenceId string `json:"referenceId"`
		} `json:"data"`
	}
	_ = json.Unmarshal(d.body, &event)
	// webhook.go:1440-1451 reads type and data.referenceId; adapter.go:208-216 maps the type.
	if event.Type != "checkout.session.completed" || event.Data.ReferenceId != "txn-abc-123" {
		t.Fatalf("event %s", d.body)
	}
}

func TestControlFailureDeclinesThePayment(t *testing.T) {
	_, srv, priv := start(t)
	gw, got := gateway(t)
	if r, _ := http.Post(srv.URL+"/__control", "application/json", strings.NewReader(`{"outcome":"failure"}`)); r.StatusCode != 200 {
		t.Fatalf("control answered %d", r.StatusCode)
	}
	resp, _ := initiate(t, srv.URL, priv, gw.URL+"/v1/psp/checkout/bouncemoney/txn-abc-123/return")
	if resp.StatusCode != http.StatusCreated {
		t.Fatalf("status %d", resp.StatusCode)
	}
	d := await(t, got)
	if !strings.Contains(string(d.body), `"type":"checkout.session.failed"`) {
		t.Fatalf("event %s", d.body)
	}
	if !verifyWebhook(defaultSigningSecret, d.body, d.signature, time.Now()) {
		t.Fatal("the failure webhook is not signed as the gateway verifies")
	}
}

func TestControlPendingSendsNoWebhook(t *testing.T) {
	_, srv, priv := start(t)
	gw, got := gateway(t)
	_, _ = http.Post(srv.URL+"/__control", "application/json", strings.NewReader(`{"outcome":"pending"}`))
	if resp, _ := initiate(t, srv.URL, priv, gw.URL+"/r"); resp.StatusCode != http.StatusCreated {
		t.Fatalf("status %d", resp.StatusCode)
	}
	select {
	case d := <-got:
		t.Fatalf("a pending session sent %s", d.body)
	case <-time.After(200 * time.Millisecond):
	}
}

func TestABadRequestSignatureIsRefused(t *testing.T) {
	_, srv, _ := start(t)
	_, other, _ := ed25519.GenerateKey(rand.Reader)
	resp, body := initiate(t, srv.URL, other, "http://gw.test/r")
	if resp.StatusCode != http.StatusUnauthorized || body["error"] == "" {
		t.Fatalf("status %d body %v", resp.StatusCode, body)
	}
}

func control(t *testing.T, base, body string) map[string]any {
	t.Helper()
	resp, err := http.Post(base+"/__control", "application/json", strings.NewReader(body))
	if err != nil || resp.StatusCode != http.StatusOK {
		t.Fatalf("control %s: %v %v", body, err, resp)
	}
	defer resp.Body.Close()
	var m map[string]any
	_ = json.NewDecoder(resp.Body).Decode(&m)
	return m
}

func TestUnsignedSendsTheCompletedEventWithNoSignature(t *testing.T) {
	_, srv, priv := start(t)
	gw, got := gateway(t)
	control(t, srv.URL, `{"outcome":"unsigned"}`)
	initiate(t, srv.URL, priv, gw.URL+"/v1/psp/checkout/bouncemoney/txn-abc-123/return")
	d := await(t, got)
	// webhook.go:1462 answers 400 to a missing X-BounceMoney-Signature.
	if d.signature != "" || !strings.Contains(string(d.body), `"type":"checkout.session.completed"`) {
		t.Fatalf("signature %q body %s", d.signature, d.body)
	}
}

func TestUnauthorisedAnswers401(t *testing.T) {
	_, srv, priv := start(t)
	control(t, srv.URL, `{"outcome":"unauthorised"}`)
	if resp, _ := initiate(t, srv.URL, priv, "http://gw.test/r"); resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("status %d", resp.StatusCode)
	}
}

func TestADuplicateIsNotABounceMoneyOutcome(t *testing.T) {
	_, srv, _ := start(t)
	resp, _ := http.Post(srv.URL+"/__control", "application/json", strings.NewReader(`{"outcome":"duplicate"}`))
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("status %d", resp.StatusCode)
	}
}

func TestWebhookNowWithAnExpiredCodeAndPendingSettled(t *testing.T) {
	_, srv, priv := start(t)
	gw, got := gateway(t)
	control(t, srv.URL, `{"outcome":"pending"}`)
	initiate(t, srv.URL, priv, gw.URL+"/v1/psp/checkout/bouncemoney/txn-abc-123/return")

	resp, err := http.Post(srv.URL+"/__webhook", "application/json", strings.NewReader(`{"reference":"txn-abc-123","outcome":"failure","code":"expired"}`))
	if err != nil || resp.StatusCode != http.StatusOK {
		t.Fatalf("webhook: %v %v", err, resp)
	}
	var answer map[string]any
	_ = json.NewDecoder(resp.Body).Decode(&answer)
	if answer["status"] != float64(200) {
		t.Fatalf("answer %v", answer)
	}
	if d := await(t, got); !strings.Contains(string(d.body), `"type":"checkout.session.expired"`) || !verifyWebhook(defaultSigningSecret, d.body, d.signature, time.Now()) {
		t.Fatalf("event %s", d.body)
	}

	// A second pending session is settled by the next control.
	initiate(t, srv.URL, priv, gw.URL+"/v1/psp/checkout/bouncemoney/txn-abc-123/return")
	settled := control(t, srv.URL, `{"outcome":"success"}`)
	if s, _ := settled["settled"].([]any); len(s) != 1 {
		t.Fatalf("settled %v", settled)
	}
	if d := await(t, got); !strings.Contains(string(d.body), `"type":"checkout.session.completed"`) {
		t.Fatalf("event %s", d.body)
	}
}

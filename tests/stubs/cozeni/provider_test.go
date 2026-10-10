package main

import (
	"crypto/sha256"
	"crypto/subtle"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
	"time"

	"github.com/eitheror-io/ankka-casino/tests/stubs/internal/stubhttp"
)

const (
	merchantId = "mx-1001"
	apiKey     = "cozeni-test-api-key"
	salt       = "1750593911000"
)

type delivery struct {
	path string
	form url.Values
}

func gateway(t *testing.T) (*httptest.Server, chan delivery) {
	t.Helper()
	got := make(chan delivery, 4)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		raw, _ := io.ReadAll(r.Body)
		form, _ := url.ParseQuery(string(raw))
		got <- delivery{r.URL.Path, form}
		w.WriteHeader(http.StatusOK)
	}))
	t.Cleanup(srv.Close)
	return srv, got
}

func start(t *testing.T) *httptest.Server {
	t.Helper()
	stub := stubhttp.New("cozeni")
	srv := httptest.NewServer(stub.Handler(NewProvider(stub, Config{APIKey: apiKey, Delay: 10 * time.Millisecond}).Routes()))
	t.Cleanup(srv.Close)
	return srv
}

// signPayIn is the adapter's token, cozeni/signature.go:11-14 (SignPayIn).
func signPayIn(merchantId, requestSalt, paymentRef, apiKey string) string {
	h := sha256.Sum256([]byte(merchantId + requestSalt + paymentRef + apiKey))
	return fmt.Sprintf("%x", h)
}

// verifyCallback re-implements the gateway's check, cozeni/signature.go:28-31 (VerifyCallback),
// called from handler/webhook.go:873 with the session's requestSalt and txnRef.
func verifyCallback(merchantId, requestSalt, paymentRef, apiKey, received string) bool {
	return subtle.ConstantTimeCompare([]byte(signPayIn(merchantId, requestSalt, paymentRef, apiKey)), []byte(received)) == 1
}

func post(t *testing.T, target string, body map[string]any, token string) (int, map[string]any) {
	t.Helper()
	b, _ := json.Marshal(body)
	req, _ := http.NewRequest(http.MethodPost, target, strings.NewReader(string(b)))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-cozeni-txnauthz", token)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	var parsed map[string]any
	_ = json.NewDecoder(resp.Body).Decode(&parsed)
	return resp.StatusCode, parsed
}

// payIn sends what cozeni.Initiate sends (adapter.go:139-182).
func payIn(t *testing.T, base, callback, ref string) (int, map[string]any) {
	return post(t, base+"/mxapi/"+merchantId+"/payintxn", map[string]any{
		"accountId": "acc-1", "requestSalt": salt, "requestAmount": 0.0015384615, "requestCurrency": "BTC",
		"convertCurrency": "USD", "paymentRef": ref, "paymentDesc": "deposit " + ref, "callbackUrl": callback, "dupCheck": true,
	}, signPayIn(merchantId, salt, ref, apiKey))
}

func await(t *testing.T, got chan delivery) delivery {
	t.Helper()
	select {
	case d := <-got:
		return d
	case <-time.After(3 * time.Second):
		t.Fatal("no callback arrived")
		return delivery{}
	}
}

func TestHealthz(t *testing.T) {
	srv := start(t)
	if resp, err := http.Get(srv.URL + "/healthz"); err != nil || resp.StatusCode != http.StatusOK {
		t.Fatalf("healthz: %v %v", resp, err)
	}
}

func TestExchangeRate(t *testing.T) {
	srv := start(t)
	resp, _ := http.Get(srv.URL + "/pub/rate/BTC/USD")
	var body struct{ Rate float64 }
	_ = json.NewDecoder(resp.Body).Decode(&body)
	// adapter.go:89-93 accepts {"rate": n > 0}.
	if resp.StatusCode != http.StatusOK || body.Rate != 65000 {
		t.Fatalf("status %d rate %v", resp.StatusCode, body.Rate)
	}
}

func TestDepositAnswersTheShapeTheAdapterReadsAndCallsBackSigned(t *testing.T) {
	srv := start(t)
	gw, got := gateway(t)
	ref := "6f1c0a52-txn"
	status, body := payIn(t, srv.URL, gw.URL+"/v1/webhooks/cozeni", ref)
	// adapter.go:198-209 needs 200 and paymentAddress; it reads txnId, requestAmount and validUntil.
	if status != http.StatusOK || body["paymentAddress"] == "" || body["txnId"] == nil || body["validUntil"] == "" || body["requestAmount"] == nil {
		t.Fatalf("status %d body %v", status, body)
	}

	d := await(t, got)
	if d.path != "/v1/webhooks/cozeni" {
		t.Fatalf("callback went to %s", d.path)
	}
	// handler/webhook.go:1003-1022 reads these form fields.
	if d.form.Get("status") != "COMPLETED" || d.form.Get("resultCode") != "00" || d.form.Get("paymentRef") != ref ||
		d.form.Get("convertAmount") != "100.00" || d.form.Get("txnId") == "" {
		t.Fatalf("callback form %v", d.form)
	}
	if !verifyCallback(merchantId, salt, ref, apiKey, d.form.Get("txnauthz")) {
		t.Fatalf("the gateway would refuse txnauthz %q", d.form.Get("txnauthz"))
	}
}

func TestControlFailureCancelsThePayIn(t *testing.T) {
	srv := start(t)
	gw, got := gateway(t)
	_, _ = http.Post(srv.URL+"/__control", "application/json", strings.NewReader(`{"outcome":"failure"}`))
	if status, _ := payIn(t, srv.URL, gw.URL+"/v1/webhooks/cozeni", "ref-2"); status != http.StatusOK {
		t.Fatalf("status %d", status)
	}
	d := await(t, got)
	if d.form.Get("status") != "CANCELLED" || !verifyCallback(merchantId, salt, "ref-2", apiKey, d.form.Get("txnauthz")) {
		t.Fatalf("callback form %v", d.form)
	}
}

func TestPayoutAnswersAndCallsBack(t *testing.T) {
	srv := start(t)
	gw, got := gateway(t)
	payout := func() (int, map[string]any) {
		// what cozeni.Payout sends (adapter.go:330-357)
		return post(t, srv.URL+"/mxapi/"+merchantId+"/payouttxn", map[string]any{
			"accountId": "acc-1", "requestSalt": salt, "amount": 25.0, "currency": "USD", "payoutCurrency": "USDT",
			"paymentRef": "wd-1", "callbackUrl": gw.URL + "/v1/webhooks/cozeni/withdrawal", "paymentAddress": "0xabc", "dupCheck": true,
		}, signPayIn(merchantId, salt, "wd-1", apiKey))
	}
	status, body := payout()
	// handler/payout.go:174 maps resultCode 01 to INITIATED.
	if status != http.StatusOK || body["resultCode"] != "01" || body["txnId"] == nil {
		t.Fatalf("status %d body %v", status, body)
	}
	d := await(t, got)
	if d.path != "/v1/webhooks/cozeni/withdrawal" || d.form.Get("status") != "COMPLETED" || d.form.Get("txnHash") == "" ||
		!verifyCallback(merchantId, salt, "wd-1", apiKey, d.form.Get("txnauthz")) {
		t.Fatalf("callback %s %v", d.path, d.form)
	}

	_, _ = http.Post(srv.URL+"/__control", "application/json", strings.NewReader(`{"outcome":"failure"}`))
	if _, body = payout(); body["resultCode"] != "93" {
		t.Fatalf("a failed payout answered %v", body)
	}
}

func TestAWrongTokenIsRefused(t *testing.T) {
	srv := start(t)
	status, body := post(t, srv.URL+"/mxapi/"+merchantId+"/payintxn",
		map[string]any{"accountId": "a", "requestSalt": salt, "requestAmount": 1.0, "requestCurrency": "USDT", "paymentRef": "r"}, "deadbeef")
	// adapter.go:190-196 reads msgCode/msgText from a 422.
	if status != http.StatusUnprocessableEntity || body["msgCode"] == "" {
		t.Fatalf("status %d body %v", status, body)
	}
}

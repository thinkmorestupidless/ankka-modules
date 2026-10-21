package eupaymentz

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/eitheror-io/ankka-casino/tests/stubs/internal/stubhttp"
)

const testPassphrase = "sk-test"

type sent struct {
	url  string
	body []byte
}

func start(t *testing.T) (*httptest.Server, chan sent) {
	t.Helper()
	stub := stubhttp.New("eupaymentz")
	p := NewProvider(stub, Config{Passphrase: testPassphrase, WebhookDelay: 0})
	hooks := make(chan sent, 4)
	p.Send = func(url string, body []byte) stubhttp.Delivery {
		hooks <- sent{url, body}
		return stubhttp.Delivery{URL: url, Status: http.StatusOK}
	}
	srv := httptest.NewServer(stub.Handler(p.Handler()))
	t.Cleanup(srv.Close)
	return srv, hooks
}

// processTx sends what the original's eupaymentz.Initiate sends (adapter.go:123-171), signed as
// signature.go:14-16 signs it.
func processTx(t *testing.T, srv *httptest.Server, merchantPaymentId string) map[string]any {
	t.Helper()
	amount, account, email, ip := "50.00", "acct-1", "player@example.com", "10.0.0.1"
	sum := sha256.Sum256([]byte(testPassphrase + amount + account + email + ip))
	body, _ := json.Marshal(map[string]string{
		"account_id": account, "account_password": "pw", "account_sha": hex.EncodeToString(sum[:]),
		"action_type": "payment", "account_gateway": "1", "merchant_payment_id": merchantPaymentId,
		"cust_email": email, "transac_amount": amount, "transac_currency_code": "EUR", "customer_ip": ip,
		"merchant_url_return":   "http://gateway/v1/psp/checkout/eupaymentz/" + merchantPaymentId + "/return",
		"merchant_url_callback": "http://gateway/v1/webhooks/eupaymentz",
	})
	resp, err := http.Post(srv.URL+"/api/v2/processTx", "application/json", bytes.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK { // adapter.go:186
		t.Fatalf("processTx status %d", resp.StatusCode)
	}
	var out map[string]any
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
		t.Fatal(err)
	}
	return out
}

// verify re-implements the original's eupaymentz.VerifyWebhook (internal/eupaymentz/signature.go:23-25):
// SHA256(passphrase + resp_trans_id + resp_trans_amount + resp_trans_status), compared case-insensitively.
func verify(passphrase, transId, amount, status, sha string) bool {
	sum := sha256.Sum256([]byte(passphrase + transId + amount + status))
	return strings.EqualFold(hex.EncodeToString(sum[:]), sha)
}

// webhookPayload is the struct the gateway decodes the callback into (handler/webhook.go:1166-1173).
type webhookPayload struct {
	RespTransId         string `json:"resp_trans_id"`
	RespTransMerchantId string `json:"resp_trans_merchant_id"`
	RespSha             string `json:"resp_sha"`
	RespTransAmount     string `json:"resp_trans_amount"`
	RespTransStatus     string `json:"resp_trans_status"`
}

func awaitHook(t *testing.T, hooks chan sent) (sent, webhookPayload) {
	t.Helper()
	select {
	case h := <-hooks:
		var p webhookPayload
		if err := json.Unmarshal(h.body, &p); err != nil {
			t.Fatal(err)
		}
		return h, p
	case <-time.After(2 * time.Second):
		t.Fatal("no webhook sent")
	}
	return sent{}, webhookPayload{}
}

func TestHealthz(t *testing.T) {
	srv, _ := start(t)
	resp, err := http.Get(srv.URL + "/healthz")
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("healthz %d", resp.StatusCode)
	}
}

func TestProcessTxAnswersWhatTheAdapterReads(t *testing.T) {
	srv, hooks := start(t)
	out := processTx(t, srv, "11111111-2222-3333-4444-555555555555")

	// adapter.go:199-210: success true, data.resp_trans_status PEND, data.UrlToRedirect non-empty.
	if out["success"] != true {
		t.Fatalf("success = %v", out["success"])
	}
	data := out["data"].(map[string]any)
	if data["resp_trans_status"] != "PEND" {
		t.Errorf("resp_trans_status = %v", data["resp_trans_status"])
	}
	redirect, _ := data["UrlToRedirect"].(string)
	if !strings.HasPrefix(redirect, srv.URL+"/api/v2/ProcessTxGetHosted?transkey=") {
		t.Errorf("UrlToRedirect = %q", redirect)
	}
	if data["UrlToRedirectMethod"] != "GET" {
		t.Errorf("UrlToRedirectMethod = %v", data["UrlToRedirectMethod"])
	}
	if data["resp_trans_merchant_id"] != "11111111-2222-3333-4444-555555555555" {
		t.Errorf("resp_trans_merchant_id = %v", data["resp_trans_merchant_id"])
	}
	// The hosted page answers and links back to the return URL.
	resp, err := http.Get(redirect)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Errorf("hosted page %d", resp.StatusCode)
	}

	h, p := awaitHook(t, hooks)
	if h.url != "http://gateway/v1/webhooks/eupaymentz" {
		t.Errorf("webhook url = %q", h.url)
	}
	if p.RespTransStatus != "00000" || p.RespTransMerchantId != "11111111-2222-3333-4444-555555555555" || p.RespTransAmount != "50.00" {
		t.Errorf("webhook = %+v", p)
	}
	if !verify(testPassphrase, p.RespTransId, p.RespTransAmount, p.RespTransStatus, p.RespSha) {
		t.Errorf("webhook resp_sha does not verify")
	}
	if verify("other", p.RespTransId, p.RespTransAmount, p.RespTransStatus, p.RespSha) {
		t.Errorf("webhook verifies under the wrong passphrase")
	}
}

func TestControlFailureRefusesThePayment(t *testing.T) {
	srv, hooks := start(t)
	resp, err := http.Post(srv.URL+"/__control", "application/json", strings.NewReader(`{"outcome":"failure"}`))
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()

	out := processTx(t, srv, "fail-1")
	if out["success"] != true { // hosted mode still answers PEND; the refusal comes by callback
		t.Fatalf("success = %v", out["success"])
	}
	_, p := awaitHook(t, hooks)
	if p.RespTransStatus == "00000" || p.RespTransStatus == "PEND" {
		t.Errorf("failure outcome sent status %q, want a refusal code", p.RespTransStatus)
	}
	if !verify(testPassphrase, p.RespTransId, p.RespTransAmount, p.RespTransStatus, p.RespSha) {
		t.Errorf("refusal webhook resp_sha does not verify")
	}
}

func TestControlPendingSendsNoWebhook(t *testing.T) {
	srv, hooks := start(t)
	resp, _ := http.Post(srv.URL+"/__control", "application/json", strings.NewReader(`{"outcome":"pending"}`))
	resp.Body.Close()
	processTx(t, srv, "pend-1")
	select {
	case h := <-hooks:
		t.Fatalf("unexpected webhook %s", h.body)
	case <-time.After(100 * time.Millisecond):
	}
}

func TestBadAccountShaIsRefused(t *testing.T) {
	srv, _ := start(t)
	body := `{"account_id":"a","account_password":"p","account_sha":"bad","merchant_payment_id":"x","transac_amount":"1.00"}`
	resp, err := http.Post(srv.URL+"/api/v2/processTx", "application/json", strings.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	var out map[string]any
	_ = json.NewDecoder(resp.Body).Decode(&out)
	if out["success"] != false {
		t.Errorf("success = %v, want false", out["success"])
	}
}

func TestPendingSettledWithAFailureCode(t *testing.T) {
	srv, hooks := start(t)
	resp, _ := http.Post(srv.URL+"/__control", "application/json", strings.NewReader(`{"outcome":"pending"}`))
	resp.Body.Close()
	processTx(t, srv, "pend-2")
	resp, _ = http.Post(srv.URL+"/__control", "application/json", strings.NewReader(`{"outcome":"failure","code":"T00050005"}`))
	resp.Body.Close()
	_, p := awaitHook(t, hooks)
	if p.RespTransMerchantId != "pend-2" || p.RespTransStatus != "T00050005" ||
		!verify(testPassphrase, p.RespTransId, p.RespTransAmount, p.RespTransStatus, p.RespSha) {
		t.Fatalf("webhook %+v", p)
	}
}

func TestWebhookNowAnswersTheGateway(t *testing.T) {
	srv, hooks := start(t)
	processTx(t, srv, "now-1")
	awaitHook(t, hooks)
	resp, _ := http.Post(srv.URL+"/__webhook", "application/json", strings.NewReader(`{"reference":"now-1","outcome":"pending"}`))
	var answer map[string]any
	_ = json.NewDecoder(resp.Body).Decode(&answer)
	if resp.StatusCode != http.StatusOK || answer["status"] != float64(200) {
		t.Fatalf("webhook %d %v", resp.StatusCode, answer)
	}
	if _, p := awaitHook(t, hooks); p.RespTransStatus != "PEND" {
		t.Fatalf("webhook %+v", p)
	}
	if resp, _ := http.Post(srv.URL+"/__control", "application/json", strings.NewReader(`{"outcome":"unsigned"}`)); resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("unsigned answered %d", resp.StatusCode)
	}
}

package main

import (
	"bytes"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
	"time"

	"github.com/eitheror-io/ankka-casino/tests/stubs/internal/stubhttp"
)

func start(t *testing.T, cfg Config) (*httptest.Server, chan Webhook) {
	t.Helper()
	stub := stubhttp.New("gigadat")
	p := NewProvider(stub, cfg)
	hooks := make(chan Webhook, 8)
	p.Send = func(_ Config, h Webhook) error { hooks <- h; return nil }
	srv := httptest.NewServer(stub.Handler(p.Handler()))
	t.Cleanup(srv.Close)
	return srv, hooks
}

func testConfig() Config {
	return Config{WebhookUrl: "http://gateway:8087/v1/webhooks/gigadat", WebhookBody: "array"}
}

// tokenResponse is what the adapter decodes (gigadat/adapter.go:57-67).
type tokenResponse struct {
	Token string `json:"token"`
	Data  struct {
		TransactionId string `json:"transactionId"`
	} `json:"data"`
}

// postToken sends what the original's postToken sends (gigadat/adapter.go:79-108).
func postToken(t *testing.T, srv *httptest.Server, typ, txnId string) (int, tokenResponse) {
	t.Helper()
	body, _ := json.Marshal(map[string]any{
		"amount": 50.0, "campaign": "camp1", "currency": "CAD", "email": "p@example.ca",
		"mobile": "4165550100", "name": "Jo Player", "province": "ON", "sandbox": true,
		"site": "http://site", "transactionId": txnId, "type": typ, "userId": "player-1", "userIp": "10.0.0.1",
	})
	req, _ := http.NewRequest(http.MethodPost, srv.URL+"/api/payment-token/camp1", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	req.SetBasicAuth("access", "security")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	var tr tokenResponse
	_ = json.NewDecoder(resp.Body).Decode(&tr)
	return resp.StatusCode, tr
}

func get(t *testing.T, u string) int {
	t.Helper()
	req, _ := http.NewRequest(http.MethodGet, u, nil)
	req.SetBasicAuth("access", "security")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	return resp.StatusCode
}

// --- the gateway's reading of a webhook, re-implemented from the original ---

type webhookBody struct{ Type, Rcode, Amount, Cookie, TransactionId string }

// parseWebhookBody re-implements gigadat.ParseWebhookBody (internal/gigadat/webhook.go:38-71) and
// parseKeyValueArray (webhook.go:76-93).
func parseWebhookBody(raw []byte) (webhookBody, bool) {
	parseArr := func(b []byte) (webhookBody, bool) {
		var pairs []KeyValue
		if err := json.Unmarshal(b, &pairs); err != nil {
			return webhookBody{}, false
		}
		m := map[string]string{}
		for _, p := range pairs {
			m[p.Key] = p.Value
		}
		wb := webhookBody{m["type"], m["rcode"], m["amount"], m["cookie"], m["transactionId"]}
		return wb, wb != webhookBody{}
	}
	if wb, ok := parseArr(raw); ok {
		return wb, true
	}
	vals, err := url.ParseQuery(string(raw))
	if err != nil {
		return webhookBody{}, false
	}
	if d := vals.Get("data"); d != "" {
		if wb, ok := parseArr([]byte(d)); ok {
			return wb, true
		}
	}
	wb := webhookBody{vals.Get("type"), vals.Get("rcode"), vals.Get("amount"), vals.Get("cookie"), vals.Get("transactionId")}
	return wb, wb != webhookBody{}
}

// mapStatus re-implements gigadat.MapStatus (internal/gigadat/webhook.go:97-128), returning the
// platform status only.
func mapStatus(status string) string {
	switch status {
	case "STATUS_SUCCESS":
		return "COMPLETED"
	case "STATUS_ABORTED1", "STATUS_EXPIRED", "STATUS_REJECTED", "STATUS_REJECTED1", "STATUS_FAILED":
		return "FAILED"
	}
	return ""
}

// gatewayReads encodes a webhook as the stub delivers it and reads it as handler/gigadat.go:56-90
// does: transactionId and status from the query, the rest from the body. Gigadat signs nothing; the
// gateway only logs the cookie (handler/gigadat.go:84-87), so a recognised body is what "verifies".
func gatewayReads(t *testing.T, cfg Config, h Webhook) (txnRef, platformStatus string, wb webhookBody) {
	t.Helper()
	target, _, body, err := Encode(cfg, h)
	if err != nil {
		t.Fatal(err)
	}
	u, _ := url.Parse(target)
	if u.Path != "/v1/webhooks/gigadat" {
		t.Errorf("webhook path %q", u.Path)
	}
	wb, ok := parseWebhookBody(body)
	if !ok {
		t.Fatalf("gateway would not recognise body %s", body)
	}
	return u.Query().Get("transactionId"), mapStatus(u.Query().Get("status")), wb
}

func await(t *testing.T, hooks chan Webhook) Webhook {
	t.Helper()
	select {
	case h := <-hooks:
		return h
	case <-time.After(2 * time.Second):
		t.Fatal("no webhook sent")
	}
	return Webhook{}
}

func TestHealthz(t *testing.T) {
	srv, _ := start(t, testConfig())
	if code := get(t, srv.URL+"/healthz"); code != http.StatusOK {
		t.Fatalf("healthz %d", code)
	}
}

func TestDepositTokenAnswersWhatTheAdapterReadsAndCompletes(t *testing.T) {
	cfg := testConfig()
	srv, hooks := start(t, cfg)
	code, tr := postToken(t, srv, "CPI", "txn-1")
	if code != http.StatusOK { // adapter.go:101
		t.Fatalf("token status %d", code)
	}
	if tr.Token == "" || tr.Data.TransactionId != "txn-1" {
		t.Fatalf("token response %+v", tr)
	}
	// The redirect the adapter builds (adapter.go:167) is served.
	if c := get(t, srv.URL+"/webflow?transaction="+tr.Data.TransactionId+"&token="+tr.Token); c != http.StatusOK {
		t.Errorf("webflow %d", c)
	}

	for _, body := range []string{"array", "form"} {
		cfg.WebhookBody = body
		h := await(t, hooks)
		txnRef, status, wb := gatewayReads(t, cfg, h)
		if txnRef != "txn-1" || status != "COMPLETED" || wb.Type != "CPI" || wb.Rcode != "100" || wb.Amount != "50" {
			t.Errorf("%s: txnRef=%q status=%q body=%+v", body, txnRef, status, wb)
		}
		hooks <- h // reuse the one delivery for the second encoding
	}
}

func TestControlFailureRejectsTheDeposit(t *testing.T) {
	cfg := testConfig()
	srv, hooks := start(t, cfg)
	resp, err := http.Post(srv.URL+"/__control", "application/json", strings.NewReader(`{"outcome":"failure"}`))
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if code, _ := postToken(t, srv, "CPI", "txn-2"); code != http.StatusOK {
		t.Fatalf("token status %d", code)
	}
	_, status, wb := gatewayReads(t, cfg, await(t, hooks))
	if status != "FAILED" || wb.Rcode != "231" {
		t.Errorf("status=%q rcode=%q", status, wb.Rcode)
	}
}

func TestPayoutWaitsForFinalizeThenReportsInitedAndSuccess(t *testing.T) {
	cfg := testConfig()
	srv, hooks := start(t, cfg)
	code, tr := postToken(t, srv, "ETO", "wr-1")
	if code != http.StatusOK {
		t.Fatalf("token status %d", code)
	}
	select {
	case h := <-hooks:
		t.Fatalf("webhook before finalize: %+v", h)
	case <-time.After(100 * time.Millisecond):
	}
	q := "?transaction=" + tr.Data.TransactionId + "&token=" + tr.Token
	if c := get(t, srv.URL+"/webflow"+q); c != http.StatusOK { // adapter.go:286
		t.Fatalf("initiate %d", c)
	}
	if c := get(t, srv.URL+"/webflow/deposit"+q); c != http.StatusOK { // adapter.go:302
		t.Fatalf("finalize %d", c)
	}
	_, s1, _ := gatewayReads(t, cfg, await(t, hooks))
	txnRef, s2, wb := gatewayReads(t, cfg, await(t, hooks))
	if s1 != "" || s2 != "COMPLETED" || txnRef != "wr-1" || wb.Type != "ETO" {
		t.Errorf("first=%q second=%q txnRef=%q type=%q", s1, s2, txnRef, wb.Type)
	}
}

func TestTokenWithoutCredentialsIsRefused(t *testing.T) {
	srv, _ := start(t, testConfig())
	resp, err := http.Post(srv.URL+"/api/payment-token/camp1", "application/json", strings.NewReader(`{}`))
	if err != nil {
		t.Fatal(err)
	}
	_, _ = io.Copy(io.Discard, resp.Body)
	resp.Body.Close()
	if resp.StatusCode != http.StatusUnauthorized {
		t.Errorf("status %d", resp.StatusCode)
	}
}

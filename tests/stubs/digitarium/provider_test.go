package digitarium

import (
	"bytes"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/eitheror-io/ankka-casino/tests/stubs/internal/stubhttp"
)

const apiKey = "g2pay-api-key"

type delivery struct {
	path, signature string
	body            []byte
}

func gateway(t *testing.T) (*httptest.Server, chan delivery) {
	t.Helper()
	got := make(chan delivery, 4)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		got <- delivery{r.URL.Path, r.Header.Get("Signature"), body}
		w.WriteHeader(http.StatusOK)
	}))
	t.Cleanup(srv.Close)
	return srv, got
}

func start(t *testing.T) *httptest.Server {
	t.Helper()
	stub := stubhttp.New("digitarium")
	srv := httptest.NewServer(stub.Handler(NewProvider(stub, Config{
		APIKey: apiKey, SigningKey: defaultSigningKey, PublicURL: "http://g2.test", Envelope: "flat", Delay: 10 * time.Millisecond,
	}).Routes()))
	t.Cleanup(srv.Close)
	return srv
}

// create sends what Initiate sends (adapter.go:139-170).
func create(t *testing.T, base, gw, ref, key string) (int, map[string]any) {
	t.Helper()
	body, _ := json.Marshal(map[string]any{
		"referenceId": ref, "paymentType": "DEPOSIT", "paymentMethod": "BASIC_CARD", "amount": 10.5, "currency": "EUR",
		"returnUrl": gw + "/v1/psp/checkout/digitarium/" + ref + "/return", "webhookUrl": gw + "/v1/webhooks/digitarium",
	})
	req, _ := http.NewRequest(http.MethodPost, base+"/api/v1/payments", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	if key != "" {
		req.Header.Set("Authorization", "Bearer "+key)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	var out map[string]any
	_ = json.NewDecoder(resp.Body).Decode(&out)
	return resp.StatusCode, out
}

// verify re-implements VerifyWebhook (signature.go:21-52): hex or any base64 of the HMAC.
func verify(key string, body []byte, sig string) bool {
	mac := hmac.New(sha256.New, []byte(key))
	mac.Write(body)
	want := mac.Sum(nil)
	for _, dec := range []func(string) ([]byte, error){hex.DecodeString, base64.StdEncoding.DecodeString,
		base64.RawStdEncoding.DecodeString, base64.URLEncoding.DecodeString, base64.RawURLEncoding.DecodeString} {
		if b, err := dec(sig); err == nil && hmac.Equal(b, want) {
			return true
		}
	}
	return false
}

// parse re-implements ParseWebhook (adapter.go:84-98): flat, or wrapped in "result".
type result struct {
	Id, ReferenceId, State, ErrorCode, ErrorMessage string
}

func parse(t *testing.T, raw []byte) (result, string) {
	t.Helper()
	var env struct {
		result
		Result *result `json:"result"`
	}
	if err := json.Unmarshal(raw, &env); err != nil {
		t.Fatalf("gateway would fail to decode %s", raw)
	}
	if env.result.ReferenceId != "" || env.Result == nil {
		return env.result, "flat"
	}
	return *env.Result, "wrapped"
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

func control(t *testing.T, base, body string) int {
	t.Helper()
	resp, err := http.Post(base+"/__control", "application/json", strings.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	return resp.StatusCode
}

func TestCreateAnswersTheRedirectAndSendsASignedFlatWebhook(t *testing.T) {
	srv := start(t)
	gw, got := gateway(t)
	status, out := create(t, srv.URL, gw.URL, "txn-1", apiKey)
	res, _ := out["result"].(map[string]any)
	if status != http.StatusOK || res["id"] == "" || !strings.HasPrefix(res["redirectUrl"].(string), "http://g2.test/checkout/") {
		t.Fatalf("%d %v", status, out)
	}
	d := await(t, got)
	p, shape := parse(t, d.body)
	if d.path != "/v1/webhooks/digitarium" || shape != "flat" || p.ReferenceId != "txn-1" || p.State != "COMPLETED" || p.Id != res["id"] {
		t.Fatalf("webhook %s %s", d.path, d.body)
	}
	if !verify(defaultSigningKey, d.body, d.signature) {
		t.Fatalf("signature %q", d.signature)
	}
	if _, err := hex.DecodeString(d.signature); err != nil {
		t.Fatalf("the default encoding is not hex: %q", d.signature)
	}
}

func TestABadOrEmptyKeyIs401(t *testing.T) {
	srv := start(t)
	for _, key := range []string{"", "wrong"} {
		if status, _ := create(t, srv.URL, "http://gw", "txn-401", key); status != http.StatusUnauthorized {
			t.Fatalf("key %q answered %d", key, status)
		}
	}
	control(t, srv.URL, `{"outcome":"unauthorised"}`)
	if status, _ := create(t, srv.URL, "http://gw", "txn-402", apiKey); status != http.StatusUnauthorized {
		t.Fatalf("unauthorised answered %d", status)
	}
}

func TestFailuresWrappedBase64AndUnsigned(t *testing.T) {
	srv := start(t)
	gw, got := gateway(t)

	control(t, srv.URL, `{"outcome":"failure","code":"51","envelope":"result","signatureEncoding":"base64"}`)
	create(t, srv.URL, gw.URL, "txn-d", apiKey)
	d := await(t, got)
	p, shape := parse(t, d.body)
	if shape != "wrapped" || p.State != "DECLINED" || p.ErrorCode != "51" || p.ErrorMessage == "" || p.ReferenceId != "txn-d" {
		t.Fatalf("declined %s", d.body)
	}
	if _, err := base64.StdEncoding.DecodeString(d.signature); err != nil || !verify(defaultSigningKey, d.body, d.signature) {
		t.Fatalf("base64 signature %q", d.signature)
	}

	control(t, srv.URL, `{"outcome":"failure","code":"CANCELLED"}`)
	create(t, srv.URL, gw.URL, "txn-c", apiKey)
	if p, _ := parse(t, await(t, got).body); p.State != "CANCELLED" {
		t.Fatalf("cancelled %+v", p)
	}

	control(t, srv.URL, `{"outcome":"unsigned"}`)
	create(t, srv.URL, gw.URL, "txn-u", apiKey)
	// handler/digitarium.go: a missing signature fails verification, 400.
	if d := await(t, got); d.signature != "" {
		t.Fatalf("unsigned carried %q", d.signature)
	}
}

func TestPendingSettledAndWebhookNow(t *testing.T) {
	srv := start(t)
	gw, got := gateway(t)
	control(t, srv.URL, `{"outcome":"pending"}`)
	create(t, srv.URL, gw.URL, "txn-p", apiKey)
	if p, _ := parse(t, await(t, got).body); p.State != "PENDING" {
		t.Fatalf("pending %+v", p)
	}
	control(t, srv.URL, `{"outcome":"success"}`)
	if p, _ := parse(t, await(t, got).body); p.State != "COMPLETED" || p.ReferenceId != "txn-p" {
		t.Fatalf("settled %+v", p)
	}
	resp, _ := http.Post(srv.URL+"/__webhook", "application/json", strings.NewReader(`{"reference":"txn-p","outcome":"failure","signatureEncoding":"base64url"}`))
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("webhook %d", resp.StatusCode)
	}
	if d := await(t, got); !verify(defaultSigningKey, d.body, d.signature) {
		t.Fatalf("base64url signature %q", d.signature)
	}
	if resp, _ := http.Post(srv.URL+"/__webhook", "application/json", strings.NewReader(`{"reference":"txn-p","signatureEncoding":"rot13"}`)); resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("a bad encoding answered %d", resp.StatusCode)
	}
	if code := control(t, srv.URL, `{"outcome":"duplicate"}`); code != http.StatusBadRequest {
		t.Fatalf("duplicate answered %d", code)
	}
}

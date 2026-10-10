package main

import (
	"crypto/sha256"
	"crypto/subtle"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/eitheror-io/ankka-casino/tests/stubs/internal/stubhttp"
)

const secret = "terminal-secret" // 15 characters: exercises the '0' padding

type received struct {
	path   string
	apikey string
	body   []byte
}

// fakeGateway stands in for POST /v1/webhooks/t365 (cmd/psp-gateway/main.go:184).
func fakeGateway(t *testing.T) (*httptest.Server, chan received) {
	t.Helper()
	got := make(chan received, 4)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		got <- received{path: r.URL.Path, apikey: r.Header.Get("apikey"), body: body}
		w.WriteHeader(http.StatusOK)
	}))
	t.Cleanup(srv.Close)
	return srv, got
}

func newStub(t *testing.T) *httptest.Server {
	t.Helper()
	stub := stubhttp.New("t365")
	srv := httptest.NewServer(stub.Handler(NewProvider(stub, Config{Secret: secret})))
	t.Cleanup(srv.Close)
	return srv
}

const orderId = "5b0a2f4e8a514c479d0e2b6f7c1d3e9a"

// initiate sends what adapter.go:89-131 sends.
func initiate(t *testing.T, base, gateway string) (int, map[string]string) {
	t.Helper()
	body, _ := json.Marshal(map[string]any{
		"amount": "50.00", "currency": "USD", "callbackUrl": gateway + "/v1/webhooks/t365",
		"returnUrl": gateway + "/v1/psp/checkout/t365/" + orderId + "/return", "orderId": orderId,
		"method": "BANK_TRANSFER", "midId": 187, "email": "p@example.com", "ip": "127.0.0.1", "country": "ZA",
	})
	req, _ := http.NewRequest(http.MethodPost, base+"/api/v1/external/payment/hosted", strings.NewReader(string(body)))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("apikey", "terminal-api-key")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	// The adapter's response struct (adapter.go:139-145).
	var api struct {
		PaymentUrl string `json:"paymentUrl"`
		Status     string `json:"status"`
		Message    string `json:"message"`
		Id         string `json:"id"`
		OrderId    string `json:"orderId"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&api); err != nil {
		t.Fatalf("adapter would fail to decode: %v", err)
	}
	return resp.StatusCode, map[string]string{"paymentUrl": api.PaymentUrl, "id": api.Id, "orderId": api.OrderId, "status": api.Status}
}

func await(t *testing.T, got chan received) received {
	t.Helper()
	select {
	case r := <-got:
		return r
	case <-time.After(5 * time.Second):
		t.Fatal("no postback reached the gateway")
		return received{}
	}
}

// verify re-implements the gateway's check, independently of the stub's Sign:
// padSecretKey and Sign (internal/t365/signature.go:13-38), Verify (signature.go:40-46), with
// amount/currency taken from the postback payload (handler/webhook.go:507-516) and the advisory
// apikey comparison (handler/webhook.go:499-504). It returns the verified status.
func verify(t *testing.T, r received, apiKey, secretKey string) string {
	t.Helper()
	var pb struct {
		Id, OrderId, Status, Amount, Currency, Hash string
	}
	if err := json.Unmarshal(r.body, &pb); err != nil {
		t.Fatalf("gateway would fail to decode %s: %v", r.body, err)
	}
	if r.apikey != "" && r.apikey != apiKey {
		t.Fatalf("apikey header %q does not match the terminal's", r.apikey)
	}
	if len(secretKey) < 16 {
		secretKey += strings.Repeat("0", 16-len(secretKey))
	}
	input := fmt.Sprintf("secretKey=%s&amount=%s&currency=%s&id=%s&orderId=%s&status=%s",
		secretKey, pb.Amount, pb.Currency, pb.OrderId, pb.OrderId, pb.Status)
	expected := strings.ToUpper(fmt.Sprintf("%x", sha256.Sum256([]byte(input))))
	if subtle.ConstantTimeCompare([]byte(expected), []byte(pb.Hash)) != 1 {
		t.Fatalf("hash does not verify: %s", r.body)
	}
	if pb.OrderId != orderId || pb.Amount != "50.00" || pb.Currency != "USD" {
		t.Fatalf("postback does not carry the payment: %s", r.body)
	}
	return pb.Status
}

func TestHealthz(t *testing.T) {
	resp, err := http.Get(newStub(t).URL + "/healthz")
	if err != nil || resp.StatusCode != http.StatusOK {
		t.Fatalf("healthz: %v %v", err, resp)
	}
}

func TestHostedPaymentAndSignedPostback(t *testing.T) {
	srv := newStub(t)
	gw, got := fakeGateway(t)

	code, api := initiate(t, srv.URL, gw.URL)
	// adapter.go:149-151: the only field the adapter requires.
	if code != http.StatusOK || api["paymentUrl"] == "" || api["orderId"] != orderId {
		t.Fatalf("answer the adapter would reject: %d %v", code, api)
	}

	r := await(t, got)
	if r.path != "/v1/webhooks/t365" {
		t.Fatalf("postback went to %q", r.path)
	}
	if status := verify(t, r, "terminal-api-key", secret); status != "SUCCESS" {
		t.Fatalf("status = %q; want SUCCESS", status)
	}

	// The hosted page sends the player back to the returnUrl.
	client := &http.Client{CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	resp, err := client.Get(api["paymentUrl"])
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if loc := resp.Header.Get("Location"); resp.StatusCode != http.StatusFound || !strings.HasSuffix(loc, "/v1/psp/checkout/t365/"+orderId+"/return") {
		t.Fatalf("hosted page: %d %q", resp.StatusCode, loc)
	}
}

func TestControlFailureAndPending(t *testing.T) {
	for _, c := range []struct{ outcome, want string }{{"failure", "FAIL"}, {"pending", "PENDING"}} {
		t.Run(c.outcome, func(t *testing.T) {
			srv := newStub(t)
			gw, got := fakeGateway(t)
			resp, err := http.Post(srv.URL+"/__control", "application/json", strings.NewReader(`{"outcome":"`+c.outcome+`"}`))
			if err != nil || resp.StatusCode != http.StatusOK {
				t.Fatalf("control: %v %v", err, resp)
			}
			if code, api := initiate(t, srv.URL, gw.URL); code != http.StatusOK || api["paymentUrl"] == "" {
				t.Fatalf("initiation still succeeds: %d %v", code, api)
			}
			if status := verify(t, await(t, got), "terminal-api-key", secret); status != c.want {
				t.Fatalf("status = %q; want %q", status, c.want)
			}
		})
	}
}

func TestMissingApiKeyHasNoPaymentUrl(t *testing.T) {
	srv := newStub(t)
	resp, err := http.Post(srv.URL+"/api/v1/external/payment/hosted", "application/json", strings.NewReader(`{}`))
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	var api struct{ PaymentUrl, Message string }
	_ = json.NewDecoder(resp.Body).Decode(&api)
	if resp.StatusCode == http.StatusOK || api.PaymentUrl != "" || api.Message == "" {
		t.Fatalf("want an error without paymentUrl, got %d %+v", resp.StatusCode, api)
	}
}

// TestSignMatchesTheOriginalsVector checks the stub's Sign against the round-trip the original
// tests (internal/t365/signature_test.go:39-50), recomputed here.
func TestSignMatchesTheOriginalsVector(t *testing.T) {
	want := strings.ToUpper(fmt.Sprintf("%x", sha256.Sum256([]byte(
		"secretKey=testkey000000000&amount=25.00&currency=GBP&id=order9876543210&orderId=order9876543210&status=SUCCESS"))))
	if got := Sign("testkey", "25.00", "GBP", "order9876543210", "SUCCESS"); got != want {
		t.Fatalf("Sign = %s; want %s", got, want)
	}
}

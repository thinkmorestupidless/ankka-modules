package main

import (
	"encoding/json"
	"io"
	"math/big"
	"net/http"
	"net/http/httptest"
	"net/url"
	"regexp"
	"strings"
	"testing"
	"time"

	"github.com/eitheror-io/ankka-casino/tests/stubs/internal/stubhttp"
)

// depositResponse is the adapter's own type
// (eitheror-platform/services/psp-gateway/internal/morphosis/adapter.go:127-134).
type depositResponse struct {
	Result     string      `json:"result"`
	Status     string      `json:"status"`
	Id         json.Number `json:"id"`
	Address    string      `json:"address"`
	CoinAmount json.Number `json:"coin_amount"`
	Detail     string      `json:"detail"`
}

// payoutResponse is adapter.go:138-145.
type payoutResponse struct {
	Result     string      `json:"result"`
	Status     string      `json:"status"`
	Id         json.Number `json:"id"`
	CoinAmount json.Number `json:"coin_amount"`
	Detail     string      `json:"detail"`
}

// received is what the fake gateway saw on its webhook route.
type received struct {
	path string
	body []byte
}

// fakeGateway answers the two webhook routes of the original (cmd/psp-gateway/main.go:199-200)
// with the plain-text "OK" QVEI requires (handler/morphosis.go:67-70).
func fakeGateway(t *testing.T) (*httptest.Server, chan received) {
	t.Helper()
	got := make(chan received, 4)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		got <- received{path: r.URL.Path, body: body}
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("OK"))
	}))
	t.Cleanup(srv.Close)
	return srv, got
}

func newStub(t *testing.T) (*stubhttp.Stub, *httptest.Server) {
	t.Helper()
	stub := stubhttp.New("morphosis")
	srv := httptest.NewServer(stub.Handler(NewProvider(stub, 0)))
	t.Cleanup(srv.Close)
	return stub, srv
}

func control(t *testing.T, base, outcome string) {
	t.Helper()
	resp, err := http.Post(base+"/__control", "application/json", strings.NewReader(`{"outcome":"`+outcome+`"}`))
	if err != nil || resp.StatusCode != http.StatusOK {
		t.Fatalf("control %s: %v %v", outcome, err, resp)
	}
	resp.Body.Close()
}

const token = "5b0a2f4e-8a51-4c47-9d0e-2b6f7c1d3e9a"

// insertDeposit sends what adapter.go:200-213 sends, to resolveBaseUrl(cfg) + "?action=insert_deposit".
func insertDeposit(t *testing.T, base, gateway string) depositResponse {
	t.Helper()
	form := url.Values{
		"user": {"u"}, "pass": {"p"}, "account": {"u"}, "amount": {"50.00"}, "currency": {"USD"},
		"method": {"bitcoin"}, "trace_id": {"8d7c2f8a-1d55-4a8e-9f0b-0f6c1c2b3a4d"},
		"notification_url": {gateway + "/v1/webhooks/morphosis/deposit/" + token},
	}
	resp, err := http.Post(base+"/?action=insert_deposit", "application/x-www-form-urlencoded", strings.NewReader(form.Encode()))
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	var dep depositResponse
	if err := json.NewDecoder(resp.Body).Decode(&dep); err != nil {
		t.Fatalf("adapter would fail to decode: %v", err)
	}
	return dep
}

func await(t *testing.T, got chan received) received {
	t.Helper()
	select {
	case r := <-got:
		return r
	case <-time.After(5 * time.Second):
		t.Fatal("no callback reached the gateway")
		return received{}
	}
}

// verified re-implements how the gateway accepts a deposit callback, since QVEI does not sign:
//   - the path's {token} must be a notify token and the one the session is keyed by
//     (handler/morphosis.go:78-82, morphosis/sessions.go:27-30);
//   - the body parses with id and coin_amount as JSON numbers (handler/morphosis.go:45-60);
//   - id must equal the insert_deposit id (handler/morphosis.go:122);
//   - status maps by morphosis.MapStatus (adapter.go:269-279);
//   - an "ac" below the quoted coin amount is underpaid (adapter.go:47-57, handler/morphosis.go:139).
//
// It returns the outcome the gateway would post: COMPLETED, FAILED, UNDERPAID or "" (non-terminal).
func verified(t *testing.T, r received, wantPrefix, wantID, quoted string) string {
	t.Helper()
	tok, ok := strings.CutPrefix(r.path, wantPrefix)
	if !ok || tok != token || !regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`).MatchString(tok) {
		t.Fatalf("callback path %q does not carry the notify token", r.path)
	}
	var cb struct {
		Id         json.Number `json:"id"`
		Status     string      `json:"status"`
		TxHash     string      `json:"tx_hash"`
		Message    string      `json:"message"`
		CoinAmount json.Number `json:"coin_amount"`
	}
	if err := json.Unmarshal(r.body, &cb); err != nil {
		t.Fatalf("gateway would fail to decode %s: %v", r.body, err)
	}
	if cb.Id.String() != wantID {
		t.Fatalf("callback id %q does not match session id %q", cb.Id, wantID)
	}
	switch cb.Status {
	case "ac":
		if quoted == "" {
			return "COMPLETED"
		}
		exp, ok1 := new(big.Rat).SetString(quoted)
		got, ok2 := new(big.Rat).SetString(cb.CoinAmount.String())
		if !ok1 || !ok2 || exp.Sign() <= 0 || got.Cmp(exp) < 0 {
			return "UNDERPAID"
		}
		return "COMPLETED"
	case "de":
		return "FAILED"
	default:
		return ""
	}
}

func TestHealthz(t *testing.T) {
	_, srv := newStub(t)
	resp, err := http.Get(srv.URL + "/healthz")
	if err != nil || resp.StatusCode != http.StatusOK {
		t.Fatalf("healthz: %v %v", err, resp)
	}
}

func TestDepositQuoteAndConfirmation(t *testing.T) {
	_, srv := newStub(t)
	gw, got := fakeGateway(t)

	dep := insertDeposit(t, srv.URL, gw.URL)
	// The fields Initiate reads (adapter.go:240-252).
	if dep.Result != "ok" || dep.Id.String() == "" || dep.Id.String() == "0" || dep.Address == "" {
		t.Fatalf("quote the adapter would reject: %+v", dep)
	}
	if dep.CoinAmount.String() != "0.00083333" {
		t.Fatalf("coin_amount = %q; want 50 USD at 60000 = 0.00083333", dep.CoinAmount)
	}

	cb := await(t, got)
	if out := verified(t, cb, "/v1/webhooks/morphosis/deposit/", dep.Id.String(), dep.CoinAmount.String()); out != "COMPLETED" {
		t.Fatalf("outcome = %q; want COMPLETED (body %s)", out, cb.body)
	}
}

func TestFailureIsAnUnderpayment(t *testing.T) {
	_, srv := newStub(t)
	gw, got := fakeGateway(t)
	control(t, srv.URL, "failure")

	dep := insertDeposit(t, srv.URL, gw.URL)
	if dep.Result != "ok" {
		t.Fatalf("the quote still succeeds on failure: %+v", dep)
	}
	cb := await(t, got)
	if out := verified(t, cb, "/v1/webhooks/morphosis/deposit/", dep.Id.String(), dep.CoinAmount.String()); out != "UNDERPAID" {
		t.Fatalf("outcome = %q; want UNDERPAID (body %s)", out, cb.body)
	}
}

func TestPendingSendsNoTerminalStatus(t *testing.T) {
	_, srv := newStub(t)
	gw, got := fakeGateway(t)
	control(t, srv.URL, "pending")

	dep := insertDeposit(t, srv.URL, gw.URL)
	if out := verified(t, await(t, got), "/v1/webhooks/morphosis/deposit/", dep.Id.String(), dep.CoinAmount.String()); out != "" {
		t.Fatalf("outcome = %q; want non-terminal", out)
	}
}

func TestPayout(t *testing.T) {
	for _, c := range []struct{ outcome, want string }{{"success", "COMPLETED"}, {"failure", "FAILED"}} {
		t.Run(c.outcome, func(t *testing.T) {
			_, srv := newStub(t)
			gw, got := fakeGateway(t)
			control(t, srv.URL, c.outcome)
			// What adapter.go:298-308 sends; insert_payout takes no currency.
			form := url.Values{
				"user": {"u"}, "pass": {"p"}, "account": {"u"}, "amount": {"100.00"}, "method": {"tether"},
				"address": {"0xabc"}, "notification_url": {gw.URL + "/v1/webhooks/morphosis/withdrawal/" + token},
			}
			resp, err := http.Post(srv.URL+"?action=insert_payout", "application/x-www-form-urlencoded", strings.NewReader(form.Encode()))
			if err != nil {
				t.Fatal(err)
			}
			defer resp.Body.Close()
			var pay payoutResponse
			if err := json.NewDecoder(resp.Body).Decode(&pay); err != nil {
				t.Fatal(err)
			}
			// adapter.go:333-341
			if pay.Result != "ok" || pay.Id.String() == "" || pay.Id.String() == "0" {
				t.Fatalf("payout the adapter would reject: %+v", pay)
			}
			if out := verified(t, await(t, got), "/v1/webhooks/morphosis/withdrawal/", pay.Id.String(), ""); out != c.want {
				t.Fatalf("outcome = %q; want %q", out, c.want)
			}
		})
	}
}

func TestMissingCredentialsIsAnAPIError(t *testing.T) {
	_, srv := newStub(t)
	resp, err := http.Post(srv.URL+"/?action=insert_deposit", "application/x-www-form-urlencoded", strings.NewReader("amount=1.00"))
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	var dep depositResponse
	_ = json.NewDecoder(resp.Body).Decode(&dep)
	if dep.Result != "error" || dep.Detail == "" {
		t.Fatalf("want result=error with detail, got %+v", dep)
	}
}

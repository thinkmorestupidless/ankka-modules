package stubhttp

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func post(t *testing.T, url, body string) (int, map[string]any) {
	t.Helper()
	r, err := http.Post(url, "application/json", strings.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	defer r.Body.Close()
	var m map[string]any
	_ = json.NewDecoder(r.Body).Decode(&m)
	return r.StatusCode, m
}

func TestHealthControlAndLog(t *testing.T) {
	s := New("example")
	provider := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		WriteJSON(w, http.StatusOK, map[string]string{"echo": string(body), "outcome": string(s.Next())})
	})
	server := httptest.NewServer(s.Handler(provider))
	defer server.Close()

	if r, _ := http.Get(server.URL + "/healthz"); r.StatusCode != 200 {
		t.Fatalf("healthz answered %d", r.StatusCode)
	}
	if status, _ := post(t, server.URL+"/__control", `{"outcome":"failure"}`); status != 200 {
		t.Fatalf("control answered %d", status)
	}
	if status, _ := post(t, server.URL+"/__control", `{"outcome":"maybe"}`); status != 400 {
		t.Fatalf("an unknown outcome was accepted")
	}
	r, _ := http.Post(server.URL+"/pay", "application/json", strings.NewReader(`{"amount":1050}`))
	body, _ := io.ReadAll(r.Body)
	if !strings.Contains(string(body), `"outcome":"failure"`) || !strings.Contains(string(body), "1050") {
		t.Fatalf("the provider did not see the body or the outcome: %s", body)
	}
	got := s.Received()
	if len(got) != 1 || got[0].Path != "/pay" || string(got[0].Body) != `{"amount":1050}` {
		t.Fatalf("the log is %+v", got)
	}
}

func TestAnOutcomeTheProviderLacksIs400(t *testing.T) {
	s := New("example")
	server := httptest.NewServer(s.Handler(http.NotFoundHandler()))
	defer server.Close()
	status, body := post(t, server.URL+"/__control", `{"outcome":"duplicate"}`)
	if status != 400 || !strings.Contains(fmt.Sprint(body["error"]), "duplicate") {
		t.Fatalf("a provider without duplicates answered %d %v", status, body)
	}
	s.Supports(Duplicate)
	if status, _ := post(t, server.URL+"/__control", `{"outcome":"duplicate"}`); status != 200 {
		t.Fatalf("a supported outcome answered %d", status)
	}
}

func TestTheControlIsStickyAndCarriesItsFields(t *testing.T) {
	s := New("example").Supports(Underpayment, Unsigned)
	server := httptest.NewServer(s.Handler(http.NotFoundHandler()))
	defer server.Close()
	post(t, server.URL+"/__control", `{"outcome":"underpayment","code":"03","receivedAmount":"0.0009","convertAmount":0,"signatureEncoding":"base64","delayMs":5}`)
	for i := 0; i < 2; i++ {
		c := s.Control()
		if c.Outcome != Underpayment || c.Code != "03" || c.ReceivedAmount != "0.0009" || c.ConvertAmount != "0" ||
			c.SignatureEncoding != "base64" || c.Delay(time.Second) != 5*time.Millisecond {
			t.Fatalf("the control is %+v", c)
		}
	}
	if (Control{}).Delay(time.Second) != time.Second {
		t.Fatal("no delayMs is not the default")
	}
}

// sessions is a provider's session table and webhook sender, standing in for one.
type sessions struct {
	known map[string]bool
	sent  []string
}

func (p *sessions) hook(gateway string) WebhookFunc {
	return func(ref string, c Control) (Delivery, error) {
		if !p.known[ref] {
			return Delivery{}, ErrUnknownReference
		}
		if c.Outcome == Duplicate {
			return Delivery{}, fmt.Errorf("a duplicate has no webhook")
		}
		p.sent = append(p.sent, ref+":"+string(c.Outcome)+":"+c.Code)
		return Post(http.DefaultClient, gateway, "application/json", nil, []byte(`{"ref":"`+ref+`"}`)), nil
	}
}

func gatewayAnswering(t *testing.T, status int, body string) *httptest.Server {
	t.Helper()
	gw := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(status)
		_, _ = w.Write([]byte(body))
	}))
	t.Cleanup(gw.Close)
	return gw
}

func TestWebhookSendsNowAndAnswersTheGateway(t *testing.T) {
	s := New("example").Supports(Duplicate)
	p := &sessions{known: map[string]bool{"ref-1": true}}
	s.OnWebhook(p.hook(gatewayAnswering(t, 400, `{"error":"invalid_checksum"}`).URL))
	server := httptest.NewServer(s.Handler(http.NotFoundHandler()))
	defer server.Close()

	status, body := post(t, server.URL+"/__webhook", `{"reference":"ref-1","outcome":"failure","code":"05"}`)
	if status != 200 || body["status"] != float64(400) || fmt.Sprint(body["body"]) != "map[error:invalid_checksum]" {
		t.Fatalf("webhook answered %d %v", status, body)
	}
	if len(p.sent) != 1 || p.sent[0] != "ref-1:failure:05" {
		t.Fatalf("sent %v", p.sent)
	}
	if s.Next() != Success {
		t.Fatal("a webhook's overrides changed the sticky control")
	}
	if status, _ := post(t, server.URL+"/__webhook", `{"reference":"ref-9"}`); status != 404 {
		t.Fatalf("an unknown reference answered %d", status)
	}
	if status, _ := post(t, server.URL+"/__webhook", `{"reference":"ref-1","outcome":"duplicate"}`); status != 400 {
		t.Fatalf("an outcome with no webhook answered %d", status)
	}
	if status, _ := post(t, server.URL+"/__webhook", `{"reference":"ref-1","outcome":"reversed"}`); status != 400 {
		t.Fatalf("an unsupported outcome answered %d", status)
	}
	if status, _ := post(t, server.URL+"/__webhook", `{}`); status != 400 {
		t.Fatalf("no reference answered %d", status)
	}
}

func TestAPlainTextAnswerIsAString(t *testing.T) {
	s := New("example")
	p := &sessions{known: map[string]bool{"r": true}}
	s.OnWebhook(p.hook(gatewayAnswering(t, 200, "OK").URL))
	server := httptest.NewServer(s.Handler(http.NotFoundHandler()))
	defer server.Close()
	if _, body := post(t, server.URL+"/__webhook", `{"reference":"r"}`); body["body"] != "OK" || body["status"] != float64(200) {
		t.Fatalf("answered %v", body)
	}
}

func TestPendingIsSettledByTheNextControl(t *testing.T) {
	s := New("example")
	p := &sessions{known: map[string]bool{"a": true, "b": true}}
	s.OnWebhook(p.hook(gatewayAnswering(t, 200, "").URL))
	server := httptest.NewServer(s.Handler(http.NotFoundHandler()))
	defer server.Close()

	post(t, server.URL+"/__control", `{"outcome":"pending"}`)
	s.Hold("a")
	s.Hold("b")
	s.Hold("a")
	if _, body := post(t, server.URL+"/__control", `{"outcome":"pending"}`); body["settled"] != nil {
		t.Fatalf("pending after pending settled %v", body)
	}
	_, body := post(t, server.URL+"/__control", `{"outcome":"failure","code":"X"}`)
	settled, _ := body["settled"].([]any)
	if len(settled) != 2 || len(p.sent) != 2 || p.sent[0] != "a:failure:X" || p.sent[1] != "b:failure:X" {
		t.Fatalf("settled %v, sent %v", body, p.sent)
	}
	if _, body := post(t, server.URL+"/__control", `{"outcome":"success"}`); body["settled"] != nil {
		t.Fatalf("a settled reference was settled again: %v", body)
	}
}

func TestAWebhookReleasesAHeldReference(t *testing.T) {
	s := New("example")
	p := &sessions{known: map[string]bool{"a": true}}
	s.OnWebhook(p.hook(gatewayAnswering(t, 200, "").URL))
	server := httptest.NewServer(s.Handler(http.NotFoundHandler()))
	defer server.Close()
	s.Hold("a")
	post(t, server.URL+"/__webhook", `{"reference":"a","outcome":"success"}`)
	if _, body := post(t, server.URL+"/__control", `{"outcome":"success"}`); body["settled"] != nil {
		t.Fatalf("a delivered reference was settled again: %v", body)
	}
}

func TestRetarget(t *testing.T) {
	cases := []struct{ request, configured, marker, want string }{
		{"http://gw/v1/webhooks/cozeni", "", "/v1/webhooks/cozeni", "http://gw/v1/webhooks/cozeni"},
		{"http://gw/v1/webhooks/cozeni/withdrawal", "http://kit:9/v1/webhooks/cozeni", "/v1/webhooks/cozeni", "http://kit:9/v1/webhooks/cozeni/withdrawal"},
		{"http://gw/v1/webhooks/morphosis/deposit/tok", "http://kit/v1/webhooks/morphosis/", "/v1/webhooks/morphosis", "http://kit/v1/webhooks/morphosis/deposit/tok"},
		{"http://gw/v1/webhooks/unityfinance/withdrawal?merchantId=m", "http://kit/v1/webhooks/unityfinance", "/v1/webhooks/unityfinance", "http://kit/v1/webhooks/unityfinance/withdrawal?merchantId=m"},
		{"", "http://kit/v1/webhooks/t365", "/v1/webhooks/t365", "http://kit/v1/webhooks/t365"},
	}
	for _, c := range cases {
		if got := Retarget(c.request, c.configured, c.marker); got != c.want {
			t.Errorf("Retarget(%q, %q) = %q, want %q", c.request, c.configured, got, c.want)
		}
	}
}

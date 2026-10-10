package stubhttp

import (
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

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
	if r, _ := http.Post(server.URL+"/__control", "application/json", strings.NewReader(`{"outcome":"failure"}`)); r.StatusCode != 200 {
		t.Fatalf("control answered %d", r.StatusCode)
	}
	if r, _ := http.Post(server.URL+"/__control", "application/json", strings.NewReader(`{"outcome":"maybe"}`)); r.StatusCode != 400 {
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

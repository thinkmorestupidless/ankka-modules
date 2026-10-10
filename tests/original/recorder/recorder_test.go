package recorder

import (
	"bytes"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestRecordsEachTestFunctionAsOneScenario(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"seen":` + string(body) + `,"id":"t-1"}`))
	}))
	defer server.Close()

	dir := t.TempDir()
	host := strings.TrimPrefix(server.URL, "http://")
	tr := New(http.DefaultTransport, dir, Services{host: "wallet"}, map[string]string{"commit": "abc"}, "recorder")
	client := &http.Client{Transport: tr}

	resp, err := client.Post(server.URL+"/bets?x=1", "application/json", strings.NewReader(`{"amount":1050}`))
	if err != nil {
		t.Fatal(err)
	}
	body, _ := io.ReadAll(resp.Body)
	if !strings.Contains(string(body), `"amount":1050`) {
		t.Fatalf("the caller did not get the response body back: %s", body)
	}

	file := filepath.Join(dir, "wallet", "TestRecordsEachTestFunctionAsOneScenario.json")
	b, err := os.ReadFile(file)
	if err != nil {
		t.Fatalf("no recording at %s: %v", file, err)
	}
	var rec Recording
	_ = json.Unmarshal(b, &rec)
	if rec.Scenario != "TestRecordsEachTestFunctionAsOneScenario" || len(rec.Steps) != 1 {
		t.Fatalf("recorded %+v", rec)
	}
	s := rec.Steps[0]
	var compact bytes.Buffer
	_ = json.Compact(&compact, s.Request.Body)
	if s.Request.Path != "/bets?x=1" || compact.String() != `{"amount":1050}` || s.Response.Status != 200 || s.Request.Service != "wallet" {
		t.Fatalf("step %+v", s)
	}
	if _, ok := s.Request.Headers["Authorization"]; ok {
		t.Fatal("a credential was recorded")
	}
}

func TestRefusesARequestThatWouldLeaveTheMachine(t *testing.T) {
	tr := New(http.DefaultTransport, t.TempDir(), Services{}, nil, "recorder")
	_, err := (&http.Client{Transport: tr}).Get("https://api.example.com/v1/payments")
	if err == nil || !strings.Contains(err.Error(), "nothing may leave the machine") {
		t.Fatalf("a request to the internet was not refused: %v", err)
	}
}

func TestLocalHosts(t *testing.T) {
	for host, want := range map[string]bool{
		"localhost:8083": true, "127.0.0.1:9000": true, "wallet.eitheror.svc.cluster.local": true,
		"bouncemoney-stub:18101": true, "10.0.0.4:80": true, "api.dreamzpay.com": false, "8.8.8.8:443": false,
	} {
		if Local(host) != want {
			t.Errorf("Local(%q) = %v", host, !want)
		}
	}
}

package merchantdriver

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/eitheror-io/ankka-casino/tests/original/recorder"
)

// stub is an original that seeds a sub-brand, opens and submits a session, and refuses an internal
// call whose secret is not the configured one.
func stub(secret string) *httptest.Server {
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = io.ReadAll(r.Body)
		internal := strings.HasPrefix(r.URL.Path, "/internal/") || strings.HasSuffix(r.URL.Path, "/capture") || strings.HasSuffix(r.URL.Path, "/fail")
		switch {
		case internal && r.Header.Get("X-Internal-Secret") != secret:
			w.WriteHeader(401)
			_, _ = w.Write([]byte(`{"error":"UNAUTHORIZED"}`))
		case strings.HasPrefix(r.URL.Path, "/internal/v1/seed/hierarchy/"):
			_, _ = w.Write([]byte(`{"holdingId":"h-1","companyId":"c-1","subBrandId":"sb-123456"}`))
		case strings.HasPrefix(r.URL.Path, "/internal/v1/seed/merchants/"):
			_, _ = w.Write([]byte(`{"merchantId":"sb-123456","merchantKey":"mk_rawkey_abcdef"}`))
		case r.URL.Path == "/v1/cashier/sessions":
			if r.Header.Get("merchantKey") != "mk_rawkey_abcdef" {
				w.WriteHeader(401)
				return
			}
			_, _ = w.Write([]byte(`{"session_token":"tok-987654","available_terminals":[{"terminalEntityId":"SIM"}]}`))
		case strings.HasSuffix(r.URL.Path, "/submit"):
			_, _ = w.Write([]byte(`{"paymentId":"pay-555555","status":"AWAITING_REDIRECT"}`))
		default:
			_, _ = w.Write([]byte(`{"status":"captured"}`))
		}
	}))
}

func TestARecordingHasTheContractShapeAndNoRunValuesOrSecrets(t *testing.T) {
	srv := stub("s3cret")
	defer srv.Close()
	dir := t.TempDir()
	cfg := Config{MerchantURL: srv.URL, SubstrateURL: srv.URL, InternalSecret: "s3cret", RecordTo: dir, Commit: "abc1234"}

	var brand string
	Scenario(t, cfg, "a capture reported by the gateway captures the payment", "004/FR-021", func(s *Session) {
		s.SubBrand()
		s.Deposit(true)
		body := s.CaptureBody(nil)
		s.Want(s.Capture("capture", body, s.Signed("whsec", body, 0)), 200)
		brand = s.ids["brand"]
	})

	b, err := os.ReadFile(filepath.Join(dir, "merchant", "a-capture-reported-by-the-gateway-captures-the-payment.json"))
	if err != nil {
		t.Fatal(err)
	}
	var rec recorder.Recording
	if err := json.Unmarshal(b, &rec); err != nil {
		t.Fatal(err)
	}
	if rec.Scenario != "a capture reported by the gateway captures the payment" || rec.Requirement != "004/FR-021" ||
		rec.Service != "merchant" || rec.Runs != 1 || rec.Original["commit"] != "abc1234" {
		t.Fatalf("header: %+v", rec)
	}
	if strings.Contains(string(b), "s3cret") {
		t.Error("the internal secret reached the recording")
	}
	names := []string{}
	for _, st := range rec.Steps {
		names = append(names, st.Name)
		text := st.Request.Path + string(st.Request.Body)
		for _, leaked := range []string{brand, "mk_rawkey_abcdef", "tok-987654", "pay-555555", "sb-123456"} {
			if strings.Contains(text, leaked) {
				t.Errorf("step %q carries a run's value: %q", st.Name, leaked)
			}
		}
	}
	want := []string{"setup: seed hierarchy", "setup: seed merchant: key, tree to SIM, SIM credential", "create cashier session", "submit", "capture"}
	if strings.Join(names, "|") != strings.Join(want, "|") {
		t.Fatalf("steps: %v", names)
	}
	session, submit, capture := rec.Steps[2], rec.Steps[3], rec.Steps[4]
	if session.Request.Headers["merchantKey"] != "{merchantKey}" || session.Capture["session"] != "$.session_token" {
		t.Errorf("session step: %+v", session)
	}
	if submit.Request.Path != "/v1/cashier/sessions/{session}/submit" || submit.Capture["payment"] != "$.paymentId" {
		t.Errorf("submit step: %+v", submit)
	}
	if capture.Request.Path != "/v1/payments/{payment}/capture" || capture.Request.Headers["X-PSP-Signature"] == "" {
		t.Errorf("capture step: %+v", capture)
	}
	if _, ok := capture.Request.Headers["X-Internal-Secret"]; ok {
		t.Error("the internal secret header was recorded")
	}
}

func TestTheSignatureIsTheOriginalsHmacOverTheRawBody(t *testing.T) {
	// hex(HMAC-SHA256("key", "The quick brown fox jumps over the lazy dog")), the RFC 4231-style vector
	got := Sign("key", []byte("The quick brown fox jumps over the lazy dog"))
	if got != "f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8" {
		t.Fatal(got)
	}
}

func TestNameUUIDIsJavasNameUUIDFromBytes(t *testing.T) {
	// UUID.nameUUIDFromBytes("seed.sub-brand.goldvibe".getBytes("UTF-8")), computed by the JDK
	if got := NameUUID("seed.sub-brand.goldvibe"); len(got) != 36 || got[14] != '3' {
		t.Fatal(got)
	}
}

func TestRefusesARequestThatWouldLeaveTheMachine(t *testing.T) {
	cfg := Config{MerchantURL: "https://merchant.example.com", InternalSecret: "x"}
	ft := &fatalT{TB: t}
	func() {
		defer func() { _ = recover() }()
		Scenario(&testing.T{}, cfg, "x", "", func(s *Session) {
			s.t = ft
			s.Read()
		})
	}()
	if !ft.fatal {
		t.Fatal("a request off the machine was not refused")
	}
}

type fatalT struct {
	testing.TB
	fatal bool
}

func (f *fatalT) Fatalf(string, ...any) { f.fatal = true; panic("fatal") }
func (f *fatalT) Helper()               {}

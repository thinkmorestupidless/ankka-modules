package walletdriver

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/eitheror-io/ankka-casino/tests/original/recorder"
)

// stub is an original that answers a bet with balances and a generated id, a withdrawal request
// with a minted request id, and refuses a call whose X-Internal-Secret is not the brand's HMAC.
func stub(secret, brand string) *httptest.Server {
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case strings.HasPrefix(r.URL.Path, "/twins/"):
			w.WriteHeader(201)
		case r.Header.Get("X-Internal-Secret") != Signature(secret, brand):
			w.WriteHeader(403)
			_, _ = w.Write([]byte(`{"error":"scope_violation"}`))
		case strings.HasSuffix(r.URL.Path, "/create-withdrawal-request"):
			w.WriteHeader(201)
			_, _ = w.Write([]byte(`{"requestId":"withdrawal-request/abc-123","status":"PENDING_REVIEW"}`))
		default:
			_, _ = w.Write([]byte(`{"realBalance":"90.00","bonusBalance":"0.00","currency":"EUR","walletType":"real","txnId":"transaction/xyz"}`))
		}
	}))
}

func TestARecordingHasTheContractShapeAndNoRunValues(t *testing.T) {
	srv := stub("s3cret", "spinvibe")
	defer srv.Close()
	dir := t.TempDir()
	cfg := Config{WalletURL: srv.URL, SubstrateURL: srv.URL, Brand: "spinvibe", Secret: "s3cret", RecordTo: dir, Commit: "abc1234"}

	var player, round, request string
	Scenario(t, cfg, "a bet funded from real balance alone opens its round with one real leg", "002/FR-005", func(s *Session) {
		s.NewWallet(WalletSetup{})
		s.Want(s.Bet("r1", 1000, BetOptions{}), 200)
		s.Want(s.Hold("w1", 500, 0), 201)
		s.Want(s.Complete("w1"), 200)
		player, round, request = s.Player, s.ids["r1"], s.ids["w1"]
	})

	b, err := os.ReadFile(filepath.Join(dir, "wallet", "a-bet-funded-from-real-balance-alone-opens-its-round-with-one-real-leg.json"))
	if err != nil {
		t.Fatal(err)
	}
	var rec recorder.Recording
	if err := json.Unmarshal(b, &rec); err != nil {
		t.Fatal(err)
	}
	if rec.Scenario != "a bet funded from real balance alone opens its round with one real leg" || rec.Requirement != "002/FR-005" ||
		rec.Service != "wallet" || rec.Runs != 1 || rec.Original["commit"] != "abc1234" || rec.Original["brand"] != "spinvibe" {
		t.Fatalf("header: %+v", rec)
	}
	// requests carry placeholders only; a response keeps what the original answered, ids included
	for _, st := range rec.Steps {
		text := st.Request.Path + string(st.Request.Body) + strings.Join(values(st.Request.Headers), " ")
		for _, leaked := range []string{player, round, request, Signature("s3cret", "spinvibe"), "s3cret", "/spinvibe/", "casino-spinvibe"} {
			if strings.Contains(text, leaked) {
				t.Errorf("step %q carries a run's value or a secret: %q", st.Name, leaked)
			}
		}
	}
	if strings.Contains(string(b), "s3cret") || strings.Contains(string(b), Signature("s3cret", "spinvibe")) {
		t.Error("the secret reached the recording")
	}
	steps := map[string]recorder.Step{}
	for _, st := range rec.Steps {
		steps[st.Name] = st
	}
	bet, hold, complete := steps["bet"], steps["create withdrawal request"], steps["complete withdrawal request"]
	bet.Request.Body, bet.Response.Body, complete.Request.Body = compact(bet.Request.Body), compact(bet.Response.Body), compact(complete.Request.Body)
	if bet.Request.Path != "/internal/v1/wallet/{brand}/{player}/EUR/bet" ||
		!strings.Contains(string(bet.Request.Body), `"roundId":"{r1}"`) ||
		!strings.Contains(string(bet.Request.Body), `"amount":"10.00"`) {
		t.Errorf("bet request: %s %s", bet.Request.Path, bet.Request.Body)
	}
	if bet.Request.Headers["X-Internal-Brand-Id"] != "{brand}" || bet.Request.Headers["X-Internal-Secret"] != "" {
		t.Errorf("bet headers: %v", bet.Request.Headers)
	}
	if bet.Response.Status != 200 || !strings.Contains(string(bet.Response.Body), `"realBalance":"90.00"`) {
		t.Errorf("bet response: %d %s", bet.Response.Status, bet.Response.Body)
	}
	if hold.Capture["w1"] != "$.requestId" || !strings.Contains(string(complete.Request.Body), `"request_id":"{w1}"`) {
		t.Errorf("capture: %+v then %s", hold.Capture, complete.Request.Body)
	}
	first := rec.Steps[0]
	if first.Request.Service != "substrate" || !strings.HasPrefix(first.Name, "setup: ") ||
		first.Request.Path != "/twins/casino-{brand}/{player}" {
		t.Errorf("setup step: %+v", first)
	}
}

func TestARefusalByTheOriginalFailsTheScenarioAndStillRecords(t *testing.T) {
	srv := stub("right", "spinvibe")
	defer srv.Close()
	dir := t.TempDir()
	cfg := Config{WalletURL: srv.URL, SubstrateURL: srv.URL, Brand: "spinvibe", Secret: "wrong", RecordTo: dir}
	failed := false
	probe := &recordingT{TB: t, failed: &failed}
	s := &Session{t: probe, cfg: cfg, Player: "p", Currency: "EUR", ids: map[string]string{},
		rec: &recorder.Recording{Scenario: "refused", Service: "wallet", Runs: 1}}
	s.cfg.Client = http.DefaultClient
	s.Want(s.Balance(), 200)
	s.write()
	if !failed {
		t.Error("a 403 where 200 was wanted did not fail the scenario")
	}
	if _, err := os.Stat(filepath.Join(dir, "wallet", "refused.json")); err != nil {
		t.Errorf("the recording was not written: %v", err)
	}
}

func compact(raw json.RawMessage) json.RawMessage {
	var b bytes.Buffer
	if json.Compact(&b, raw) != nil {
		return raw
	}
	return b.Bytes()
}

func values(m map[string]string) []string {
	out := make([]string, 0, len(m))
	for _, v := range m {
		out = append(out, v)
	}
	return out
}

// recordingT notes an Errorf without failing the enclosing test.
type recordingT struct {
	testing.TB
	failed *bool
}

func (r *recordingT) Errorf(string, ...any) { *r.failed = true }
func (r *recordingT) Helper()               {}

func TestAmountsAreTheOriginalsDecimalsAndIdsItsOwn(t *testing.T) {
	for minor, want := range map[int64]string{0: "0.00", 5: "0.05", 1050: "10.50", -1000: "-10.00", 500000000: "5000000.00"} {
		if got := Decimal(minor); got != want {
			t.Errorf("Decimal(%d) = %s, want %s", minor, got, want)
		}
	}
	// Java: UUID.nameUUIDFromBytes("spinvibe:p1:EUR".getBytes("UTF-8")).toString()
	if got := WalletTwinID("spinvibe", "p1", "EUR"); len(got) != 36 || got[14] != '3' || got != nameUUID("spinvibe:p1:EUR") {
		t.Errorf("WalletTwinID = %s: not the version 3 name UUID", got)
	}
	if got := FileName("a player's bet, refused"); got != "a-player-s-bet-refused" {
		t.Errorf("FileName: %s", got)
	}
}

package ingestdriver

import (
	"bufio"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"

	md "github.com/eitheror-io/ankka-casino/tests/original/merchant-driver"
	"github.com/eitheror-io/ankka-casino/tests/original/recorder"
)

// stub is an original that seeds sub-brands with a key each, keeps their keys as the original's twin
// does, opens a session under an active key (the header read whatever its case), submits and reads.
type stub struct {
	mu      sync.Mutex
	n       int
	keys    map[string]string // raw key -> sub-brand
	off     map[string]bool   // sub-brand -> its keys disabled
	headers []http.Header     // what each create carried
}

func (o *stub) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	o.mu.Lock()
	defer o.mu.Unlock()
	body, _ := io.ReadAll(r.Body)
	p := r.URL.Path
	switch {
	case strings.HasPrefix(p, "/internal/v1/seed/hierarchy/"):
		o.n++
		fmt.Fprintf(w, `{"holdingId":"h-%d","companyId":"c-%d","subBrandId":"sb-%d00000"}`, o.n, o.n, o.n)
	case strings.HasPrefix(p, "/internal/v1/seed/merchants/"):
		raw := fmt.Sprintf("eo_live_rawkey%d0000000000", o.n)
		o.keys[raw] = fmt.Sprintf("sb-%d00000", o.n)
		fmt.Fprintf(w, `{"merchantId":"sb-%d00000","merchantKey":%q}`, o.n, raw)
	case strings.HasPrefix(p, "/twins/eitheror-merchant-keys/") && r.Method == http.MethodGet:
		fmt.Fprint(w, `{"keys":"[{\"keyId\":\"key_abc12345\",\"name\":\"default\",\"keyHash\":\"feedface00\",\"active\":true,\"createdAt\":\"2026-10-24T10:00:00Z\"}]"}`)
	case strings.HasPrefix(p, "/twins/eitheror-merchant-keys/"):
		if strings.Contains(string(body), `\"active\":false`) {
			o.off[fmt.Sprintf("sb-%d00000", o.n)] = true
		}
		w.WriteHeader(201)
	case strings.HasPrefix(p, "/twins/"):
		w.WriteHeader(201)
	case p == "/v1/cashier/sessions":
		o.headers = append(o.headers, r.Header.Clone())
		key, sent := "", false
		for name, v := range r.Header {
			if strings.EqualFold(name, "merchantKey") {
				key, sent = v[0], true
			}
		}
		sb, ok := o.keys[key]
		switch {
		case !sent:
			w.WriteHeader(400)
			fmt.Fprint(w, "Request is missing required HTTP header 'merchantKey'")
		case !ok || o.off[sb]:
			w.WriteHeader(401)
			fmt.Fprint(w, `{"error":"INVALID_MERCHANT_KEY"}`)
		default:
			fmt.Fprintf(w, `{"session_token":"tok-%s-%d","available_terminals":[{"terminalEntityId":"SIM"}]}`, sb, len(o.headers))
		}
	case strings.HasSuffix(p, "/submit"):
		if strings.Contains(p, "00000000-0000") {
			w.WriteHeader(404)
			fmt.Fprint(w, `{"error":"SESSION_NOT_FOUND"}`)
			return
		}
		fmt.Fprint(w, `{"paymentId":"pay-555555","status":"AWAITING_REDIRECT"}`)
	case strings.HasPrefix(p, "/v1/payments/"):
		fmt.Fprint(w, `{"status":"AWAITING_REDIRECT","amountCents":5000}`)
	default:
		w.WriteHeader(404)
	}
}

func record(t *testing.T, name string) (recorder.Recording, string, *stub) {
	t.Helper()
	o := &stub{keys: map[string]string{}, off: map[string]bool{}}
	srv := httptest.NewServer(o)
	defer srv.Close()
	dir := t.TempDir()
	cfg := md.Config{MerchantURL: srv.URL, SubstrateURL: srv.URL, InternalSecret: "s3cret", RecordTo: dir, Commit: "abc1234", Service: "ingest"}
	var def Def
	for _, d := range Scenarios() {
		if d.Scenario() == name {
			def = d
		}
	}
	if def.Run == nil {
		t.Fatalf("no scenario %q", name)
	}
	run := &lenient{T: t}
	md.Scenario(run, cfg, def.Scenario(), def.Requirement, func(s *Session) { def.Run(run, s) })
	b, err := os.ReadFile(filepath.Join(dir, "ingest", md.FileName(name)+".json"))
	if err != nil {
		t.Fatal(err)
	}
	var rec recorder.Recording
	if err := json.Unmarshal(b, &rec); err != nil {
		t.Fatal(err)
	}
	return rec, string(b), o
}

// lenient keeps the scenario's expectations as notes: the stub answers as the original does, which is
// not always what the feature expects, and a run records that without failing.
type lenient struct {
	*testing.T
	notes []string
}

func (l *lenient) Errorf(format string, args ...any) {
	l.notes = append(l.notes, fmt.Sprintf(format, args...))
}

func step(t *testing.T, rec recorder.Recording, name string) recorder.Step {
	t.Helper()
	for _, s := range rec.Steps {
		if s.Name == name {
			return s
		}
	}
	t.Fatalf("no step %q", name)
	return recorder.Step{}
}

func TestARecordingIsTheIngestPortsWithNoRawKeyAndTheKeyCapturedFromTheSeed(t *testing.T) {
	rec, text, _ := record(t, "a payment read by another sub-brand is refused as not found")
	if rec.Service != "ingest" || rec.Requirement != "006/FR-009" || rec.Original["commit"] != "abc1234" {
		t.Fatalf("header: %+v", rec)
	}
	for _, raw := range []string{"eo_live_rawkey1", "eo_live_rawkey2", "s3cret"} {
		for _, s := range rec.Steps {
			if strings.Contains(s.Request.Path+string(s.Request.Body)+fmt.Sprint(s.Request.Headers), raw) {
				t.Errorf("step %q carries %q", s.Name, raw)
			}
		}
	}
	if !strings.Contains(text, `"merchantKey": "$.merchantKey"`) || !strings.Contains(text, `"otherMerchantKey": "$.merchantKey"`) {
		t.Error("the seed steps do not capture the keys")
	}
	read := step(t, rec, "read payment with another sub-brand's key")
	if read.Request.Headers["merchantKey"] != "{otherMerchantKey}" || read.Request.Path != "/v1/payments/{payment}" {
		t.Errorf("read: %+v", read.Request)
	}
}

func TestNoKeyIsSentAsNoHeaderAndRecordedEmpty(t *testing.T) {
	rec, _, o := record(t, "a request with no cashier key is refused as unauthenticated and no session exists")
	for name := range o.headers[0] {
		if strings.EqualFold(name, "merchantKey") {
			t.Fatal("a merchantKey header was sent")
		}
	}
	s := step(t, rec, "create cashier session with no key")
	if v, ok := s.Request.Headers["merchantKey"]; !ok || v != "" {
		t.Errorf("headers: %v", s.Request.Headers)
	}
	if s.Response.Status != 400 {
		t.Errorf("status %d", s.Response.Status)
	}
}

// The header goes on the wire as spelt (merchant-driver does not canonicalise extra headers), which
// Go's server canonicalises on arrival, so only the recording is checked here.
func TestTheHeaderIsRecordedInTheCaseTheScenarioNames(t *testing.T) {
	rec, _, _ := record(t, EdgeHeaderCase)
	if h := step(t, rec, "create cashier session, the header spelt MerchantKey").Request.Headers; h["MerchantKey"] != "{merchantKey}" {
		t.Errorf("recorded: %v", h)
	}
}

func TestADisabledKeyIsWrittenInactiveWithItsHashTemplated(t *testing.T) {
	rec, _, _ := record(t, "a request under a key no sub-brand holds active is refused without saying why [a cashier key a sub-brand disabled]")
	w := step(t, rec, "setup: disable the key")
	if w.Request.Path != "/twins/eitheror-merchant-keys/{subBrandTwin}" || strings.Contains(string(w.Request.Body), "feedface00") ||
		!strings.Contains(string(w.Request.Body), `\"active\":false`) {
		t.Errorf("disable: %s %s", w.Request.Path, w.Request.Body)
	}
	if s := step(t, rec, "create cashier session under the disabled key"); s.Response.Status != 401 {
		t.Errorf("status %d", s.Response.Status)
	}
}

// featureScenarios is cashier.feature's scenarios, an outline's once per example by its first column.
func featureScenarios(t *testing.T) []string {
	f, err := os.Open(filepath.Join("..", "..", "..", "features", "payments", "cashier.feature"))
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	var out []string
	outline, header := "", false
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		line := strings.TrimSpace(sc.Text())
		switch {
		case strings.HasPrefix(line, "Scenario Outline:"):
			outline, header = strings.TrimSpace(strings.TrimPrefix(line, "Scenario Outline:")), true
		case strings.HasPrefix(line, "Scenario:"):
			outline = ""
			out = append(out, strings.TrimSpace(strings.TrimPrefix(line, "Scenario:")))
		case outline != "" && strings.HasPrefix(line, "|"):
			if header {
				header = false
				continue
			}
			out = append(out, outline+" ["+strings.TrimSpace(strings.Split(line, "|")[1])+"]")
		}
	}
	return out
}

func TestEveryScenarioIsNamedAsCashierFeatureNamesItAndEveryFeatureScenarioIsDrivenOrSaidWhyNot(t *testing.T) {
	driven := map[string]bool{}
	for _, d := range Scenarios() {
		if driven[d.Scenario()] {
			t.Errorf("two scenarios named %q", d.Scenario())
		}
		driven[d.Scenario()] = true
		if !strings.HasPrefix(d.Requirement, "006/FR-") {
			t.Errorf("%q: requirement %q", d.Scenario(), d.Requirement)
		}
	}
	known := map[string]bool{EdgeHeaderCase: true}
	for _, n := range featureScenarios(t) {
		known[n] = true
		if !driven[n] && NotDriven[n] == "" {
			t.Errorf("%q is neither driven nor said why not", n)
		}
		if driven[n] && NotDriven[n] != "" {
			t.Errorf("%q is driven and said not to be", n)
		}
	}
	for n := range driven {
		if !known[n] {
			t.Errorf("cashier.feature names no scenario %q", n)
		}
	}
	for n := range NotDriven {
		if !known[n] {
			t.Errorf("NotDriven names %q, which cashier.feature does not", n)
		}
	}
}

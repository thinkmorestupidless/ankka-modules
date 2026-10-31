package payoutsdriver

import (
	"bufio"
	"bytes"
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
	"time"

	"github.com/eitheror-io/ankka-casino/tests/original/recorder"
)

const (
	signing  = "s3cret"
	internal = "internal-s3cret"
	operator = "operator-token-0000"
	outsider = "outsider-token-0000"
)

// stub is an original that registers, verifies, signs in, keeps terminals and twins, and moves each
// withdrawal request through the statuses its routes set; it refuses an outcome or a wallet deposit
// not signed with `internal` as the original does.
type stub struct {
	mu       sync.Mutex
	n        int
	status   map[string]string // request id -> status
	outcomes []string          // every X-Internal-Secret the outcome route received
	deposits []string          // every X-Internal-Secret the wallet's deposit received
}

func (o *stub) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	o.mu.Lock()
	defer o.mu.Unlock()
	raw, _ := io.ReadAll(r.Body)
	body := map[string]any{}
	_ = json.Unmarshal(raw, &body)
	p := r.URL.Path
	parts := strings.Split(strings.Trim(p, "/"), "/")
	last := parts[len(parts)-1]
	reply := func(status int, format string, args ...any) {
		w.WriteHeader(status)
		fmt.Fprintf(w, format, args...)
	}
	switch {
	case strings.HasSuffix(p, "/register"):
		o.n++
		reply(201, `{"playerId":"0000000%d-aaaa-4aaa-8aaa-00000000000%d","status":"pending_email_verification"}`, o.n, o.n)
	case strings.HasSuffix(p, "/verify-email"):
		reply(200, `{"status":"verified"}`)
	case strings.HasSuffix(p, "/login"):
		o.n++
		reply(200, `{"token":"jwt-%d-secretpart","refreshToken":"rt-%d-secretpart"}`, o.n, o.n)
	case strings.HasPrefix(p, "/internal/v1/wallet/"):
		o.deposits = append(o.deposits, r.Header.Get("X-Internal-Secret"))
		if r.Header.Get("X-Internal-Secret") != Signature(internal, "spinvibe") {
			reply(401, `{"error":"UNAUTHORIZED"}`)
			return
		}
		reply(200, `{"realBalance":"100.00"}`)
	case strings.HasPrefix(p, "/internal/v1/casino/withdrawal-requests/"):
		o.outcomes = append(o.outcomes, r.Header.Get("X-Internal-Secret"))
		if r.Header.Get("X-Internal-Secret") != internal {
			reply(401, `{"error":"UNAUTHORIZED"}`)
			return
		}
		id := parts[len(parts)-2]
		o.status[id] = map[string]string{"COMPLETED": "COMPLETED", "FAILED": "FAILED", "REVERSED": "REVERSED"}[body["outcome"].(string)]
		reply(200, `{"ok":true}`)
	case strings.HasPrefix(p, "/twins/eitheror-terminal-index/") && r.Method == http.MethodGet:
		reply(200, `{"ids":"existing-terminal"}`)
	case strings.HasPrefix(p, "/twins/"):
		reply(201, `{}`)
	case p == "/__control":
		reply(200, `{}`)
	case strings.HasSuffix(p, "/bonus/status"):
		reply(200, `{"bonusStatus":"ACTIVE"}`)
	case strings.HasSuffix(p, "/balance"):
		reply(200, `{"realBalance":"50.00"}`)
	case strings.HasSuffix(p, "/bonus/grant/spinvibe/"+last) || strings.Contains(p, "/bonus/grant/"):
		reply(200, `{}`)
	case strings.HasSuffix(p, "/withdrawal-config"):
		reply(200, `{"withdrawalTurnoverMultiplier":1,"withdrawalTurnoverDefinition":"gross_wagered"}`)
	case strings.HasSuffix(p, "/payout-destinations") && r.Method == http.MethodPost:
		o.n++
		reply(201, `{"id":"payout-destination/dest-%d"}`, o.n)
	case strings.Contains(p, "/payout-destinations") && r.Method == http.MethodDelete:
		w.WriteHeader(204)
	case strings.Contains(p, "/payout-destinations"):
		reply(200, `{"destinations":[]}`)
	case strings.HasSuffix(p, "/withdrawal-terminals"):
		reply(200, `{"terminals":[]}`)
	case strings.HasSuffix(p, "/withdrawal-requests") && r.Method == http.MethodPost:
		o.n++
		id := fmt.Sprintf("req-%d", o.n)
		o.status[id] = "PENDING_REVIEW"
		reply(201, `{"requestId":%q,"status":"PENDING_REVIEW","bonusForfeited":false}`, id)
	case parts[0] == "v1" && len(parts) == 8 && parts[6] == "withdrawal-requests" && r.Method == http.MethodDelete:
		o.status[last] = "CANCELLED"
		reply(200, `{"requestId":%q,"status":"CANCELLED"}`, last)
	case len(parts) == 7 && parts[4] == "withdrawal-requests" && r.Method == http.MethodPost:
		id := parts[5]
		next := map[string]string{"approve": "AWAITING_MANUAL_PAYOUT", "reject": "REJECTED", "confirm-payout": "COMPLETED", "mark-payout-failed": "FAILED"}[last]
		o.status[id] = next
		reply(200, `{"requestId":%q,"status":%q}`, id, next)
	case len(parts) == 6 && parts[4] == "withdrawal-requests":
		reply(200, `{"requestId":%q,"status":%q,"payoutStatus":"INITIATED","pspPayoutRef":"uf-0000"}`, last, o.status[last])
	default:
		reply(200, `{"requests":[]}`)
	}
}

// lenient keeps the scenario's expectations as notes: the stub is not the original, and a run
// records what it answers without failing.
type lenient struct {
	*testing.T
	notes []string
}

func (l *lenient) Errorf(format string, args ...any) {
	l.notes = append(l.notes, fmt.Sprintf(format, args...))
}

func record(t *testing.T, def Def) (recorder.Recording, string, *stub) {
	t.Helper()
	o := &stub{status: map[string]string{}}
	srv := httptest.NewServer(o)
	defer srv.Close()
	dir := t.TempDir()
	cfg := Config{CasinoURL: srv.URL, KeycloakURL: srv.URL, SubstrateURL: srv.URL, WalletURL: srv.URL, WalletInternalURL: srv.URL, StubURL: srv.URL,
		Brand: "spinvibe", SigningSecret: signing, InternalSecret: internal, OperatorToken: operator, OutsiderToken: outsider,
		RecordTo: dir, Commit: "abc1234", Patience: 50 * time.Millisecond}
	run := &lenient{T: t}
	Scenario(run, cfg, def.Scenario(), def.Requirement, func(s *Session) { def.Run(run, s) })
	b, err := os.ReadFile(filepath.Join(dir, "payouts", FileName(def.Scenario())+".json"))
	if err != nil {
		t.Fatal(err)
	}
	var rec recorder.Recording
	if err := json.Unmarshal(b, &rec); err != nil {
		t.Fatal(err)
	}
	return rec, string(b), o
}

func find(t *testing.T, name string) Def {
	t.Helper()
	for _, d := range Scenarios() {
		if d.Scenario() == name {
			return d
		}
	}
	t.Fatalf("no scenario %q", name)
	return Def{}
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

// compact is a recorded body on one line: the recording indents it.
func compact(b json.RawMessage) string {
	var buf bytes.Buffer
	if err := json.Compact(&buf, b); err != nil {
		return string(b)
	}
	return buf.String()
}

func TestTheOriginalsNameUUIDsAndMajorUnits(t *testing.T) {
	// Computed outside Go: MD5 of the name with the version 3 and IETF variant bits, as Java's
	// UUID.nameUUIDFromBytes makes it.
	if got := NameUUID("seed.sub-brand.spinvibe"); got != "2867b301-c32d-3879-b766-10321f0302a8" {
		t.Errorf("got %s", got)
	}
	for minor, want := range map[int64]string{0: "0.00", -100: "-1.00", 5000: "50.00", 1050: "10.50", 7: "0.07"} {
		if got := Decimal(minor); got != want {
			t.Errorf("Decimal(%d) = %s, want %s", minor, got, want)
		}
	}
}

// Every scenario runs against the stub, and none writes the signing secret, the internal secret (as
// it is or as its HMAC) or psp-stub's credentials anywhere, nor sends a token or an address as it is.
// A sign-in's answer keeps its token, as every driver's does; the merge redacts it.
func TestEveryScenarioRecordsNoCredentialAndNamesEveryTokenItCarries(t *testing.T) {
	for _, d := range Scenarios() {
		d := d
		t.Run(d.Scenario(), func(t *testing.T) {
			rec, text, _ := record(t, d)
			if rec.Service != "payouts" || rec.Requirement != d.Requirement || rec.Original["commit"] != "abc1234" || len(rec.Steps) == 0 {
				t.Fatalf("header: %+v", rec)
			}
			for _, raw := range []string{signing, internal, Signature(internal, "spinvibe"), stubWithdrawKey, stubSignKey, "X-Internal-Secret"} {
				if strings.Contains(text, raw) {
					t.Errorf("the recording carries %q", raw)
				}
			}
			for _, s := range rec.Steps {
				sent := s.Request.Path + string(s.Request.Body) + fmt.Sprint(s.Request.Headers)
				for _, raw := range []string{operator, outsider, "secretpart", "@example.test"} {
					if strings.Contains(sent, raw) {
						t.Errorf("step %q sends %q: %s", s.Name, raw, sent)
					}
				}
				if a, ok := s.Request.Headers["Authorization"]; ok && !(strings.HasPrefix(a, "Bearer {") && strings.HasSuffix(a, "}")) {
					t.Errorf("step %q: Authorization %q", s.Name, a)
				}
			}
		})
	}
}

func TestTheOutcomeIsSignedWithTheInternalSecretWhichIsNeverRecorded(t *testing.T) {
	rec, _, o := record(t, find(t, "an initiated withdrawal payment whose outcome arrives as completed completes the request"))
	if len(o.outcomes) != 1 || o.outcomes[0] != internal {
		t.Fatalf("the outcome route received %q", o.outcomes)
	}
	out := step(t, rec, "the provider's outcome arrives as completed")
	if out.Request.Path != "/internal/v1/casino/withdrawal-requests/{requestId}/outcome" || out.Request.Service != "casino" {
		t.Errorf("outcome: %+v", out.Request)
	}
	if _, ok := out.Request.Headers["X-Internal-Secret"]; ok {
		t.Errorf("the secret's header is recorded: %+v", out.Request.Headers)
	}
	if b := compact(out.Request.Body); b != `{"brandId":"spinvibe","outcome":"COMPLETED","pspReference":"{pspReference}"}` {
		t.Errorf("outcome body: %s", b)
	}
	if out.Response.Status != 200 {
		t.Errorf("the stub refused the outcome: %d", out.Response.Status)
	}
}

func TestADepositIsSignedWithTheBrandsHMACAndRecordedAsSetup(t *testing.T) {
	rec, _, o := record(t, find(t, "a verified player passes the identity gate"))
	if len(o.deposits) != 1 || o.deposits[0] != Signature(internal, "spinvibe") {
		t.Fatalf("the wallet's deposit received %q", o.deposits)
	}
	dep := step(t, rec, "setup: deposit to the player")
	if dep.Request.Service != "wallet" || dep.Request.Path != "/internal/v1/wallet/spinvibe/{playerId}/EUR/deposit" ||
		dep.Request.Headers["X-Internal-Brand-Id"] != "spinvibe" || !strings.Contains(compact(dep.Request.Body), `"amount":"100.00"`) {
		t.Errorf("deposit: %+v %s", dep.Request, dep.Request.Body)
	}
}

func TestARequestCarriesItsAmountInMajorUnitsAndLaterStepsItsIDByName(t *testing.T) {
	rec, text, _ := record(t, find(t, "a player cancels a request pending review and the amount returns"))
	if !strings.Contains(text, `"requestId": "$.requestId"`) {
		t.Error("the request does not capture its id")
	}
	held := step(t, rec, "setup: request a withdrawal of 50.00")
	if b := compact(held.Request.Body); b != `{"amount":"50.00","currency":"EUR","terminalId":"{manualTerminal}"}` ||
		held.Request.Path != "/v1/casino/brands/spinvibe/players/{playerId}/withdrawal-requests" || held.Request.Headers["Authorization"] != "Bearer {token}" {
		t.Errorf("request: %+v %s", held.Request, b)
	}
	if c := step(t, rec, "cancel the request"); c.Request.Method != http.MethodDelete || c.Request.Path != "/v1/casino/brands/spinvibe/players/{playerId}/withdrawal-requests/{requestId}" {
		t.Errorf("cancel: %+v", c.Request)
	}
}

func TestATerminalIsWrittenUnderItsIDAppendedToTheBrandsIndexAndRetiredAndRemovedAfterwards(t *testing.T) {
	rec, _, _ := record(t, find(t, "a request to a crypto terminal is refused when no address is supplied"))
	index := "/twins/eitheror-terminal-index/" + NameUUID("eitheror-terminal-index."+NameUUID("seed.sub-brand.spinvibe"))
	add := step(t, rec, "setup: add the terminal to the brand's index")
	if add.Request.Path != index || compact(add.Request.Body) != `{"ids":"existing-terminal|{cryptoTerminal}"}` {
		t.Errorf("index: %+v %s", add.Request, add.Request.Body)
	}
	if w := step(t, rec, "setup: the terminal payouts driver crypto"); w.Request.Path != "/twins/eitheror-terminal/{cryptoTerminal}" ||
		!strings.Contains(compact(w.Request.Body), `"supportsWithdrawal":true`) {
		t.Errorf("terminal: %+v %s", w.Request, w.Request.Body)
	}
	if r := step(t, rec, "setup: retire the terminal payouts driver crypto"); compact(r.Request.Body) != `{"status":"inactive"}` {
		t.Errorf("retire: %s", r.Request.Body)
	}
	if last := rec.Steps[len(rec.Steps)-1]; last.Name != "setup: take the terminal out of the brand's index" ||
		last.Request.Path != index || compact(last.Request.Body) != `{"ids":"existing-terminal"}` {
		t.Errorf("the last step is %q %s", last.Name, last.Request.Body)
	}
}

func TestTheQueueIsNarrowedToTheRunsPlayersByItsTag(t *testing.T) {
	rec, _, _ := record(t, find(t, "a queue filtered by payment status needs review shows only those requests"))
	q := step(t, rec, "filter the queue by payment status needs review")
	if q.Request.Path != "/v1/casino/brands/spinvibe/withdrawal-requests?status=ALL&payoutStatus=NEEDS_REVIEW&playerSearch={tag}" ||
		q.Request.Headers["Authorization"] != "Bearer {operatorToken}" {
		t.Errorf("queue: %+v", q.Request)
	}
	if r := step(t, rec, "setup: register the review player"); !strings.Contains(compact(r.Request.Body), `"email":"{reviewEmail}"`) {
		t.Errorf("register: %s", r.Request.Body)
	}
}

func TestTheLimitsAreRestoredAfterTheScenario(t *testing.T) {
	rec, _, _ := record(t, find(t, "a request that would pass the brand's weekly withdrawal limit is refused naming the limit, what was used and what remains"))
	set := step(t, rec, "setup: set the brand's withdrawal limits")
	if b := compact(set.Request.Body); !strings.Contains(b, `"weeklyWithdrawalLimitEur":1000`) || !strings.Contains(b, `"monthlyWithdrawalLimitEur":null`) ||
		!strings.Contains(b, `"withdrawalTurnoverDefinition":"gross_wagered"`) {
		t.Errorf("set: %s", b)
	}
	last := rec.Steps[len(rec.Steps)-1]
	if last.Name != "setup: restore the brand's withdrawal limits" || !strings.Contains(compact(last.Request.Body), `"weeklyWithdrawalLimitEur":null`) {
		t.Errorf("the last step is %q: %s", last.Name, last.Request.Body)
	}
}

// featureScenarios is every scenario of features/payouts/*.feature, an outline's once per example by
// its first column.
func featureScenarios(t *testing.T) []string {
	files, err := filepath.Glob(filepath.Join("..", "..", "..", "features", "payouts", "*.feature"))
	if err != nil || len(files) == 0 {
		t.Fatalf("no features: %v", err)
	}
	var out []string
	for _, file := range files {
		f, err := os.Open(file)
		if err != nil {
			t.Fatal(err)
		}
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
		_ = f.Close()
	}
	return out
}

func TestEveryScenarioIsNamedAsItsFeatureNamesItAndEveryFeatureScenarioIsDrivenOrSaidWhyNot(t *testing.T) {
	driven := map[string]bool{}
	for _, d := range Scenarios() {
		if driven[d.Scenario()] {
			t.Errorf("two scenarios named %q", d.Scenario())
		}
		driven[d.Scenario()] = true
		if !strings.HasPrefix(d.Requirement, "007/FR-") {
			t.Errorf("%q: requirement %q", d.Scenario(), d.Requirement)
		}
	}
	known := map[string]bool{}
	for n := range Extra {
		known[n] = true
	}
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
			t.Errorf("no feature names a scenario %q", n)
		}
	}
	for n := range NotDriven {
		if !known[n] {
			t.Errorf("NotDriven names %q, which no feature does", n)
		}
	}
	for n := range Marked {
		if !driven[n] {
			t.Errorf("Marked names %q, which the driver does not record", n)
		}
	}
}

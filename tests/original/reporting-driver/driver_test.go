package reportingdriver

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
	"regexp"
	"sort"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/eitheror-io/ankka-casino/tests/original/recorder"
)

const (
	signing  = "s3cret"
	internal = "internal-secret-0000"
	operator = "operator-token-0000"
	support  = "support-token-0000"
	unleash  = "unleash-admin-token-0000"
	brandID  = "spinvibe"
)

// stub is an original that registers, verifies and signs in, lists the login session, takes a signed
// deposit, launches, binds and settles rounds as fake-st8 does, grants and excludes, keeps Unleash's
// admin API, and answers the reporting routes: the Reports flag on for `spinvibe` alone, the reports
// refused to the support operator, and the dispute report's refusals by DashboardRoutes' own rules.
type stub struct {
	mu       sync.Mutex
	n        int
	player   string
	launches map[string]string // launch token -> game session id
	rounds   map[string]int    // game session id -> rounds settled
	signed   []bool            // whether each deposit's signature verified
	auths    []string          // every Authorization header received
}

func (o *stub) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	o.mu.Lock()
	defer o.mu.Unlock()
	o.auths = append(o.auths, r.Header.Get("Authorization"))
	raw, _ := io.ReadAll(r.Body)
	body := map[string]any{}
	_ = json.Unmarshal(raw, &body)
	p, q := r.URL.Path, r.URL.Query()
	reply := func(status int, format string, args ...any) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(status)
		fmt.Fprintf(w, format, args...)
	}
	file := func(contentType, ext, text string) {
		w.Header().Set("Content-Type", contentType)
		w.Header().Set("Content-Disposition", "attachment; filename=dispute-report-ref-"+fmt.Sprint(o.n)+"."+ext)
		fmt.Fprint(w, text)
	}
	backoffice := "/v1/casino/backoffice/"
	switch {
	case strings.HasPrefix(p, "/api/admin/"):
		switch {
		case r.Method == http.MethodGet:
			reply(404, `{"message":"not found"}`)
		case strings.HasSuffix(p, "/strategies"):
			reply(200, `{"id":"strategy-1"}`)
		default:
			reply(200, `{}`)
		}
	case strings.HasSuffix(p, "/register"):
		o.n++
		o.player = fmt.Sprintf("0000000%d-aaaa-4aaa-8aaa-00000000000%d", o.n, o.n)
		reply(201, `{"playerId":%q,"status":"pending_email_verification"}`, o.player)
	case strings.HasSuffix(p, "/verify-email"):
		reply(200, `{"status":"verified"}`)
	case strings.HasSuffix(p, "/login"):
		reply(200, `{"token":"jwt-player-secretpart","refreshToken":"rt-secretpart","playerId":%q}`, o.player)
	case strings.HasPrefix(p, "/internal/v1/wallet/"):
		o.signed = append(o.signed, r.Header.Get("X-Internal-Secret") == Signature(internal, brandID) &&
			r.Header.Get("X-Internal-Brand-Id") == brandID)
		reply(201, `{"balance":"100.00"}`)
	case strings.HasSuffix(p, "/launch"):
		o.n++
		gs, lt := fmt.Sprintf("gs-%d-0000", o.n), fmt.Sprintf("lt-%d-0000", o.n)
		o.launches[lt] = gs
		reply(200, `{"sessionId":%q,"launchToken":%q,"gameUrl":"http://fake-st8.svc.cluster.local:30096/game?token=%s"}`, gs, lt, lt)
	case p == "/game":
		w.Header().Set("Content-Type", "text/html")
		fmt.Fprint(w, "<html>game</html>")
	case p == "/test/bet":
		reply(200, `{"status":"ok"}`)
	case p == "/test/win" || p == "/test/lose":
		lt, _ := body["launchToken"].(string)
		o.rounds[o.launches[lt]]++
		reply(200, `{"status":"ok"}`)
	case strings.HasPrefix(p, "/v1/casino/bonus/grant/"):
		reply(201, `{"grantId":"grant-0000"}`)
	case strings.HasSuffix(p, "/permanent-exclusion"):
		reply(200, `{"status":"self_excluded"}`)
	case strings.HasPrefix(p, backoffice) && strings.Contains(p, "/reports"):
		if !strings.HasPrefix(p, backoffice+brandID+"/") {
			reply(404, `{"error":"not_found"}`)
			return
		}
		if r.Header.Get("Authorization") == "Bearer "+support {
			w.Header().Set("Content-Type", "text/plain")
			w.WriteHeader(403)
			fmt.Fprint(w, "The supplied authentication is not authorized to access this resource")
			return
		}
		reply(200, `{"from":%q,"to":%q,"currency":"EUR"}`, q.Get("from"), q.Get("to"))
	case strings.HasSuffix(p, "/sessions") && q.Get("type") == "player":
		reply(200, `{"sessions":[{"sessionId":"ps-0000-1111","playerRef":%q}],"total":1}`, q.Get("playerEmail"))
	case strings.HasPrefix(p, backoffice+brandID+"/sessions/game/"):
		id := strings.TrimSuffix(strings.TrimPrefix(p, backoffice+brandID+"/sessions/game/"), "/rounds")
		rounds := make([]string, o.rounds[id])
		for i := range rounds {
			rounds[i] = `{"roundNumber":` + fmt.Sprint(i+1) + `}`
		}
		reply(200, `{"sessionId":%q,"rounds":[%s]}`, id, strings.Join(rounds, ","))
	case strings.HasSuffix(p, "/dashboard/activity"):
		reply(200, `{"events":[{"eventType":"bonus_grant","playerId":%q},{"eventType":"self_exclusion","playerId":%q}]}`, o.player, o.player)
	case strings.Contains(p, "/dashboard/"):
		reply(200, `{"currency":"EUR"}`)
	case strings.HasSuffix(p, "/dispute-report"):
		player := strings.TrimSuffix(strings.TrimPrefix(p, backoffice+brandID+"/players/"), "/dispute-report")
		if msg := disputeRule(q.Get("sessionId"), q.Get("fromDate"), q.Get("toDate")); msg != "" {
			reply(400, `{"error":%q}`, msg)
			return
		}
		if player != o.player {
			reply(404, `{"error":"player_not_found"}`)
			return
		}
		if sid := q.Get("sessionId"); sid != "" && sid != "ps-0000-1111" {
			reply(404, `{"error":"session_not_found"}`)
			return
		}
		o.n++
		if q.Get("format") == "csv" {
			file("text/csv; charset=UTF-8", "csv", "Reference,ref\n")
		} else {
			file("text/html; charset=UTF-8", "html", "<html>report</html>")
		}
	default:
		reply(200, `{}`)
	}
}

// disputeRule is DashboardRoutes' dispute-report validation: the parameter shape, then the range.
func disputeRule(session, from, to string) string {
	switch {
	case session != "" && (from != "" || to != ""):
		return "provide either sessionId or fromDate+toDate, not both"
	case session == "" && (from == "" || to == ""):
		return "provide either sessionId or both fromDate and toDate"
	case session != "":
		return ""
	}
	f, err1 := time.Parse(time.DateOnly, from)
	t, err2 := time.Parse(time.DateOnly, to)
	switch days := int(t.Sub(f).Hours() / 24); {
	case err1 != nil || err2 != nil:
		return "invalid date format; use ISO 8601 (YYYY-MM-DD)"
	case days > 365:
		return "date range must not exceed 365 days"
	case days < 0:
		return "fromDate must be before toDate"
	}
	return ""
}

// strict fails the test for every expectation the stub does not meet: it answers as the original's
// routes do, so a recording's own checks must hold of it.
type strict struct {
	*testing.T
	notes []string
}

func (l *strict) Errorf(format string, args ...any) {
	l.notes = append(l.notes, fmt.Sprintf(format, args...))
}

type run struct {
	seed  recorder.Recording
	recs  map[string]recorder.Recording
	stub  *stub
	notes []string
}

// record sows the seed and makes every recording against one stub, as TestReporting does against the
// original, and reads back what each wrote.
func record(t *testing.T) run {
	t.Helper()
	o := &stub{launches: map[string]string{}, rounds: map[string]int{}}
	srv := httptest.NewServer(o)
	defer srv.Close()
	dir := t.TempDir()
	cfg := Config{CasinoURL: srv.URL, KeycloakURL: srv.URL, FakeSt8URL: srv.URL, WalletInternalURL: srv.URL,
		InternalSecret: internal, UnleashURL: srv.URL, UnleashToken: unleash, Brand: brandID, FlagOffBrand: "reports-off",
		SigningSecret: signing, OperatorToken: operator, SupportToken: support, RecordTo: dir, Commit: "abc1234",
		Patience: time.Second}
	l := &strict{T: t}
	s := newSession(l, cfg, SeedName, "015/FR-027")
	var cleanups []func()
	s.ReportsOn(func(f func()) { cleanups = append(cleanups, f) })
	seed := s.Sow()
	s.write()
	for _, f := range cleanups {
		f()
	}
	for _, d := range Scenarios() {
		Scenario(l, cfg, seed, d.Name, d.Requirement, func(s *Session) { d.Run(l, s) })
	}
	read := func(name string) recorder.Recording {
		b, err := os.ReadFile(filepath.Join(dir, "reporting", FileName(name)+".json"))
		if err != nil {
			t.Fatal(err)
		}
		var rec recorder.Recording
		if err := json.Unmarshal(b, &rec); err != nil {
			t.Fatal(err)
		}
		return rec
	}
	out := run{seed: read(SeedName), recs: map[string]recorder.Recording{}, stub: o, notes: l.notes}
	for _, d := range Scenarios() {
		out.recs[d.Name] = read(d.Name)
	}
	return out
}

func step(t *testing.T, rec recorder.Recording, name string) recorder.Step {
	t.Helper()
	for _, s := range rec.Steps {
		if s.Name == name {
			return s
		}
	}
	t.Fatalf("%s: no step %q", rec.Scenario, name)
	return recorder.Step{}
}

func TestEveryRecordingHoldsOfAnOriginalThatAnswersAsTheRoutesDo(t *testing.T) {
	r := record(t)
	for _, n := range r.notes {
		t.Error(n)
	}
	for _, ok := range r.stub.signed {
		if !ok {
			t.Error("a deposit was not signed with the internal secret's HMAC of the brand")
		}
	}
	if len(r.stub.signed) != 1 {
		t.Errorf("%d deposits", len(r.stub.signed))
	}
}

// No recording writes a token, a password, a secret or an address as it is, and every token it
// carries is named.
func TestTheSeedAndEveryRecordingWriteNoCredentialAndNameEveryTokenTheyCarry(t *testing.T) {
	r := record(t)
	all := append([]recorder.Recording{r.seed}, values(r.recs)...)
	for _, rec := range all {
		if rec.Service != "reporting" || rec.Original["commit"] != "abc1234" || len(rec.Steps) == 0 || !strings.HasPrefix(rec.Requirement, "015/FR-") {
			t.Errorf("header: %+v", rec)
		}
		for _, s := range rec.Steps {
			sent := s.Request.Path + string(s.Request.Body) + fmt.Sprint(s.Request.Headers)
			for _, raw := range []string{signing, internal, Signature(internal, brandID), operator, support, unleash, "secretpart"} {
				if strings.Contains(sent, raw) {
					t.Errorf("%s: step %q carries %q: %s", rec.Scenario, s.Name, raw, sent)
				}
			}
			if a, ok := s.Request.Headers["Authorization"]; ok && !(strings.HasPrefix(a, "Bearer {") && strings.HasSuffix(a, "}")) {
				t.Errorf("%s: step %q: Authorization %q", rec.Scenario, s.Name, a)
			}
			if strings.Contains(sent, "@example.test") {
				t.Errorf("%s: step %q carries an address as it is: %s", rec.Scenario, s.Name, sent)
			}
		}
	}
	for _, a := range r.stub.auths {
		if a == "Bearer " || strings.HasPrefix(a, "Bearer {") {
			t.Errorf("a request carried %q", a)
		}
	}
}

// compact is a recorded body on one line: the recording indents it.
func compact(b json.RawMessage) string {
	var buf bytes.Buffer
	if err := json.Compact(&buf, b); err != nil {
		return string(b)
	}
	return buf.String()
}

func values(m map[string]recorder.Recording) []recorder.Recording {
	keys := make([]string, 0, len(m))
	for k := range m {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	out := make([]recorder.Recording, 0, len(keys))
	for _, k := range keys {
		out = append(out, m[k])
	}
	return out
}

func TestTheSeedIsSetupOnlyAndCapturesWhatTheRecordingsName(t *testing.T) {
	r := record(t)
	captured := map[string]bool{}
	for _, s := range r.seed.Steps {
		if !strings.HasPrefix(s.Name, "setup: ") && !strings.HasPrefix(s.Name, "observe: ") {
			t.Errorf("the seed's step %q would be compared", s.Name)
		}
		for name := range s.Capture {
			captured[name] = true
		}
	}
	for _, name := range []string{"playerId", "token", "playerSessionId", "gameSessionId", "otherGameSessionId", "launchToken", "otherLaunchToken"} {
		if !captured[name] {
			t.Errorf("the seed does not capture %s", name)
		}
	}
	deposit := step(t, r.seed, "setup: deposit to the player")
	if _, ok := deposit.Request.Headers["X-Internal-Secret"]; ok || deposit.Request.Headers["X-Internal-Brand-Id"] != brandID ||
		deposit.Request.Service != "wallet" || deposit.Request.Path != "/internal/v1/wallet/spinvibe/{playerId}/EUR/deposit" {
		t.Errorf("deposit: %+v", deposit.Request)
	}
	if b := compact(step(t, r.seed, "setup: stake round A").Request.Body); !strings.Contains(b, `"launchToken":"{launchToken}"`) ||
		!strings.Contains(b, `"roundId":"{roundA}"`) {
		t.Errorf("stake: %s", b)
	}
	if p := step(t, r.seed, "setup: load fake-slot-002's page").Request.Path; p != "/game?token={otherLaunchToken}" {
		t.Errorf("the game page: %s", p)
	}
	if p := step(t, r.seed, "observe: game session two's rounds").Request.Path; p != "/v1/casino/backoffice/spinvibe/sessions/game/{otherGameSessionId}/rounds" {
		t.Errorf("rounds: %s", p)
	}
	if got := r.stub.rounds; got["gs-2-0000"] != 3 || got["gs-3-0000"] != 2 {
		t.Errorf("rounds settled: %v", got)
	}
	if h := step(t, r.seed, "setup: show the Reports tab for the brand").Request.Headers; h["Authorization"] != "" {
		t.Errorf("Unleash's token was written: %v", h)
	}
}

func TestEveryRecordingNamesTheSeedAndThePeriodAndKeepsADocumentsHeaders(t *testing.T) {
	r := record(t)
	html := step(t, r.recs["dispute-session-html"], "the dispute report")
	if html.Request.Path != "/v1/casino/backoffice/spinvibe/players/{playerId}/dispute-report?sessionId={playerSessionId}&format=html" ||
		html.Request.Headers["Authorization"] != "Bearer {operatorToken}" {
		t.Errorf("html: %+v", html.Request)
	}
	if h := html.Response.Headers; !strings.HasPrefix(h["Content-Type"], "text/html") || !strings.HasPrefix(h["Content-Disposition"], "attachment") {
		t.Errorf("html's headers: %v", h)
	}
	if h := step(t, r.recs["dispute-session-csv"], "the dispute report").Response.Headers; !strings.HasPrefix(h["Content-Type"], "text/csv") {
		t.Errorf("csv's headers: %v", h)
	}
	if p := step(t, r.recs["dispute-range-html"], "the dispute report").Request.Path; !strings.HasSuffix(p, "?fromDate={from}&toDate={to}&format=html") {
		t.Errorf("range: %s", p)
	}
	if p := step(t, r.recs["dispute-refusals-unknown-player"], "the dispute report").Request.Path; !strings.Contains(p, "/players/{unknownPlayerId}/") {
		t.Errorf("unknown player: %s", p)
	}
	if p := step(t, r.recs["reports-ggr"], "reports-ggr").Request.Path; p != "/v1/casino/backoffice/spinvibe/reports/ggr?from={from}&to={to}" {
		t.Errorf("ggr: %s", p)
	}
	flagOff := r.recs["reports-flag-off"]
	if len(flagOff.Steps) != 6 || flagOff.Steps[0].Request.Path != "/v1/casino/backoffice/{flagOffBrand}/reports/ggr?from={from}&to={to}" {
		t.Errorf("flag off: %+v", flagOff.Steps)
	}
	refused := step(t, r.recs["reports-refused-role"], "the revenue report as a support operator")
	if refused.Request.Headers["Authorization"] != "Bearer {supportToken}" || refused.Response.Status != 403 {
		t.Errorf("refused: %+v", refused)
	}
	for name, rec := range r.recs {
		for _, s := range rec.Steps {
			if s.Response.Headers == nil {
				t.Errorf("%s: step %q keeps no Content-Type", name, s.Name)
			}
		}
	}
}

// contractRows is every row of contracts/recording.md's table: its name and its driver-call cell.
func contractRows(t *testing.T) [][2]string {
	t.Helper()
	f, err := os.Open(filepath.Join("..", "..", "..", "specs", "015-reporting", "contracts", "recording.md"))
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	var out [][2]string
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		cells := strings.Split(strings.TrimSpace(sc.Text()), "|")
		if len(cells) < 4 || !strings.HasPrefix(strings.TrimSpace(cells[1]), "`") {
			continue
		}
		out = append(out, [2]string{strings.Trim(strings.TrimSpace(cells[1]), "`"), strings.TrimSpace(cells[2])})
	}
	if len(out) == 0 {
		t.Fatal("no rows in contracts/recording.md")
	}
	return out
}

var (
	backticked = regexp.MustCompile("`([^`]*)`")
	counted    = map[string]int{"one": 1, "two": 2, "three": 3, "four": 4, "five": 5, "six": 6, "seven": 7, "eight": 8, "nine": 9, "ten": 10}
)

// expand is a row's recordings: its name, or one per suffix of a `-*` row. The suffixes must be the
// values the driver-call cell lists (`?range=today`, `7d`), or as many as the numbers it names.
func expand(t *testing.T, name, call string) []string {
	t.Helper()
	if !strings.HasSuffix(name, "-*") {
		return []string{name}
	}
	prefix := strings.TrimSuffix(name, "*")
	suffixes, ok := Wildcards[prefix]
	if !ok {
		t.Errorf("%q: no suffixes in Wildcards", name)
		return nil
	}
	var listed []string
	for _, m := range backticked.FindAllStringSubmatch(call, -1) {
		v := m[1]
		if i := strings.LastIndex(v, "="); i >= 0 {
			v = v[i+1:]
		}
		listed = append(listed, v)
	}
	if len(listed) > 0 {
		if strings.Join(listed, ",") != strings.Join(suffixes, ",") {
			t.Errorf("%q: the contract lists %v, the driver %v", name, listed, suffixes)
		}
	} else {
		n := 0
		for _, w := range strings.Fields(call) {
			n += counted[strings.ToLower(w)]
		}
		if n != len(suffixes) {
			t.Errorf("%q: the contract names %d, the driver %d", name, n, len(suffixes))
		}
	}
	out := make([]string, 0, len(suffixes))
	for _, s := range suffixes {
		out = append(out, prefix+s)
	}
	return out
}

func TestEveryRecordingOfTheContractIsDrivenOrSaidWhyNot(t *testing.T) {
	driven := map[string]bool{}
	for _, d := range Scenarios() {
		if driven[d.Name] {
			t.Errorf("two recordings named %q", d.Name)
		}
		driven[d.Name] = true
		if !strings.HasPrefix(d.Requirement, "015/FR-") {
			t.Errorf("%q: requirement %q", d.Name, d.Requirement)
		}
		if FileName(d.Name) != d.Name {
			t.Errorf("%q is not its own file name", d.Name)
		}
	}
	known := map[string]bool{}
	for n := range Extra {
		known[n] = true
		if driven[n] {
			t.Errorf("%q is an extra and a row", n)
		}
	}
	total := 0
	for _, row := range contractRows(t) {
		for _, n := range expand(t, row[0], row[1]) {
			total++
			known[n] = true
			if !driven[n] && NotDriven[n] == "" {
				t.Errorf("%q is neither driven nor said why not", n)
			}
			if driven[n] && NotDriven[n] != "" {
				t.Errorf("%q is driven and said not to be", n)
			}
		}
	}
	if total != 31 {
		t.Errorf("the contract names %d recordings (T070 says 31)", total)
	}
	for n := range driven {
		if !known[n] {
			t.Errorf("the contract names no recording %q", n)
		}
	}
	for n := range NotDriven {
		if !known[n] {
			t.Errorf("NotDriven names %q, which the contract does not", n)
		}
	}
	for prefix := range Wildcards {
		if !known[prefix+Wildcards[prefix][0]] {
			t.Errorf("Wildcards names %q*, which the contract does not", prefix)
		}
	}
}

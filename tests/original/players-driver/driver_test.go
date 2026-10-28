package playersdriver

import (
	"bufio"
	"bytes"
	"encoding/base64"
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
	secret   = "s3cret"
	admin    = "admin-token-0000"
	operator = "operator-token-0000"
	outsider = "outsider-token-0000"
)

// stub is an original that registers, verifies a link signed under `secret`, signs in, refreshes,
// launches, keeps a second address's token and a password change's on the player twin, and lists a
// fraud case linking the last player it registered.
type stub struct {
	mu       sync.Mutex
	n        int
	players  map[string]string // email -> player id
	verified map[string]bool   // player id -> verified
	last     string            // the last player id registered
	second   string            // the second address added
	auths    []string          // every Authorization header received
}

func (o *stub) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	o.mu.Lock()
	defer o.mu.Unlock()
	o.auths = append(o.auths, r.Header.Get("Authorization"))
	raw, _ := io.ReadAll(r.Body)
	body := map[string]any{}
	_ = json.Unmarshal(raw, &body)
	str := func(k string) string { v, _ := body[k].(string); return v }
	p := r.URL.Path
	switch {
	case strings.HasSuffix(p, "/register"):
		switch {
		case str("email") == "" || str("password") == "" || str("currency") == "":
			w.WriteHeader(400)
			fmt.Fprint(w, `{"error":"email, password, and currency are required"}`)
		case str("password") == WeakPassword:
			w.WriteHeader(422)
			fmt.Fprint(w, `{"error":"password_too_weak"}`)
		case o.players[str("email")] != "":
			w.WriteHeader(409)
			fmt.Fprint(w, `{"error":"email_already_exists"}`)
		default:
			o.n++
			id := fmt.Sprintf("0000000%d-aaaa-4aaa-8aaa-00000000000%d", o.n, o.n)
			o.players[str("email")], o.last = id, id
			w.WriteHeader(201)
			fmt.Fprintf(w, `{"playerId":%q,"status":"pending_email_verification"}`, id)
		}
	case strings.HasSuffix(p, "/verify-email"):
		parts := strings.Split(str("token"), ".")
		var payload struct{ P, E string }
		if len(parts) == 2 {
			b, _ := base64.RawURLEncoding.DecodeString(parts[0])
			_ = json.Unmarshal(b, &payload)
		}
		if len(parts) != 2 || payload.P == "" {
			w.WriteHeader(400)
			return
		}
		o.verified[payload.P] = true
		fmt.Fprint(w, `{"status":"verified"}`)
	case strings.HasSuffix(p, "/login"):
		id := o.players[str("email")]
		if !o.verified[id] {
			w.WriteHeader(401)
			fmt.Fprint(w, `{"error":"email_not_verified"}`)
			return
		}
		o.n++
		fmt.Fprintf(w, `{"token":"jwt-%d-secretpart","refreshToken":"rt-%d-secretpart","playerId":%q,"currency":"EUR"}`, o.n, o.n, id)
	case strings.HasSuffix(p, "/refresh"):
		o.n++
		fmt.Fprintf(w, `{"accessToken":"jwt-%d-refreshed","refreshToken":"rt-%d-refreshed","expiresIn":300}`, o.n, o.n)
	case strings.HasSuffix(p, "/launch"):
		o.n++
		fmt.Fprintf(w, `{"sessionId":"gs-%d-0000"}`, o.n)
	case strings.HasSuffix(p, "/emails") && r.Method == http.MethodPost:
		o.second = str("address")
		w.WriteHeader(202)
	case strings.HasPrefix(p, "/twins/"):
		list, _ := json.Marshal([]map[string]any{{"address": o.second, "verificationToken": "second-link-0000"}})
		out, _ := json.Marshal(map[string]any{"properties": map[string]any{
			"player_email_list": string(list), "password_change_token": "change-token-0000",
		}})
		w.WriteHeader(map[bool]int{true: 201, false: 200}[r.Method == http.MethodPost])
		_, _ = w.Write(out)
	case strings.HasPrefix(p, "/admin/realms/"):
		fmt.Fprint(w, `[{"id":"kc-user-0000"}]`)
	case strings.HasSuffix(p, "/fraud-cases"):
		fmt.Fprintf(w, `{"cases":[{"caseId":"case-0000","playerRef":"casino.player/%s","linkedPlayerRef":""}]}`, o.last)
	case strings.HasSuffix(p, "/activity-export"):
		w.WriteHeader(202)
		fmt.Fprint(w, `{"exportId":"export-0000","status":"pending"}`)
	case strings.Contains(p, "/exports/"):
		fmt.Fprint(w, `{"exportId":"export-0000","status":"ready"}`)
	case strings.HasSuffix(p, "/notes"):
		w.WriteHeader(201)
		fmt.Fprint(w, `{"noteId":"note-0000"}`)
	default:
		fmt.Fprint(w, `{}`)
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
	o := &stub{players: map[string]string{}, verified: map[string]bool{}}
	srv := httptest.NewServer(o)
	defer srv.Close()
	dir := t.TempDir()
	cfg := Config{CasinoURL: srv.URL, KeycloakURL: srv.URL, SubstrateURL: srv.URL, Brand: "spinvibe", SigningSecret: secret,
		AdminToken: admin, OperatorToken: operator, OutsiderToken: outsider, RecordTo: dir, Commit: "abc1234"}
	run := &lenient{T: t}
	Scenario(run, cfg, def.Scenario(), def.Requirement, func(s *Session) { def.Run(run, s) })
	b, err := os.ReadFile(filepath.Join(dir, "players", FileName(def.Scenario())+".json"))
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

func TestTheVerificationLinkIsTheOriginalsSignedPayload(t *testing.T) {
	// Computed outside Go: base64url of the payload, and `openssl dgst -sha256 -hmac s3cret` of that.
	want := "eyJiIjoic3BpbnZpYmUiLCJwIjoicC0xIiwiZSI6ImFAZXhhbXBsZS50ZXN0IiwieCI6MTcwMDAwMDAwMDAwMCwidCI6ImVtYWlsX3ZlcmlmeSJ9" +
		".N6l66h-TkE1GyIJl8rYcauwEy02ZhtxHPXITlV3hLEE"
	if got := VerificationToken(secret, "spinvibe", "p-1", "a@example.test", time.UnixMilli(1700000000000)); got != want {
		t.Fatalf("got %s", got)
	}
}

// Every scenario runs against the stub, and none writes a token, a password or the secret as it is.
func TestEveryScenarioRecordsNoCredentialAndNamesEveryTokenItCarries(t *testing.T) {
	for _, d := range Scenarios() {
		d := d
		t.Run(d.Scenario(), func(t *testing.T) {
			rec, _, o := record(t, d)
			if rec.Service != "players" || rec.Requirement != d.Requirement || rec.Original["commit"] != "abc1234" || len(rec.Steps) == 0 {
				t.Fatalf("header: %+v", rec)
			}
			for _, s := range rec.Steps {
				sent := s.Request.Path + string(s.Request.Body) + fmt.Sprint(s.Request.Headers)
				for _, raw := range []string{secret, admin, operator, outsider, "secretpart", "refreshed", Password + "x"} {
					if strings.Contains(sent, raw) {
						t.Errorf("step %q carries %q: %s", s.Name, raw, sent)
					}
				}
				if a, ok := s.Request.Headers["Authorization"]; ok && !(strings.HasPrefix(a, "Bearer {") && strings.HasSuffix(a, "}")) {
					t.Errorf("step %q: Authorization %q", s.Name, a)
				}
				if strings.Contains(sent, "@example.test") {
					t.Errorf("step %q carries an address as it is: %s", s.Name, sent)
				}
			}
			for _, a := range o.auths {
				if a == "Bearer " || a == "Bearer {token}" {
					t.Errorf("a request carried %q", a)
				}
			}
		})
	}
}

func TestASignInCapturesTheTokenAndLaterStepsCarryItsName(t *testing.T) {
	rec, text, _ := record(t, find(t, "a game session opened at launch for an active verified player is recorded under the player session"))
	if !strings.Contains(text, `"token": "$.token"`) || !strings.Contains(text, `"playerId": "$.playerId"`) || !strings.Contains(text, `"sessionId": "$.sessionId"`) {
		t.Error("the sign-in, the registration or the launch does not capture its value")
	}
	launch := step(t, rec, "launch a game")
	if launch.Request.Headers["Authorization"] != "Bearer {token}" || !strings.Contains(compact(launch.Request.Body), `"playerId":"{playerId}"`) ||
		launch.Request.Path != "/v1/casino/sessions/spinvibe/launch" {
		t.Errorf("launch: %+v %s", launch.Request, launch.Request.Body)
	}
	if read := step(t, rec, "read the game session"); read.Request.Path != "/v1/casino/sessions/spinvibe/{sessionId}" {
		t.Errorf("read: %s", read.Request.Path)
	}
}

func TestTheVerificationLinkIsRecordedByNameAndItsAddressTemplated(t *testing.T) {
	rec, _, _ := record(t, find(t, "following the verification link within a day verifies the address and makes the player active, and following it again changes nothing"))
	for _, name := range []string{"follow the verification link", "follow the verification link again"} {
		if b := compact(step(t, rec, name).Request.Body); b != `{"token":"{link}"}` {
			t.Errorf("%s: %s", name, b)
		}
	}
	if b := compact(step(t, rec, "setup: register").Request.Body); !strings.Contains(b, `"email":"{email}"`) {
		t.Errorf("register: %s", b)
	}
}

func TestAnOperatorStepCarriesTheOperatorByNameAndARealmReadTheAdmin(t *testing.T) {
	rec, _, _ := record(t, find(t, "a closure ends everything, disables the realm user and is produced with actor and reason"))
	if h := step(t, rec, "close the account").Request.Headers["Authorization"]; h != "Bearer {operatorToken}" {
		t.Errorf("close: %q", h)
	}
	find := step(t, rec, "setup: find the player in the realm")
	if find.Request.Headers["Authorization"] != "Bearer {adminToken}" || find.Request.Service != "keycloak" ||
		find.Request.Path != "/admin/realms/casino-spinvibe/users?exact=true&email={email}" {
		t.Errorf("find: %+v", find.Request)
	}
}

func TestATwinTokenIsReadAsSetupAndSentByName(t *testing.T) {
	rec, _, _ := record(t, find(t, "a second email address added and verified is verified and not the sign-in address"))
	if s := step(t, rec, "setup: read the second address's verification token"); s.Request.Service != "substrate" || s.Request.Path != "/twins/casino-spinvibe/{playerId}" {
		t.Errorf("read: %+v", s.Request)
	}
	if b := compact(step(t, rec, "follow the second address's link").Request.Body); !strings.Contains(b, `"token":"{secondLink}"`) {
		t.Errorf("verify: %s", b)
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

// featureScenarios is every scenario of features/players/*.feature, an outline's once per example by
// its first column.
func featureScenarios(t *testing.T) []string {
	files, err := filepath.Glob(filepath.Join("..", "..", "..", "features", "players", "*.feature"))
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
		if !strings.HasPrefix(d.Requirement, "008/FR-") {
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
}

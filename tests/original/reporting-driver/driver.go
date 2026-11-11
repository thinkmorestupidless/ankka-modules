// Package reportingdriver drives the original's casino-service at its reporting routes (the six
// reports under `/reports`, the dashboard's eight reads under `/dashboard` and the dispute report),
// with its Keycloak, fake-st8, wallet-service's internal API and its Unleash for setup, and records each
// recording of specs/015-reporting/contracts/recording.md as one file (research R18, T070;
// tests/original/README.md, *Recording the reports*).
//
// No recording of these routes exists: the original's Go suite never calls `/reports`, `/dashboard` or
// the dispute report. The driver first seeds, through the original's own routes, a player with a
// login session, two game sessions and five rounds, a deposit, a bonus grant and a self-exclusion, so
// no answer is empty (the `reporting-seed` recording, every step of it `setup:`), then calls every
// route as the backoffice does. The original's figures are its twenty-player sample's and its days
// UTC, so a parity test (T071) compares a recording's shape, not its figures.
//
// Every token is the run's own and is written as its `{name}`: the player's sign-in, the operator's and
// the support operator's. The wallet's internal signature, Unleash's admin token, the signing secret and
// the passwords are sent and never written.
//
// The machinery in this file needs no running original, so `go test ./original/reporting-driver/...`
// checks it against a stub; the recordings themselves are tagged `e2e`.
package reportingdriver

import (
	"bytes"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"testing"
	"time"

	"github.com/eitheror-io/ankka-casino/tests/original/recorder"
)

// Config is where the original runs, who the run acts as, and where recordings go.
type Config struct {
	CasinoURL         string // the original's casino-service, e.g. http://localhost:8091
	KeycloakURL       string // the original's Keycloak, e.g. http://localhost:8180
	FakeSt8URL        string // the original's fake-st8, the game provider casino-service is pointed at
	WalletInternalURL string // wallet-service's internal API, where the seed's deposit is made
	// InternalSecret is the original's WALLET_SERVICE_INTERNAL_SECRET: the deposit is signed with its
	// HMAC of the brand. Never written.
	InternalSecret string
	// UnleashURL is the original's Unleash, where the brand's Reports flag is set; its admin token is
	// UnleashToken, never written. With no token the flag is taken as it is.
	UnleashURL         string
	UnleashToken       string
	UnleashProject     string // the project the flag lives in; "default" when unset
	UnleashEnvironment string // the environment casino-service's token reads; "development" when unset
	Brand              string // the original's seeded brand, whose realm is casino-<brand>
	// FlagOffBrand is a brand the Reports flag is off for: any brand the flag's strategy does not name.
	// The original reads the flag before anything else, so it need not exist.
	FlagOffBrand string
	// SigningSecret is the original's EMAIL_TOKEN_SIGNING_SECRET, from which a verification link is
	// minted as the original mints it (EmailVerificationTokenStore). Never written.
	SigningSecret string
	OperatorToken string // an operator with platform_admin, resource_admin or brand_admin at the brand; {operatorToken}
	SupportToken  string // an operator of the brand with none of those roles (support_agent); {supportToken}
	RecordTo      string // EITHEROR_RECORD_TO; empty runs without writing
	Commit        string // EITHEROR_ORIGINAL_COMMIT
	Client        *http.Client
	Now           func() time.Time
	// Patience is how long an `observe:` step waits for the original's reads to catch up; 40 seconds
	// when unset. casino-service's Unleash client refreshes its flags every 15 seconds or so, inside it.
	Patience time.Duration
}

// Response is what the original answered to one step: an object in Body, an array in List.
type Response struct {
	Status int
	Header http.Header
	Body   map[string]any
	List   []any
	Raw    []byte
}

// Str reads a JSON field as a string, whatever its JSON type, or "" when absent.
func (r Response) Str(field string) string { return str(r.Body, field) }

// Items is the array under field, or nil.
func (r Response) Items(field string) []map[string]any {
	arr, _ := r.Body[field].([]any)
	out := make([]map[string]any, 0, len(arr))
	for _, e := range arr {
		if m, ok := e.(map[string]any); ok {
			out = append(out, m)
		}
	}
	return out
}

func str(m map[string]any, field string) string {
	v, ok := m[field]
	if !ok || v == nil {
		return ""
	}
	switch x := v.(type) {
	case string:
		return x
	case float64:
		return fmt.Sprintf("%v", x)
	default:
		b, _ := json.Marshal(x)
		return string(b)
	}
}

// Session is one recording: its steps and the run's values it names.
type Session struct {
	t        testing.TB
	cfg      Config
	rec      *recorder.Recording
	ids      map[string]string // name -> the value minted or captured for it this run
	order    []string
	cleanups []func()
	skipped  bool
}

// Scenario runs body, then its clean-ups, and writes one recording named exactly `name`. The seed's
// values are named in it, so every request that carries one carries its `{name}`.
func Scenario(t testing.TB, cfg Config, seed Seed, name, requirement string, body func(s *Session)) {
	t.Helper()
	s := newSession(t, cfg, name, requirement)
	seed.name(s)
	defer s.write()
	defer s.runCleanups()
	body(s)
}

func newSession(t testing.TB, cfg Config, name, requirement string) *Session {
	if cfg.Client == nil {
		cfg.Client = &http.Client{Timeout: 60 * time.Second}
	}
	if cfg.Now == nil {
		cfg.Now = time.Now
	}
	if cfg.Patience == 0 {
		cfg.Patience = 40 * time.Second
	}
	if cfg.UnleashProject == "" {
		cfg.UnleashProject = "default"
	}
	if cfg.UnleashEnvironment == "" {
		cfg.UnleashEnvironment = "development"
	}
	s := &Session{
		t:   t,
		cfg: cfg,
		ids: map[string]string{},
		rec: &recorder.Recording{
			Scenario:    name,
			Requirement: requirement,
			RecordedAt:  time.Now().UTC(),
			Original:    map[string]string{"commit": cfg.Commit},
			Runs:        1,
			Service:     "reporting",
		},
	}
	// The run's own credentials, so no request or answer carries one as it is.
	s.remember("operatorToken", cfg.OperatorToken)
	s.remember("supportToken", cfg.SupportToken)
	// The brand the flag is off for: a replay names its own there.
	s.remember("flagOffBrand", cfg.FlagOffBrand)
	// The period every report is asked for: the thirty days to today, UTC, as the backoffice sends them.
	today := cfg.Now().UTC()
	s.remember("from", today.AddDate(0, 0, -29).Format(time.DateOnly))
	s.remember("to", today.Format(time.DateOnly))
	return s
}

func (s *Session) runCleanups() {
	for i := len(s.cleanups) - 1; i >= 0; i-- {
		s.cleanups[i]()
	}
}

// Skip ends a recording the run cannot make, before its first step, and writes nothing.
func (s *Session) Skip(reason string) {
	s.skipped = true
	s.t.Skip(reason)
}

// Brand is the brand every path names; recorded as it is, since a replay names its own brand there.
func (s *Session) Brand() string { return s.cfg.Brand }

// UUID mints a fresh UUID under name.
func (s *Session) UUID(name string) string {
	if v, ok := s.ids[name]; ok {
		return v
	}
	s.remember(name, newUUID())
	return s.ids[name]
}

// Email mints the run's address under name: `reporting-<name>-<nonce>@example.test`, lower case.
func (s *Session) Email(name string) string {
	if v, ok := s.ids[name]; ok {
		return v
	}
	s.remember(name, strings.ToLower(fmt.Sprintf("reporting-%s-%s@example.test", name, newUUID()[:8])))
	return s.ids[name]
}

// Value is a value minted or captured under name, or "" when there is none.
func (s *Session) Value(name string) string { return s.ids[name] }

// Grab names a value the original generated in the last response, for later steps' `{name}`.
func (s *Session) Grab(name, jsonPath, value string) {
	if value == "" {
		s.t.Errorf("%s: capture %s: %s was empty in the last response", s.rec.Scenario, name, jsonPath)
		return
	}
	last := &s.rec.Steps[len(s.rec.Steps)-1]
	if last.Capture == nil {
		last.Capture = map[string]string{}
	}
	last.Capture[name] = jsonPath
	s.remember(name, value)
}

// Remember names a value this run made or read, so every later request carries `{name}` in its place.
func (s *Session) Remember(name, value string) { s.remember(name, value) }

func (s *Session) remember(name, value string) {
	if _, ok := s.ids[name]; !ok {
		s.order = append(s.order, name)
	}
	s.ids[name] = value
}

// Cleanup runs f after the recording's body, before it is written; clean-ups run last first.
func (s *Session) Cleanup(f func()) { s.cleanups = append(s.cleanups, f) }

// Want records a mismatch between the original's status and the one the contract expects as a failure
// of the recording: the recording is still written, and the run's result shows it.
func (s *Session) Want(r Response, status int) Response {
	if r.Status != status {
		s.t.Errorf("%s: the original answered %d (want %d): %s", s.rec.Scenario, r.Status, status, clip(r.Raw))
	}
	return r
}

// WantField records a mismatch between a field of the original's answer and the one expected.
func (s *Session) WantField(r Response, field, value string) Response {
	if got := r.Str(field); got != value {
		s.t.Errorf("%s: the original answered %s %q (want %q): %s", s.rec.Scenario, field, got, value, clip(r.Raw))
	}
	return r
}

// ok checks a setup write the original answers 2xx.
func (s *Session) ok(r Response) Response {
	if r.Status < 200 || r.Status > 299 {
		s.t.Errorf("%s: a setup step was answered %d: %s", s.rec.Scenario, r.Status, clip(r.Raw))
	}
	return r
}

func clip(b []byte) string {
	if len(b) > 400 {
		return string(b[:400]) + "…"
	}
	return string(b)
}

// Auth names the token a step carries: the run value remembered under that name, sent as a bearer
// token and recorded as `Bearer {name}`. Empty sends none.
type Auth string

const (
	NoAuth   Auth = ""
	Player   Auth = "token"         // the seed's player's sign-in
	Operator Auth = "operatorToken" // an operator who may read the reports
	Support  Auth = "supportToken"  // an operator of the brand who may not
)

// unrecorded are the headers a step sends and its recording never holds: the wallet's internal
// signature and Unleash's admin token.
var unrecorded = map[string]bool{"X-Internal-Secret": true, "Authorization": true}

// kept are the response headers a compared step's recording holds: a report's shape is in them.
var kept = []string{"Content-Type", "Content-Disposition"}

// Casino calls casino-service: a step a replay sends to the port and compares.
func (s *Session) Casino(name, path string, auth Auth) Response {
	return s.call(name, "casino", http.MethodGet, path, nil, auth, nil)
}

// Setup calls casino-service, fake-st8, the wallet or Unleash for what the backoffice does not do;
// recorded as a `setup:` step a replay skips.
func (s *Session) Setup(name, service, method, path string, body any, auth Auth) Response {
	return s.call("setup: "+name, service, method, path, body, auth, nil)
}

// WalletSetup calls wallet-service's internal routes as casino-service and merchant-service do,
// signed `X-Internal-Brand-Id` and `X-Internal-Secret: hex(HMAC-SHA256(secret, brand))`; recorded as
// setup, without the signature.
func (s *Session) WalletSetup(name, method, path string, body any) Response {
	return s.call("setup: "+name, "wallet", method, path, body, NoAuth, map[string]string{
		"X-Internal-Brand-Id": s.Brand(),
		"X-Internal-Secret":   Signature(s.cfg.InternalSecret, s.Brand()),
	})
}

// Unleash calls Unleash's admin API with the run's admin token, as `setup:`; the token is never written.
func (s *Session) Unleash(name, method, path string, body any) Response {
	return s.call("setup: "+name, "unleash", method, path, body, NoAuth, map[string]string{"Authorization": s.cfg.UnleashToken})
}

// Observe repeats a call until `until` holds of its answer, within the run's patience, and records
// only the answer that held, as an `observe:` step a replay sends without comparing. An answer that
// never held is recorded as the last one read, and fails the recording.
func (s *Session) Observe(name, service, path string, auth Auth, until func(Response) bool) Response {
	s.t.Helper()
	deadline := time.Now().Add(s.cfg.Patience)
	for {
		r := s.call("observe: "+name, service, http.MethodGet, path, nil, auth, nil)
		if until(r) {
			return r
		}
		if time.Now().After(deadline) {
			s.t.Errorf("%s: %s not observed within %s: %s", s.rec.Scenario, name, s.cfg.Patience, clip(r.Raw))
			return r
		}
		s.rec.Steps = s.rec.Steps[:len(s.rec.Steps)-1]
		time.Sleep(250 * time.Millisecond)
	}
}

// Eventually makes a compared call again, for up to the run's patience, until `until` holds, and keeps
// only its last answer: casino-service reads the Reports flag through its Unleash client, which follows
// a change by seconds. A call that never settles is kept as answered, for the recording's own check.
func (s *Session) Eventually(read func() Response, until func(Response) bool) Response {
	s.t.Helper()
	deadline := time.Now().Add(s.cfg.Patience)
	for {
		r := read()
		if until(r) || time.Now().After(deadline) {
			return r
		}
		s.rec.Steps = s.rec.Steps[:len(s.rec.Steps)-1]
		time.Sleep(250 * time.Millisecond)
	}
}

func (s *Session) base(service string) string {
	switch service {
	case "keycloak":
		return s.cfg.KeycloakURL
	case "fake-st8":
		return s.cfg.FakeSt8URL
	case "wallet":
		return s.cfg.WalletInternalURL
	case "unleash":
		return s.cfg.UnleashURL
	default:
		return s.cfg.CasinoURL
	}
}

func (s *Session) call(name, service, method, path string, body any, auth Auth, extra map[string]string) Response {
	s.t.Helper()
	u, err := url.Parse(strings.TrimSuffix(s.base(service), "/") + path)
	if err != nil {
		s.t.Fatalf("%s: %v", name, err)
	}
	if !recorder.Local(u.Host) {
		s.t.Fatalf("refusing %s: nothing may leave the machine while the original is recorded", u.Host)
	}
	var payload []byte
	if body != nil {
		payload, _ = json.Marshal(body)
	}
	req, _ := http.NewRequest(method, u.String(), bytes.NewReader(payload))
	headers := map[string]string{}
	if payload != nil {
		req.Header.Set("Content-Type", "application/json")
		headers["Content-Type"] = "application/json"
	}
	if auth != NoAuth {
		token := s.ids[string(auth)]
		if token == "" {
			s.t.Fatalf("%s: no %s for this run", name, auth)
		}
		req.Header.Set("Authorization", "Bearer "+token)
		headers["Authorization"] = "Bearer {" + string(auth) + "}"
	}
	for k, v := range extra {
		req.Header.Set(k, v)
		if !unrecorded[k] {
			headers[k] = s.template(v)
		}
	}
	resp, err := s.cfg.Client.Do(req)
	if err != nil {
		s.t.Fatalf("%s: %v", name, err)
	}
	raw, _ := io.ReadAll(resp.Body)
	_ = resp.Body.Close()
	out := Response{Status: resp.StatusCode, Header: resp.Header, Raw: raw, Body: map[string]any{}}
	if err := json.Unmarshal(raw, &out.Body); err != nil {
		out.Body = map[string]any{}
		_ = json.Unmarshal(raw, &out.List)
	}

	step := recorder.Step{
		Name: name,
		Request: recorder.Request{
			Method:  method,
			Path:    s.template(u.RequestURI()),
			Service: service,
			Headers: headers,
		},
		Response: recorder.Response{Status: resp.StatusCode, Body: rawJSON(raw)},
	}
	if len(headers) == 0 {
		step.Request.Headers = nil
	}
	if service == "casino" && !strings.HasPrefix(name, "setup: ") && !strings.HasPrefix(name, "observe: ") {
		for _, h := range kept {
			if v := resp.Header.Get(h); v != "" {
				if step.Response.Headers == nil {
					step.Response.Headers = map[string]string{}
				}
				step.Response.Headers[h] = v
			}
		}
	}
	if payload != nil {
		step.Request.Body = json.RawMessage(s.template(string(payload)))
	}
	s.rec.Steps = append(s.rec.Steps, step)
	return out
}

// template replaces this run's minted and captured values with their `{name}` placeholders, longest
// first; a value also appears URL-escaped in a path or a query, and is replaced there too.
func (s *Session) template(text string) string {
	names := append([]string(nil), s.order...)
	sort.SliceStable(names, func(i, j int) bool { return len(s.ids[names[i]]) > len(s.ids[names[j]]) })
	for _, n := range names {
		v := s.ids[n]
		if v == "" {
			continue
		}
		text = strings.ReplaceAll(text, v, "{"+n+"}")
		if e := url.QueryEscape(v); e != v {
			text = strings.ReplaceAll(text, e, "{"+n+"}")
		}
		if e := url.PathEscape(v); e != v {
			text = strings.ReplaceAll(text, e, "{"+n+"}")
		}
	}
	return text
}

func (s *Session) write() {
	if s.cfg.RecordTo == "" || s.skipped {
		return
	}
	path := filepath.Join(s.cfg.RecordTo, s.rec.Service, FileName(s.rec.Scenario)+".json")
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		s.t.Errorf("recording %s: %v", s.rec.Scenario, err)
		return
	}
	b, _ := json.MarshalIndent(s.rec, "", "  ")
	if err := os.WriteFile(path, append(b, '\n'), 0o644); err != nil {
		s.t.Errorf("recording %s: %v", s.rec.Scenario, err)
	}
}

// FileName is a recording's file name: its words, with anything a path cannot hold made a dash.
func FileName(scenario string) string {
	return strings.Trim(pathUnsafe.ReplaceAllString(scenario, "-"), "-")
}

var pathUnsafe = regexp.MustCompile(`[^A-Za-z0-9_.-]+`)

// VerificationToken is the link's token as the original's EmailVerificationTokenStore.generateToken
// makes it: the base64url (unpadded) of `{"b","p","e","x","t":"email_verify"}`, a dot, and the
// base64url HMAC-SHA256 of that under the signing secret (as players-driver mints it).
func VerificationToken(secret, brand, playerID, email string, expires time.Time) string {
	payload, _ := json.Marshal(struct {
		B string `json:"b"`
		P string `json:"p"`
		E string `json:"e"`
		X int64  `json:"x"`
		T string `json:"t"`
	}{brand, playerID, email, expires.UnixMilli(), "email_verify"})
	enc := base64.RawURLEncoding.EncodeToString(payload)
	m := hmac.New(sha256.New, []byte(secret))
	m.Write([]byte(enc))
	return enc + "." + base64.RawURLEncoding.EncodeToString(m.Sum(nil))
}

// Signature is wallet-service's internal signature of a brand: hex HMAC-SHA256 under the internal
// secret (as payouts-driver signs it).
func Signature(secret, brand string) string {
	m := hmac.New(sha256.New, []byte(secret))
	m.Write([]byte(brand))
	return hex.EncodeToString(m.Sum(nil))
}

// PasswordToken fetches a token from a realm by the password grant, outside any recording: it is how
// the run obtains the operators' sign-ins, which the steps then carry as `{name}`.
func PasswordToken(client *http.Client, keycloak, realm, clientID, user, password string) (string, error) {
	u, err := url.Parse(strings.TrimSuffix(keycloak, "/") + "/realms/" + realm + "/protocol/openid-connect/token")
	if err != nil {
		return "", err
	}
	if !recorder.Local(u.Host) {
		return "", fmt.Errorf("refusing %s: nothing may leave the machine while the original is recorded", u.Host)
	}
	form := url.Values{"grant_type": {"password"}, "client_id": {clientID}, "username": {user}, "password": {password}}
	resp, err := client.PostForm(u.String(), form)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	var out struct {
		AccessToken string `json:"access_token"`
	}
	raw, _ := io.ReadAll(resp.Body)
	if resp.StatusCode != http.StatusOK || json.Unmarshal(raw, &out) != nil || out.AccessToken == "" {
		return "", fmt.Errorf("realm %s answered %d for %s", realm, resp.StatusCode, user)
	}
	return out.AccessToken, nil
}

func newUUID() string {
	var b [16]byte
	_, _ = rand.Read(b[:])
	b[6] = (b[6] & 0x0f) | 0x40
	b[8] = (b[8] & 0x3f) | 0x80
	h := hex.EncodeToString(b[:])
	return h[0:8] + "-" + h[8:12] + "-" + h[12:16] + "-" + h[16:20] + "-" + h[20:32]
}

func rawJSON(b []byte) json.RawMessage {
	b = bytes.TrimSpace(b)
	if len(b) == 0 {
		return nil
	}
	if json.Valid(b) {
		return json.RawMessage(b)
	}
	q, _ := json.Marshal(string(b))
	return q
}

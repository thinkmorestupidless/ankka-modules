// Package publicapidriver drives the original's casino partner REST API (`casino/public-api`, spec 255
// of the original) with its casino-service, Keycloak, fake-st8 and wallet-service's internal API for
// setup, and records each recording of specs/016-public-api/contracts/recording.md as one file
// (research R14, T090; tests/original/README.md).
//
// The original's suite reaches the partner API only through scenario 252, which walks whatever
// activity the cluster happens to hold. The driver first seeds activity with known figures through the
// original's own routes (the `public-api-seed` recording, every step `setup:` or `observe:`), then
// makes the eleven recordings on it. doc.go lists what is seeded through which route, and the two steps
// the original offers no route for (the partner grants and two of the four partner clients).
//
// Ids are noted by role. Every record the seed makes or finds is named (`player-1-of-A`,
// `session-2-of-A`, `round-3-of-session-2-of-A`, `bet-1-of-round-3-of-session-2-of-A`,
// `transaction-7-of-player-2-of-A`, `award-1-of-player-1-of-A`, `snapshot-1-of-A`, `game-1`…), every
// request that carries one carries its `{role}`, and each recording file holds the run's `roles`: the
// role and the original's id it stood for, so a parity test can map the original's ids in an answer to
// the port's ids through the same seeding.
//
// Every token is the run's own and is written as its `{name}`: the four partner clients' tokens
// (`fullToken`, `bareToken`, `otherToken`, `quotaToken`), the operator's and the players'. The wallet's
// internal signature, the internal secret, Keycloak's admin password, the client secrets and the
// passwords are sent and never written.
//
// The machinery in this file needs no running original, so `go test ./original/public-api-driver/...`
// checks it against a stub; the recordings themselves are tagged `e2e`.
package publicapidriver

import (
	"bytes"
	"crypto/hmac"
	"crypto/md5" //nolint:gosec // java.util.UUID.nameUUIDFromBytes is MD5-based by definition
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

// Service is the rebuilt service every recording establishes requirements for, and the directory under
// EITHEROR_RECORD_TO each recording is written to.
const Service = "public-api"

// Config is where the original runs, who the run acts as, and where recordings go.
type Config struct {
	APIURL            string // the original's casino-public-api, e.g. http://localhost:8097 (NodePort 30097)
	CasinoURL         string // the original's casino-service, e.g. http://localhost:8091
	KeycloakURL       string // the original's Keycloak, e.g. http://localhost:8180
	FakeSt8URL        string // the original's fake-st8, the game provider casino-service is pointed at
	WalletInternalURL string // wallet-service's internal API, where the seed's deposits are made
	// InternalSecret is the original's WALLET_SERVICE_INTERNAL_SECRET: deposits are signed with its HMAC
	// of the brand, and casino-service's stats recompute routes take it as `X-Internal-Secret`. Never
	// written.
	InternalSecret string
	// SigningSecret is the original's EMAIL_TOKEN_SIGNING_SECRET, from which a verification link is
	// minted as the original mints it. Never written.
	SigningSecret string
	BrandA        string // the brand whose records are walked: spinvibe
	BrandB        string // the other brand: goldvibe
	// Token fetches a fresh token for a partner client or the operator by its Auth name; tokens live
	// five minutes, so a session asks again after four.
	Token    func(name Auth) (string, error)
	RecordTo string // EITHEROR_RECORD_TO; empty runs without writing
	Commit   string // EITHEROR_ORIGINAL_COMMIT
	Client   *http.Client
	Now      func() time.Time
	Sleep    func(time.Duration)
	// Patience is how long an `observe:` step waits for the original's reads to catch up; 60 seconds
	// when unset. GrantPatience is how long the run waits for the grants file to be loaded (the
	// ConfigMap's sync and the original's 60-second re-read); three minutes when unset.
	Patience      time.Duration
	GrantPatience time.Duration
	// StatsWait makes the seed wait for the next full hour before the statistics runs: the original
	// buckets by whole hours and counts nothing after the hour it runs in (GameStatsRecompute.run,
	// PlayerStatsRecompute.runOnce truncate `now` to the hour), so activity seeded this hour is in no
	// figure until the next one.
	StatsWait bool
	// RetentionWait is how long a player-statistics run that the original skips for retention (a slot
	// replaced less than 30 minutes ago, PlayerStatsPointer.RetentionMinutes) may wait for
	// `retainedUntil`; zero fails the recording instead.
	RetentionWait time.Duration
}

// Auth names the token a step carries: a partner client's or the operator's, fetched through
// Config.Token, or a value the session remembered under that name (a player's sign-in). It is sent as a
// bearer token and recorded as `Bearer {name}`. Empty sends none.
type Auth string

const (
	NoAuth   Auth = ""
	Full     Auth = "fullToken"     // brand A's partner, every identifying field and an agreement
	Bare     Auth = "bareToken"     // brand A's partner with no identifying field and no agreement
	Other    Auth = "otherToken"    // brand B's partner, with none
	Quota    Auth = "quotaToken"    // brand A's partner at 5 requests a minute
	Operator Auth = "operatorToken" // an operator holding brand_admin or platform_admin at both brands
)

// fetched are the tokens Config.Token supplies; any other Auth is a remembered value.
var fetched = map[Auth]bool{Full: true, Bare: true, Other: true, Quota: true, Operator: true}

// tokenLife is how long a fetched token is used before it is asked for again: Keycloak's partner clients
// issue five-minute tokens (`access.token.lifespan: 300`).
const tokenLife = 4 * time.Minute

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
func (r Response) Items(field string) []map[string]any { return items(r.Body, field) }

// Data is a page's records: the `data` array of the original's `{data, page}` envelope.
func (r Response) Data() []map[string]any { return r.Items("data") }

// Total is a page's `page.total`, or -1 when the answer is not a page.
func (r Response) Total() int {
	page, ok := r.Body["page"].(map[string]any)
	if !ok {
		return -1
	}
	n, ok := page["total"].(float64)
	if !ok {
		return -1
	}
	return int(n)
}

// Snapshot is a ranking's `snapshot.id`, or "".
func (r Response) Snapshot() string {
	snap, _ := r.Body["snapshot"].(map[string]any)
	return str(snap, "id")
}

func items(m map[string]any, field string) []map[string]any {
	arr, _ := m[field].([]any)
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

// File is one recording as written: the recorder's format, and the run's roles. `roles` maps each role
// the seed named to the original's id it stood for this run; the parity tests map ids through it.
type File struct {
	recorder.Recording
	Roles map[string]string `json:"roles,omitempty"`
}

// Session is one recording: its steps, the run's values it names and the roles among them.
type Session struct {
	t        testing.TB
	cfg      Config
	rec      *recorder.Recording
	ids      map[string]string // name -> the value minted or captured for it this run
	order    []string
	roles    map[string]string // role -> the original's id
	tokens   map[Auth]time.Time
	cleanups []func()
	skipped  bool
	// quotaFree is when the quota client's last window has closed.
	quotaFree time.Time
}

// Scenario runs body, then its clean-ups, and writes one recording named exactly `name`. The seed's
// roles are named in it, so every request that carries one carries its `{role}`.
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
		cfg.Client = &http.Client{Timeout: 6 * time.Minute}
	}
	if cfg.Now == nil {
		cfg.Now = time.Now
	}
	if cfg.Sleep == nil {
		cfg.Sleep = time.Sleep
	}
	if cfg.Patience == 0 {
		cfg.Patience = 60 * time.Second
	}
	if cfg.GrantPatience == 0 {
		cfg.GrantPatience = 3 * time.Minute
	}
	if cfg.BrandA == "" {
		cfg.BrandA = BrandA
	}
	if cfg.BrandB == "" {
		cfg.BrandB = BrandB
	}
	return &Session{
		t:      t,
		cfg:    cfg,
		ids:    map[string]string{},
		roles:  map[string]string{},
		tokens: map[Auth]time.Time{},
		rec: &recorder.Recording{
			Scenario:    name,
			Requirement: requirement,
			RecordedAt:  time.Now().UTC(),
			Original:    map[string]string{"commit": cfg.Commit, "brand": cfg.BrandA, "otherBrand": cfg.BrandB},
			Runs:        1,
			Service:     Service,
		},
	}
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

// Brand is brand A or brand B by its letter.
func (s *Session) Brand(letter string) string {
	if letter == "B" {
		return s.cfg.BrandB
	}
	return s.cfg.BrandA
}

// UUID mints a fresh UUID under name.
func (s *Session) UUID(name string) string {
	if v, ok := s.ids[name]; ok {
		return v
	}
	s.remember(name, newUUID())
	return s.ids[name]
}

// Email mints the run's address under name: `public-api-<name>-<nonce>@example.test`, lower case.
func (s *Session) Email(name string) string {
	if v, ok := s.ids[name]; ok {
		return v
	}
	s.remember(name, strings.ToLower(fmt.Sprintf("public-api-%s-%s@example.test", name, newUUID()[:8])))
	return s.ids[name]
}

// Value is a value minted, captured or named under name, or "" when there is none.
func (s *Session) Value(name string) string { return s.ids[name] }

// Role names a record of the seed: later requests carry `{role}` in its place, and the recording's
// `roles` holds it.
func (s *Session) Role(role, id string) {
	if id == "" {
		s.t.Errorf("%s: role %s: the original gave no id", s.rec.Scenario, role)
		return
	}
	s.remember(role, id)
	s.roles[role] = id
}

// Roles is every role named so far.
func (s *Session) Roles() map[string]string {
	out := make(map[string]string, len(s.roles))
	for k, v := range s.roles {
		out[k] = v
	}
	return out
}

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

// unrecorded are the headers a step sends and its recording never holds.
var unrecorded = map[string]bool{"X-Internal-Secret": true, "Authorization": true}

// kept are the response headers a compared step's recording holds: the refusal's Retry-After, and the
// type that tells the reference page from a JSON answer.
var kept = []string{"Content-Type", "Retry-After"}

// API calls the partner API with GET: a step a replay sends to the port and compares.
func (s *Session) API(name, path string, auth Auth) Response {
	return s.call(name, Service, http.MethodGet, path, nil, auth, nil)
}

// APIMethod calls the partner API with any method, as a compared step.
func (s *Session) APIMethod(name, method, path string, body any, auth Auth) Response {
	return s.call(name, Service, method, path, body, auth, nil)
}

// APIRaw calls the partner API with an Authorization header sent and recorded exactly as given (a
// malformed token), as a compared step.
func (s *Session) APIRaw(name, path, authorization string) Response {
	return s.call(name, Service, http.MethodGet, path, nil, NoAuth, map[string]string{"Authorization": authorization}, true)
}

// Setup calls casino-service, fake-st8 or the partner API for what a partner does not do; recorded as
// a `setup:` step a replay skips.
func (s *Session) Setup(name, service, method, path string, body any, auth Auth) Response {
	return s.call("setup: "+name, service, method, path, body, auth, nil)
}

// WalletSetup calls wallet-service's internal routes as casino-service and merchant-service do, signed
// `X-Internal-Brand-Id` and `X-Internal-Secret: hex(HMAC-SHA256(secret, brand))`; recorded as setup,
// without the signature.
func (s *Session) WalletSetup(name, brand, method, path string, body any) Response {
	return s.call("setup: "+name, "wallet", method, path, body, NoAuth, map[string]string{
		"X-Internal-Brand-Id": brand,
		"X-Internal-Secret":   Signature(s.cfg.InternalSecret, brand),
	})
}

// InternalSetup calls one of casino-service's internal routes, which take the internal secret itself
// as `X-Internal-Secret` (GameStatsRecomputeRoutes, PlayerStatsRecomputeRoutes); recorded as setup,
// without the secret.
func (s *Session) InternalSetup(name, path string) Response {
	return s.call("setup: "+name, "casino", http.MethodPost, path, nil, NoAuth, map[string]string{"X-Internal-Secret": s.cfg.InternalSecret})
}

// Observe repeats a GET until `until` holds of its answer, within patience, and records only the
// answer that held, as an `observe:` step a replay sends without comparing. An answer that never held
// is recorded as the last one read, and fails the recording.
func (s *Session) Observe(name, service, path string, auth Auth, until func(Response) bool) Response {
	return s.observeWithin(s.cfg.Patience, name, service, path, auth, until)
}

func (s *Session) observeWithin(patience time.Duration, name, service, path string, auth Auth, until func(Response) bool) Response {
	s.t.Helper()
	deadline := s.cfg.Now().Add(patience)
	for {
		r := s.call("observe: "+name, service, http.MethodGet, path, nil, auth, nil)
		if until(r) {
			return r
		}
		if s.cfg.Now().After(deadline) {
			s.t.Errorf("%s: %s not observed within %s: %s", s.rec.Scenario, name, patience, clip(r.Raw))
			return r
		}
		s.rec.Steps = s.rec.Steps[:len(s.rec.Steps)-1]
		s.cfg.Sleep(500 * time.Millisecond)
	}
}

// Eventually makes a compared call again, within patience, until `until` holds, and keeps only its last
// answer: the original caches the brand's catalogue configuration for 60 seconds. A call that never
// settles is kept as answered, for the recording's own check.
func (s *Session) Eventually(read func() Response, until func(Response) bool) Response {
	s.t.Helper()
	deadline := s.cfg.Now().Add(2 * s.cfg.Patience)
	for {
		r := read()
		if until(r) || s.cfg.Now().After(deadline) {
			return r
		}
		s.rec.Steps = s.rec.Steps[:len(s.rec.Steps)-1]
		s.cfg.Sleep(time.Second)
	}
}

func (s *Session) base(service string) string {
	switch service {
	case Service:
		return s.cfg.APIURL
	case "keycloak":
		return s.cfg.KeycloakURL
	case "fake-st8":
		return s.cfg.FakeSt8URL
	case "wallet":
		return s.cfg.WalletInternalURL
	default:
		return s.cfg.CasinoURL
	}
}

// token is the value an Auth stands for this run: a fetched token, asked for again after tokenLife, or
// a remembered one.
func (s *Session) token(auth Auth) string {
	if fetched[auth] && s.cfg.Token != nil {
		at, ok := s.tokens[auth]
		if !ok || s.cfg.Now().Sub(at) > tokenLife {
			v, err := s.cfg.Token(auth)
			if err != nil {
				s.t.Fatalf("%s: %s: %v", s.rec.Scenario, auth, err)
			}
			s.remember(string(auth), v)
			s.tokens[auth] = s.cfg.Now()
		}
	}
	return s.ids[string(auth)]
}

func (s *Session) call(name, service, method, path string, body any, auth Auth, extra map[string]string, recordExtra ...bool) Response {
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
		token := s.token(auth)
		if token == "" {
			s.t.Fatalf("%s: no %s for this run", name, auth)
		}
		req.Header.Set("Authorization", "Bearer "+token)
		headers["Authorization"] = "Bearer {" + string(auth) + "}"
	}
	asSent := len(recordExtra) > 0 && recordExtra[0]
	for k, v := range extra {
		req.Header.Set(k, v)
		if asSent || !unrecorded[k] {
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
	if err := json.Unmarshal(raw, &out.Body); err != nil || out.Body == nil {
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
	if service == Service && !strings.HasPrefix(name, "setup: ") && !strings.HasPrefix(name, "observe: ") {
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

// template replaces this run's minted, captured and named values with their `{name}` placeholders,
// longest first; a value also appears URL-escaped in a path or a query, and is replaced there too.
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
	b, _ := json.MarshalIndent(File{Recording: *s.rec, Roles: s.Roles()}, "", "  ")
	if err := os.WriteFile(path, append([]byte(s.redact(string(b))), '\n'), 0o644); err != nil {
		s.t.Errorf("recording %s: %v", s.rec.Scenario, err)
	}
}

// Credentials are the names of what the original issued a player: sign-in and refresh tokens, the
// verification link and the launch token. Requests carry them as `{name}` already (template); the
// answers that issued them carry them as they were, so the file names them there too. Ids stay as
// they are in answers: a replay reads them by role.
var Credentials = []string{"token-", "refresh-of-", "link-of-", "launch-"}

// redact replaces every credential this run holds with its `{name}`, anywhere in the file.
func (s *Session) redact(text string) string {
	names := make([]string, 0, len(s.order))
	for _, n := range s.order {
		for _, p := range Credentials {
			if strings.HasPrefix(n, p) && s.ids[n] != "" {
				names = append(names, n)
			}
		}
	}
	sort.SliceStable(names, func(i, j int) bool { return len(s.ids[names[i]]) > len(s.ids[names[j]]) })
	for _, n := range names {
		v := s.ids[n]
		for _, form := range []string{v, url.QueryEscape(v), url.PathEscape(v)} {
			text = strings.ReplaceAll(text, form, "{"+n+"}")
		}
	}
	return text
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

// BrandScopeID is the sub-brand scope id a partner token for brand must carry, as the original derives
// it (BrandScopeIds.scala, auth/brandScope.ts, the suite's partnerapi.BrandScopeID):
// UUID.nameUUIDFromBytes("seed.sub-brand." + brand).
func BrandScopeID(brand string) string {
	sum := md5.Sum([]byte("seed.sub-brand." + brand)) //nolint:gosec
	sum[6] = (sum[6] & 0x0f) | 0x30
	sum[8] = (sum[8] & 0x3f) | 0x80
	h := hex.EncodeToString(sum[:])
	return h[0:8] + "-" + h[8:12] + "-" + h[12:16] + "-" + h[16:20] + "-" + h[20:32]
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

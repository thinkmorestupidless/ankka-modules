// Package rewardsdriver drives the original's casino-service at its reward routes (the player's code
// entry and preview, cancel and state read; the operator's grant in its three shapes, revoke, offer
// reads, delete and reorder, and the reward lists; the Customer.io webhook's grant_bonus) with its
// Keycloak, its substrate, fake-st8 and, for one scenario, its Unleash, and records each scenario of
// features/rewards/*.feature that the original's Go end-to-end suite does not reach as one recording
// (spec 011 research R2, T065; tests/original/README.md).
//
// The suite reaches eligibility through scenarios 18 and 21, the lifecycle through 16, 19, 23, 244, 245,
// 254 and the bust, and free rounds through the FreeSpinsPlay tests. It never reaches the #730 gates
// (the duplicate check in progress, the weak duplicate signal, the brand's kill switch), whose only
// oracle is the unit specs (MultiAccountDetectorSpec, CustomerIOWebhookRoutesSpec,
// BackofficePlayerRoutesSpec); nor the operator's two awards with no offer, the revoke of free rounds,
// the preview, the reorder, the delete or the operator's reward lists. This driver calls those routes as
// the player site, the backoffice and Customer.io do. What none of them can do for itself goes in as
// `setup:` steps a replay does not compare: a verification link minted from the original's signing
// secret, an offer written to substrate as the backoffice route writes it (scenario 21's
// s21CreateOffer), the duplicate check's flags written where MultiAccountDetector writes them, a free
// rounds grant marked played out where settle-free-spins-round writes it, the brand's CRM signing key
// written where scenario 21 writes it, and the kill switch set in Unleash.
//
// Every token is the run's own and is written as its `{name}`. The webhook's signature and timestamp, and
// Unleash's admin token, are sent and never written; nor is the signing secret or a password.
//
// The machinery in this file needs no running original, so `go test ./original/rewards-driver/...`
// checks it against a stub; the scenarios themselves are tagged `e2e`.
package rewardsdriver

import (
	"bytes"
	"crypto/hmac"
	"crypto/md5" //nolint:gosec // the original's name-based UUIDs (Java's nameUUIDFromBytes) are MD5
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
	CasinoURL    string // the original's casino-service, e.g. http://localhost:8091
	KeycloakURL  string // the original's Keycloak, e.g. http://localhost:8180
	SubstrateURL string // the original's substrate, e.g. http://localhost:9000
	FakeSt8URL   string // the original's fake-st8, the game provider casino-service is pointed at
	// UnleashURL is the original's Unleash (eitheror-bundle-unleash, port-forwarded), where the brand's
	// kill switch for duplicate detection is set; its admin token is UnleashToken, never written. With no
	// token, the one scenario that needs it is skipped.
	UnleashURL         string
	UnleashToken       string
	UnleashProject     string // the project the flag lives in; "default" when unset
	UnleashEnvironment string // the environment casino-service's token reads; "development" when unset
	Brand              string // the original's seeded brand, whose realm is casino-<brand>
	// SigningSecret is the original's EMAIL_TOKEN_SIGNING_SECRET, from which a verification link is
	// minted as the original mints it (EmailVerificationTokenStore). Never written.
	SigningSecret string
	OperatorToken string // an operator holding brand_admin at the brand; written as {operatorToken}
	RecordTo      string // EITHEROR_RECORD_TO; empty runs without writing
	Commit        string // EITHEROR_ORIGINAL_COMMIT
	Client        *http.Client
	Now           func() time.Time
	// Patience is how long an `observe:` step waits for the original's reads to catch up; 40 seconds
	// when unset. Unleash's SDK in casino-service refreshes its flags every 15 seconds or so, inside it.
	Patience time.Duration
}

// Response is what the original answered to one step: an object in Body, an array in List.
type Response struct {
	Status int
	Body   map[string]any
	List   []any
	Raw    []byte
}

// Str reads a JSON field as a string, whatever its JSON type, or "" when absent.
func (r Response) Str(field string) string { return str(r.Body, field) }

// Prop reads a twin's property from substrate's answer, at the top level or under `properties`.
func (r Response) Prop(field string) string {
	if v := r.Str(field); v != "" {
		return v
	}
	if p, ok := r.Body["properties"].(map[string]any); ok {
		return str(p, field)
	}
	return ""
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

// Session is one scenario: its own players, offers, rewards and tokens.
type Session struct {
	t        testing.TB
	cfg      Config
	rec      *recorder.Recording
	ids      map[string]string // name -> the value minted or captured for it this run
	order    []string
	cleanups []func()
	skipped  bool
}

// Scenario runs body, then the scenario's clean-ups, and writes one recording named exactly `name`.
func Scenario(t testing.TB, cfg Config, name, requirement string, body func(s *Session)) {
	t.Helper()
	if cfg.Client == nil {
		cfg.Client = &http.Client{Timeout: 30 * time.Second}
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
			Service:     "rewards",
		},
	}
	// The run's own credential, so no request or answer carries it as it is.
	s.remember("operatorToken", cfg.OperatorToken)
	// The run's tag: in every address the scenario mints.
	s.remember("tag", "rd"+strings.ReplaceAll(newUUID(), "-", "")[:10])
	defer s.write()
	defer func() {
		for i := len(s.cleanups) - 1; i >= 0; i-- {
			s.cleanups[i]()
		}
	}()
	body(s)
}

// Skip ends a scenario the run cannot drive, before its first step, and writes no recording.
func (s *Session) Skip(reason string) {
	s.skipped = true
	s.t.Skip(reason)
}

// Brand is the brand every path names; recorded as it is, since a replay names its own brand there.
func (s *Session) Brand() string { return s.cfg.Brand }

// ID mints a fresh value for a name the scenario uses and records it as `{name}` in every request.
func (s *Session) ID(name string) string {
	if v, ok := s.ids[name]; ok {
		return v
	}
	v := name + "-" + newUUID()[:8]
	s.remember(name, v)
	return v
}

// UUID mints a fresh UUID under name.
func (s *Session) UUID(name string) string {
	if v, ok := s.ids[name]; ok {
		return v
	}
	s.remember(name, newUUID())
	return s.ids[name]
}

// Code mints an offer code under name: upper case letters and digits, as an operator types one.
func (s *Session) Code(name string) string {
	if v, ok := s.ids[name]; ok {
		return v
	}
	s.remember(name, "RD"+strings.ToUpper(strings.ReplaceAll(newUUID(), "-", "")[:10]))
	return s.ids[name]
}

// Email mints the scenario's address under `name`: `<scenario>-<tag>-<name>-<nonce>@example.test`,
// lower case, so two runs and two scenarios never share one.
func (s *Session) Email(name string) string {
	if v, ok := s.ids[name]; ok {
		return v
	}
	// The local part stays within the 64 characters an address allows: the original refuses a longer one.
	rest := fmt.Sprintf("-%s-%s-%s", s.ids["tag"], name, newUUID()[:8])
	slug := strings.ToLower(FileName(s.rec.Scenario))
	if most := min(30, 64-len(rest)); len(slug) > most {
		slug = strings.Trim(slug[:max(most, 1)], "-.")
	}
	v := slug + rest + "@example.test"
	s.remember(name, strings.ToLower(v))
	return s.ids[name]
}

// Value is a value minted or captured under name, or "" when there is none.
func (s *Session) Value(name string) string { return s.ids[name] }

// Grab names a value the original generated in the last response, for later steps' `{name}`.
func (s *Session) Grab(name, jsonPath, value string) {
	if value == "" {
		s.t.Errorf("capture %s: %s was empty in the last response", name, jsonPath)
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

// Cleanup runs f after the scenario, before its recording is written; clean-ups run last first.
func (s *Session) Cleanup(f func()) { s.cleanups = append(s.cleanups, f) }

// Want records a mismatch between the original's status and the one the feature expects as a failure
// of the scenario: the recording is still written, and the run's result shows it.
func (s *Session) Want(r Response, status int) Response {
	if r.Status != status {
		s.t.Errorf("%s: the original answered %d (want %d): %s", s.rec.Scenario, r.Status, status, string(r.Raw))
	}
	return r
}

// WantField records a mismatch between a field of the original's answer and the one the feature expects.
func (s *Session) WantField(r Response, field, value string) Response {
	if got := r.Str(field); got != value {
		s.t.Errorf("%s: the original answered %s %q (want %q): %s", s.rec.Scenario, field, got, value, string(r.Raw))
	}
	return r
}

// Auth names the token a step carries: the run value remembered under that name, sent as a bearer
// token and recorded as `Bearer {name}`. Empty sends none.
type Auth string

const (
	NoAuth   Auth = ""
	Player   Auth = "token"         // the scenario's player's sign-in
	Operator Auth = "operatorToken" // an operator of the brand
)

// unrecorded are the headers a step sends and its recording never holds: the webhook's signature and
// its timestamp (both differ on every run and are Customer.io's, which `engagement` checks in the port)
// and Unleash's admin token.
var unrecorded = map[string]bool{"X-CIO-Signature": true, "X-CIO-Timestamp": true, "Authorization": true}

// Casino calls casino-service: a step a replay sends to the port and compares.
func (s *Session) Casino(name, method, path string, body any, auth Auth) Response {
	return s.call(name, "casino", method, path, body, auth, nil)
}

// Setup calls casino-service, Keycloak, substrate or fake-st8 for what the frontend cannot do;
// recorded as a `setup:` step a replay skips.
func (s *Session) Setup(name, service, method, path string, body any, auth Auth) Response {
	return s.call("setup: "+name, service, method, path, body, auth, nil)
}

// Webhook posts body to the brand's Customer.io webhook as Customer.io does: `X-CIO-Timestamp` and
// `X-CIO-Signature: hex(HMAC-SHA256(key, "v0:<timestamp>:<body>"))` (CustomerIOWebhookRoutes.
// verifySignature). Neither header is written; a replay sends the port's `reward-campaigns` route
// instead (contracts/wire.md).
func (s *Session) Webhook(name, key string, body map[string]any) Response {
	raw, _ := json.Marshal(body)
	ts := fmt.Sprintf("%d", s.cfg.Now().Unix())
	return s.callRaw(name, "casino", http.MethodPost, "/v1/casino/customerio/webhook/"+s.Brand(), raw, NoAuth,
		map[string]string{"X-CIO-Timestamp": ts, "X-CIO-Signature": CIOSignature(key, ts, raw)})
}

// Unleash calls Unleash's admin API with the run's admin token, as `setup:`; the token is never written.
func (s *Session) Unleash(name, method, path string, body any) Response {
	return s.call("setup: "+name, "unleash", method, path, body, NoAuth, map[string]string{"Authorization": s.cfg.UnleashToken})
}

// Observe repeats a call until `until` holds of its answer, within the run's patience, and records
// only the answer that held, as an `observe:` step a replay sends without comparing. An answer that
// never held is recorded as the last one read, and fails the scenario.
func (s *Session) Observe(name, service, method, path string, auth Auth, until func(Response) bool) Response {
	s.t.Helper()
	deadline := time.Now().Add(s.cfg.Patience)
	for {
		r := s.call("observe: "+name, service, method, path, nil, auth, nil)
		if until(r) {
			return r
		}
		if time.Now().After(deadline) {
			s.t.Errorf("%s: %s not observed within %s: %s", s.rec.Scenario, name, s.cfg.Patience, string(r.Raw))
			return r
		}
		s.rec.Steps = s.rec.Steps[:len(s.rec.Steps)-1]
		time.Sleep(250 * time.Millisecond)
	}
}

// Eventually makes a compared call again, for up to the run's patience, until `until` holds, and keeps
// only its last answer: the original finds an offer by code through substrate's projection, which
// follows a write by moments. A call that never settles is kept as answered, for the scenario's own
// check to report.
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
	case "substrate":
		return s.cfg.SubstrateURL
	case "fake-st8":
		return s.cfg.FakeSt8URL
	case "unleash":
		return s.cfg.UnleashURL
	default:
		return s.cfg.CasinoURL
	}
}

func (s *Session) call(name, service, method, path string, body any, auth Auth, extra map[string]string) Response {
	var payload []byte
	if body != nil {
		payload, _ = json.Marshal(body)
	}
	return s.callRaw(name, service, method, path, payload, auth, extra)
}

func (s *Session) callRaw(name, service, method, path string, payload []byte, auth Auth, extra map[string]string) Response {
	s.t.Helper()
	u, err := url.Parse(strings.TrimSuffix(s.base(service), "/") + path)
	if err != nil {
		s.t.Fatalf("%s: %v", name, err)
	}
	if !recorder.Local(u.Host) {
		s.t.Fatalf("refusing %s: nothing may leave the machine while the original is recorded", u.Host)
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
	out := Response{Status: resp.StatusCode, Raw: raw, Body: map[string]any{}}
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

// FileName is a scenario's file name: its words, with anything a path cannot hold made a dash.
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

// CIOSignature is Customer.io's webhook signature as the original checks it: hex HMAC-SHA256 of
// `v0:<timestamp>:<body>` under the brand's webhook signing key.
func CIOSignature(key, timestamp string, body []byte) string {
	m := hmac.New(sha256.New, []byte(key))
	m.Write([]byte("v0:" + timestamp + ":"))
	m.Write(body)
	return hex.EncodeToString(m.Sum(nil))
}

// NameUUID is Java's UUID.nameUUIDFromBytes: MD5, version 3. The original keys the brand's legacy CRM
// config twin by it.
func NameUUID(name string) string {
	h := md5.Sum([]byte(name)) //nolint:gosec
	h[6] = (h[6] & 0x0f) | 0x30
	h[8] = (h[8] & 0x3f) | 0x80
	return fmt.Sprintf("%x-%x-%x-%x-%x", h[0:4], h[4:6], h[6:8], h[8:10], h[10:16])
}

// Decimal renders minor units as the original's major-unit string: 1050 -> "10.50", -100 -> "-1.00".
func Decimal(minor int64) string {
	sign := ""
	if minor < 0 {
		sign, minor = "-", -minor
	}
	return fmt.Sprintf("%s%d.%02d", sign, minor/100, minor%100)
}

// PasswordToken fetches a token from a realm by the password grant, outside any recording: it is how
// the run obtains the operator's sign-in, which the steps then carry as `{operatorToken}`.
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

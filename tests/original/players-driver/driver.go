// Package playersdriver drives the original's casino-service at its player, session and backoffice
// routes, with its Keycloak and its substrate for setup, and records each scenario of
// features/players/*.feature the original can show as one recording (spec 008 research R1;
// tests/original/README.md).
//
// The original's end-to-end suite registers players only on the way to something else (scenario 18
// is the one that looks at the registration itself), so the refusals, the verification link, the
// profile's locks, the second address, the password change and the operator's actions rest on its unit
// suites, which mock the substrate and record nothing. This driver calls the routes as the player
// frontend and the backoffice do. What the frontend cannot do for itself goes in as `setup:` steps a
// replay does not compare: a verification link is minted from the original's signing secret (the
// email that carries it is not read), a token the original keeps on the player (a second address's,
// a password change's) is read from substrate, a status the original sets only by migration
// (`blocked`) is written there, and a realm user is read through Keycloak's admin API.
//
// Every token is the run's own and is written as its `{name}`: a player's sign-in, refresh and
// verification link, the operator's sign-in and the realm admin's. The signing secret, the admin
// password and the operator's password are never written.
//
// The machinery in this file needs no running original, so `go test ./original/players-driver/...`
// checks it against a stub; the scenarios themselves are tagged `e2e`.
package playersdriver

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
	CasinoURL    string // the original's casino-service, e.g. http://localhost:8085
	KeycloakURL  string // the original's Keycloak, e.g. http://localhost:8080
	SubstrateURL string // the original's substrate, e.g. http://localhost:8082
	Brand        string // the original's seeded brand, whose realm is casino-<brand>
	// SigningSecret is the original's EMAIL_TOKEN_SIGNING_SECRET, from which a verification link is
	// minted as the original mints it (EmailVerificationTokenStore). Never written.
	SigningSecret string
	AdminToken    string // a master-realm admin token for the realm's admin API; written as {adminToken}
	OperatorToken string // an operator of the brand (brand_admin); written as {operatorToken}
	OutsiderToken string // an operator with no role at the brand; written as {outsiderToken}
	RecordTo      string // EITHEROR_RECORD_TO; empty runs without writing
	Commit        string // EITHEROR_ORIGINAL_COMMIT
	Client        *http.Client
	Now           func() time.Time
}

// Response is what the original answered to one step.
type Response struct {
	Status int
	Body   map[string]any
	Raw    []byte
}

// Str reads a JSON field as a string, whatever its JSON type, or "" when absent.
func (r Response) Str(field string) string {
	v, ok := r.Body[field]
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

// Session is one scenario: its own players, its own addresses, its own tokens.
type Session struct {
	t     testing.TB
	cfg   Config
	rec   *recorder.Recording
	ids   map[string]string // name -> the value minted or captured for it this run
	order []string
}

// Scenario runs body and writes one recording named exactly `name`.
func Scenario(t testing.TB, cfg Config, name, requirement string, body func(s *Session)) {
	t.Helper()
	if cfg.Client == nil {
		cfg.Client = &http.Client{Timeout: 30 * time.Second}
	}
	if cfg.Now == nil {
		cfg.Now = time.Now
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
			Service:     "players",
		},
	}
	// The run's own credentials, so no request or answer carries one as it is.
	s.remember("adminToken", cfg.AdminToken)
	s.remember("operatorToken", cfg.OperatorToken)
	s.remember("outsiderToken", cfg.OutsiderToken)
	defer s.write()
	body(s)
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

// Email mints the scenario's address under `name`: `<scenario>-<nonce>@example.test`, lower case, so
// two runs and two scenarios never share one.
func (s *Session) Email(name string) string {
	if v, ok := s.ids[name]; ok {
		return v
	}
	slug := strings.ToLower(FileName(s.rec.Scenario))
	if len(slug) > 40 {
		slug = strings.Trim(slug[:40], "-.")
	}
	v := fmt.Sprintf("%s-%s-%s@example.test", slug, name, newUUID()[:8])
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
	Other    Auth = "otherToken"    // a second player's sign-in
	Operator Auth = "operatorToken" // an operator of the brand
	Outsider Auth = "outsiderToken" // an operator with no role at the brand
	Admin    Auth = "adminToken"    // the realm's admin, for setup only
)

// Form is a body sent form-encoded, as Keycloak's token endpoint takes one.
type Form url.Values

// Casino calls casino-service: a step a replay sends to the port and compares.
func (s *Session) Casino(name, method, path string, body any, auth Auth, headers map[string]string) Response {
	return s.call(name, "casino", method, path, body, auth, headers)
}

// Setup calls casino-service, Keycloak or substrate for what the frontend cannot do; recorded as a
// `setup:` step a replay skips.
func (s *Session) Setup(name, service, method, path string, body any, auth Auth) Response {
	return s.call("setup: "+name, service, method, path, body, auth, nil)
}

// Observe repeats a call until `until` holds of its answer, within 30 seconds, and records only the
// answer that held, as an `observe:` step a replay sends without comparing.
func (s *Session) Observe(name, service, method, path string, auth Auth, until func(Response) bool) Response {
	s.t.Helper()
	deadline := time.Now().Add(30 * time.Second)
	for {
		r := s.call("observe: "+name, service, method, path, nil, auth, nil)
		if until(r) {
			return r
		}
		s.rec.Steps = s.rec.Steps[:len(s.rec.Steps)-1]
		if time.Now().After(deadline) {
			s.t.Fatalf("%s: not observed within 30 seconds: %s", name, string(r.Raw))
		}
		time.Sleep(250 * time.Millisecond)
	}
}

func (s *Session) base(service string) string {
	switch service {
	case "keycloak":
		return s.cfg.KeycloakURL
	case "substrate":
		return s.cfg.SubstrateURL
	default:
		return s.cfg.CasinoURL
	}
}

func (s *Session) call(name, service, method, path string, body any, auth Auth, extra map[string]string) Response {
	s.t.Helper()
	u, err := url.Parse(s.base(service) + path)
	if err != nil {
		s.t.Fatalf("%s: %v", name, err)
	}
	if !recorder.Local(u.Host) {
		s.t.Fatalf("refusing %s: nothing may leave the machine while the original is recorded", u.Host)
	}
	var payload []byte
	contentType := "application/json"
	switch b := body.(type) {
	case nil:
	case Form:
		payload = []byte(url.Values(b).Encode())
		contentType = "application/x-www-form-urlencoded"
	default:
		payload, _ = json.Marshal(b)
	}
	req, _ := http.NewRequest(method, u.String(), bytes.NewReader(payload))
	headers := map[string]string{}
	if payload != nil {
		req.Header.Set("Content-Type", contentType)
		headers["Content-Type"] = contentType
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
		headers[k] = s.template(v)
	}
	resp, err := s.cfg.Client.Do(req)
	if err != nil {
		s.t.Fatalf("%s: %v", name, err)
	}
	raw, _ := io.ReadAll(resp.Body)
	_ = resp.Body.Close()
	out := Response{Status: resp.StatusCode, Raw: raw, Body: map[string]any{}}
	_ = json.Unmarshal(raw, &out.Body)

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
	if payload != nil {
		text := s.template(string(payload))
		if contentType == "application/json" {
			step.Request.Body = json.RawMessage(text)
		} else {
			q, _ := json.Marshal(text)
			step.Request.Body = q
		}
	}
	s.rec.Steps = append(s.rec.Steps, step)
	return out
}

// template replaces this run's minted and captured values with their `{name}` placeholders, longest
// first; a value also appears URL-escaped in a path or a form, and is replaced there too.
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
	if s.cfg.RecordTo == "" {
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
// base64url HMAC-SHA256 of that under the signing secret.
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

// PasswordToken fetches a token from a realm by the password grant, outside any recording: it is how
// the run obtains the admin's and the operator's sign-ins, which the steps then carry as `{name}`.
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

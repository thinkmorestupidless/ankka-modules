// Package payoutsdriver drives the original's casino-service at its withdrawal routes (the player's
// request, cancel and reads, the terminal listing, the destinations, the operator's queue and
// decisions, and the internal outcome route), with its Keycloak, its substrate, its wallet-service
// and psp-stub for setup, and records each scenario of features/payouts/*.feature the original can
// show as one recording (spec 007 research R2, scenario 30; tests/original/README.md).
//
// The original's end-to-end suite reaches the withdrawal routes only in scenario 251 (a manual payout
// on its own terminal), so the gates, the destinations, the listing, the queue's filters and the
// outcome route would rest on WithdrawalRoutesSpec, which fakes the substrate, the wallet and the
// gateway and records nothing. This driver calls the routes as the player site, the backoffice and
// psp-gateway do. What none of them can do for itself goes in as `setup:` steps a replay does not
// compare: a verification link minted from the original's signing secret, a deposit through the
// wallet's internal route, a terminal and the brand's terminal index written to substrate as
// merchant-service writes them, an identity status written where the KYC rollup writes it
// (`kyc_status`), deposit turnover and a payout's `NEEDS_REVIEW` written where the wallet and the
// reconciliation sweeper write them, and psp-stub's control.
//
// Every token is the run's own and is written as its `{name}`. The outcome route is signed with the
// original's internal secret (`WALLET_SERVICE_INTERNAL_SECRET`) and the wallet's internal deposit
// with its HMAC; neither header is written, and neither is the signing secret nor a password.
//
// The machinery in this file needs no running original, so `go test ./original/payouts-driver/...`
// checks it against a stub; the scenarios themselves are tagged `e2e`.
package payoutsdriver

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
	WalletURL    string // the original's wallet-service, for the player's balance
	// WalletInternalURL is wallet-service's internal API (its port 9001), which serves `/internal/v1/wallet/*`:
	// the deposits. The public port answers those 404.
	WalletInternalURL string
	StubURL           string // psp-stub's Unity Finance, whose control the automated payouts set
	Brand             string // the original's seeded brand, whose realm is casino-<brand>
	// SigningSecret is the original's EMAIL_TOKEN_SIGNING_SECRET, from which a verification link is
	// minted as the original mints it (EmailVerificationTokenStore). Never written.
	SigningSecret string
	// InternalSecret is the original's WALLET_SERVICE_INTERNAL_SECRET: sent as it is on the outcome
	// route (WithdrawalRoutes.requireInternalAuth), and as its HMAC of the brand on the wallet's
	// internal routes (WalletServiceClient). Never written.
	InternalSecret string
	OperatorToken  string // an operator of the brand (brand_admin or brand_finance); written as {operatorToken}
	OutsiderToken  string // an operator with no role at the brand; written as {outsiderToken}
	RecordTo       string // EITHEROR_RECORD_TO; empty runs without writing
	Commit         string // EITHEROR_ORIGINAL_COMMIT
	Client         *http.Client
	Now            func() time.Time
	// Patience is how long an `observe:` step waits for the original's reads to catch up; 40 seconds,
	// scenario 251's s251Wait, when unset.
	Patience time.Duration
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

// Session is one scenario: its own players, terminals, requests and tokens.
type Session struct {
	t        testing.TB
	cfg      Config
	rec      *recorder.Recording
	ids      map[string]string // name -> the value minted or captured for it this run
	order    []string
	cleanups []func()
	cached   map[string]string // a terminal made once per scenario, by its kind
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
	s := &Session{
		t:      t,
		cfg:    cfg,
		ids:    map[string]string{},
		cached: map[string]string{},
		rec: &recorder.Recording{
			Scenario:    name,
			Requirement: requirement,
			RecordedAt:  time.Now().UTC(),
			Original:    map[string]string{"commit": cfg.Commit},
			Runs:        1,
			Service:     "payouts",
		},
	}
	// The run's own credentials, so no request or answer carries one as it is.
	s.remember("operatorToken", cfg.OperatorToken)
	s.remember("outsiderToken", cfg.OutsiderToken)
	// The run's tag: in every address the scenario mints, so the queue can be narrowed to this run.
	s.remember("tag", "pd"+strings.ReplaceAll(newUUID(), "-", "")[:10])
	defer s.write()
	defer func() {
		for i := len(s.cleanups) - 1; i >= 0; i-- {
			s.cleanups[i]()
		}
	}()
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

// UUID mints a fresh UUID under name: the original keys a terminal's twin by its id when the id is a
// UUID (SubstrateTwinClient.resolvedEntityId), so every reader finds it under the same id.
func (s *Session) UUID(name string) string {
	if v, ok := s.ids[name]; ok {
		return v
	}
	s.remember(name, newUUID())
	return s.ids[name]
}

// Email mints the scenario's address under `name`: `<scenario>-<tag>-<name>-<nonce>@example.test`,
// lower case, so two runs and two scenarios never share one and the run's tag finds every one.
func (s *Session) Email(name string) string {
	if v, ok := s.ids[name]; ok {
		return v
	}
	// The local part stays within the 64 characters an address allows: the original refuses a longer one
	// with a bare 503 ("Registration failed"), so the slug gives way to the rest.
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
	Other    Auth = "otherToken"    // a second player's sign-in
	Operator Auth = "operatorToken" // an operator of the brand
	Outsider Auth = "outsiderToken" // an operator with no role at the brand
)

// unrecorded are the headers a step sends and its recording never holds: the internal secret, as it
// is or as its HMAC. The merge redacts the first as well (merge.Redact).
var unrecorded = map[string]bool{"X-Internal-Secret": true}

// Casino calls casino-service: a step a replay sends to the port and compares.
func (s *Session) Casino(name, method, path string, body any, auth Auth) Response {
	return s.call(name, "casino", method, path, body, auth, nil)
}

// Outcome calls casino-service's internal outcome route as psp-gateway does, with the internal secret
// in `X-Internal-Secret`; the secret is sent and never recorded. A replay sends it to the port's
// outcome route under the gateway's grant (research R6).
func (s *Session) Outcome(name, requestID string, body map[string]any) Response {
	return s.call(name, "casino", http.MethodPost, "/internal/v1/casino/withdrawal-requests/"+requestID+"/outcome", body, NoAuth,
		map[string]string{"X-Internal-Secret": s.cfg.InternalSecret})
}

// Setup calls casino-service, Keycloak, substrate, wallet-service or psp-stub for what the frontend
// cannot do; recorded as a `setup:` step a replay skips.
func (s *Session) Setup(name, service, method, path string, body any, auth Auth) Response {
	return s.call("setup: "+name, service, method, path, body, auth, nil)
}

// WalletSetup calls wallet-service's internal routes as casino-service and merchant-service do,
// signed `X-Internal-Brand-Id` and `X-Internal-Secret: hex(HMAC-SHA256(secret, brand))`; recorded as
// a `setup:` step, the signature never written.
func (s *Session) WalletSetup(name, method, path string, body any) Response {
	return s.call("setup: "+name, "wallet", method, path, body, NoAuth, map[string]string{
		"X-Internal-Brand-Id": s.Brand(),
		"X-Internal-Secret":   Signature(s.cfg.InternalSecret, s.Brand()),
	})
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

func (s *Session) base(service, path string) string {
	switch service {
	case "keycloak":
		return s.cfg.KeycloakURL
	case "substrate":
		return s.cfg.SubstrateURL
	case "wallet":
		if strings.HasPrefix(path, "/internal/") {
			return s.cfg.WalletInternalURL
		}
		return s.cfg.WalletURL
	case "psp-stub":
		return s.cfg.StubURL
	default:
		return s.cfg.CasinoURL
	}
}

func (s *Session) call(name, service, method, path string, body any, auth Auth, extra map[string]string) Response {
	s.t.Helper()
	u, err := url.Parse(s.base(service, path) + path)
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

// Signature is the original's wallet `X-Internal-Secret`: HMAC-SHA256 of the brand under the shared
// secret, hex (WalletServiceClient.hmacSha256Hex).
func Signature(secret, brand string) string {
	m := hmac.New(sha256.New, []byte(secret))
	m.Write([]byte(brand))
	return hex.EncodeToString(m.Sum(nil))
}

// NameUUID is Java's UUID.nameUUIDFromBytes: MD5, version 3. The original keys the wallet twin, the
// sub-brand and the brand's terminal index by it.
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

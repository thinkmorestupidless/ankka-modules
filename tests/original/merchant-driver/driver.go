// Package merchantdriver drives the original's merchant-service cashier at its own routes and records
// each scenario as one recording (spec 004 research R6; specs/004-merchant/contracts/recording.md).
//
// The original's end-to-end suite reaches the cashier only on its happy path (scenarios 12 to 14), so
// the failure, expiry, abandon, signature and settlement-variance behaviours rest on its unit suites,
// which mock the substrate and record nothing. This driver calls the cashier as `psp-gateway` and the
// player frontend do: the public session, submit and read routes under a merchant key, and the
// capture, fail, abandon and sweep routes under the internal secret. Setup the cashier cannot do for
// itself (a sub-brand with a key, a credential and a deployed tree; a webhook secret on that
// credential; the gateway's undelivered marker) goes through the original's seed routes and substrate
// directly, and is recorded as `setup:` steps a replay skips.
//
// The machinery in this file needs no running original, so `go test ./original/merchant-driver/...`
// checks it against a stub; the scenarios themselves are tagged `e2e`.
package merchantdriver

import (
	"bytes"
	"crypto/hmac"
	"crypto/md5"
	"crypto/rand"
	"crypto/sha256"
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

// Config is where the original runs and where recordings go.
type Config struct {
	MerchantURL    string // the original's merchant-service, e.g. http://localhost:8085
	SubstrateURL   string // the original's substrate, e.g. http://localhost:8080
	InternalSecret string // MERCHANT_SERVICE_INTERNAL_SECRET; never recorded
	RecordTo       string // EITHEROR_RECORD_TO; empty runs without writing
	Commit         string // EITHEROR_ORIGINAL_COMMIT
	Service        string // the port the recordings are for, and their directory; "merchant" when empty
	Client         *http.Client
	Now            func() time.Time
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

// Session is one scenario on its own fresh sub-brand.
type Session struct {
	t     testing.TB
	cfg   Config
	rec   *recorder.Recording
	ids   map[string]string // name -> the value minted or captured for it this run
	order []string
}

// Scenario runs body on a fresh brand id and writes one recording named exactly `name`.
func Scenario(t testing.TB, cfg Config, name, requirement string, body func(s *Session)) {
	t.Helper()
	if cfg.Client == nil {
		cfg.Client = &http.Client{Timeout: 30 * time.Second}
	}
	if cfg.Now == nil {
		cfg.Now = time.Now
	}
	if cfg.Service == "" {
		cfg.Service = "merchant"
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
			Service:     cfg.Service,
		},
	}
	defer s.write()
	body(s)
}

// ID mints a fresh value for a name the scenario uses (a brand, a reference, a customer) and records
// it as `{name}` in every request, so a replay mints its own.
func (s *Session) ID(name string) string {
	if v, ok := s.ids[name]; ok {
		return v
	}
	v := name + "-" + newUUID()[:8]
	s.ids[name] = v
	s.order = append(s.order, name)
	return v
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
// of the scenario: the recording is still written, and suite-result.json shows it.
func (s *Session) Want(r Response, status int) Response {
	if r.Status != status {
		s.t.Errorf("%s: the original answered %d (want %d): %s", s.rec.Scenario, r.Status, status, string(r.Raw))
	}
	return r
}

// WantField records a mismatch between a field of the original's answer and the one the feature
// expects, as Want does for the status: a status alone does not say the behaviour happened.
func (s *Session) WantField(r Response, field, value string) Response {
	if got := r.Str(field); got != value {
		s.t.Errorf("%s: the original answered %s %q (want %q): %s", s.rec.Scenario, field, got, value, string(r.Raw))
	}
	return r
}

// Observe repeats a call until `until` holds of its answer, within 30 seconds, and records only the
// answer that held, as an `observe:` step a replay sends without comparing. It is for the original's
// eventual consistency: a step that must not run before the original can see what it acts on.
func (s *Session) Observe(name, service, method, path string, until func(Response) bool) Response {
	s.t.Helper()
	base := s.cfg.MerchantURL
	if service == "substrate" {
		base = s.cfg.SubstrateURL
	}
	deadline := time.Now().Add(30 * time.Second)
	for {
		r := s.call("observe: "+name, service, method, base, path, nil, NoAuth, nil)
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

// Auth is how a step authenticates to the original: as the frontend (a merchant key), as a service
// (the internal secret), or not at all.
type Auth int

const (
	NoAuth Auth = iota
	MerchantKey
	Internal
	// NoKey sends no merchantKey and records the header empty, so a replay knows none was sent
	// rather than that the recorder dropped it (the Go suite's recordings keep no such header).
	NoKey
)

// Merchant calls merchant-service.
func (s *Session) Merchant(name, method, path string, body any, auth Auth, headers map[string]string) Response {
	return s.call(name, "merchant", method, s.cfg.MerchantURL, path, body, auth, headers)
}

// Setup calls merchant-service or substrate for what the original's seed and other services did;
// recorded as a `setup:` step a replay skips.
func (s *Session) Setup(name, service, method, path string, body any, auth Auth) Response {
	base := s.cfg.MerchantURL
	if service == "substrate" {
		base = s.cfg.SubstrateURL
	}
	return s.call("setup: "+name, service, method, base, path, body, auth, nil)
}

func (s *Session) call(name, service, method, base, path string, body any, auth Auth, extra map[string]string) Response {
	s.t.Helper()
	u, err := url.Parse(base + path)
	if err != nil {
		s.t.Fatalf("%s: %v", name, err)
	}
	if !recorder.Local(u.Host) {
		s.t.Fatalf("refusing %s: nothing may leave the machine while the original is recorded", u.Host)
	}
	var payload []byte
	switch b := body.(type) {
	case nil:
	case RawBody:
		payload = []byte(b)
	default:
		payload, _ = json.Marshal(b)
	}
	req, _ := http.NewRequest(method, u.String(), bytes.NewReader(payload))
	headers := map[string]string{}
	if payload != nil {
		req.Header.Set("Content-Type", "application/json")
		headers["Content-Type"] = "application/json"
	}
	switch auth {
	case MerchantKey:
		req.Header.Set("merchantKey", s.ids["merchantKey"])
		headers["merchantKey"] = "{merchantKey}" // the raw key is captured, so a replay uses its own
	case Internal:
		req.Header.Set("X-Internal-Secret", s.cfg.InternalSecret) // never recorded
	case NoKey:
		headers["merchantKey"] = ""
	}
	for k, v := range extra {
		req.Header[k] = []string{v} // spelt as given, not canonicalised: a scenario may name the header's case
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
		step.Request.Body = json.RawMessage(s.template(string(payload)))
	}
	s.rec.Steps = append(s.rec.Steps, step)
	return out
}

// RawBody is a body sent exactly as written, for a capture whose signature is over these bytes.
type RawBody string

// template replaces this run's minted and captured values with their `{name}` placeholders, longest first.
func (s *Session) template(text string) string {
	names := append([]string(nil), s.order...)
	sort.SliceStable(names, func(i, j int) bool { return len(s.ids[names[i]]) > len(s.ids[names[j]]) })
	for _, n := range names {
		if v := s.ids[n]; v != "" {
			text = strings.ReplaceAll(text, v, "{"+n+"}")
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

// Sign is the provider's capture signature the original verifies: hex HMAC-SHA256 of the raw body
// under the credential's webhookSecret (CashierRoutes.hmacSha256Hex).
func Sign(secret string, body []byte) string {
	m := hmac.New(sha256.New, []byte(secret))
	m.Write(body)
	return hex.EncodeToString(m.Sum(nil))
}

// NameUUID is Java's UUID.nameUUIDFromBytes: an MD5 name-based (version 3) UUID.
func NameUUID(name string) string {
	h := md5.Sum([]byte(name))
	h[6] = (h[6] & 0x0f) | 0x30
	h[8] = (h[8] & 0x3f) | 0x80
	return formatUUID(h)
}

// TwinID is the substrate id the original's MerchantServiceClient.twinUrl gives an entity id: the id
// itself when it is a UUID, else NameUUID("merchant." + id).
func TwinID(id string) string {
	if uuidPattern.MatchString(id) {
		return id
	}
	return NameUUID("merchant." + id)
}

var uuidPattern = regexp.MustCompile(`^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$`)

func newUUID() string {
	var b [16]byte
	_, _ = rand.Read(b[:])
	b[6] = (b[6] & 0x0f) | 0x40
	b[8] = (b[8] & 0x3f) | 0x80
	return formatUUID(b)
}

func formatUUID(b [16]byte) string {
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

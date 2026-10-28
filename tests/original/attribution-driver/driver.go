// Package attributiondriver drives the original's affiliate-service at the routes spec 012's
// `attribution` takes over, and records each scenario of features/attribution/*.feature the original
// can show as one recording (specs/012-affiliate-protocol/contracts/recording.md).
//
// The original's suite reaches the affiliate service only in scenario 17 and the scenario-15 GGR tests,
// all hard-skipped on the KinD stack. What it never sends is what this driver sends: the public click
// with and without a tag, the registration notification and the twin it writes, a second tag for a
// bound player, deposits, withdrawals and their reversal, the bonus cost as casino-service sends it,
// and a chargeback. Each scenario runs on a fresh brand and seeds its own plan, affiliate and link
// twins through substrate as scenario 17 does, recorded as `setup:` steps a replay turns into kit
// setup. Twin reads are `observe:` steps a replay turns into expected facts.
//
// The machinery needs no running original, so `go test ./original/attribution-driver/...` checks it
// and the scenario names against a stub; the run itself is tagged `e2e`.
package attributiondriver

import (
	"bytes"
	"crypto/md5"
	"crypto/rand"
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
	AffiliateURL   string // the original's affiliate-service, e.g. http://localhost:8082 (a port-forward on KinD)
	SubstrateURL   string // the original's substrate, e.g. http://localhost:8080
	InternalSecret string // the affiliate service's internal secret; sent as X-Internal-Secret, never recorded
	RecordTo       string // EITHEROR_RECORD_TO; empty runs without writing
	Commit         string // EITHEROR_ORIGINAL_COMMIT
	Client         *http.Client
}

// Service is the port the recordings are for, and their directory.
const Service = "attribution"

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
	case float64, bool:
		return fmt.Sprintf("%v", x)
	default:
		b, _ := json.Marshal(x)
		return string(b)
	}
}

// Session is one scenario on its own fresh brand.
type Session struct {
	t     testing.TB
	cfg   Config
	rec   *recorder.Recording
	ids   map[string]string
	order []string
}

// Scenario runs body on a fresh brand and writes one recording named exactly `name`.
func Scenario(t testing.TB, cfg Config, name, requirement string, body func(s *Session)) {
	t.Helper()
	if cfg.Client == nil {
		cfg.Client = &http.Client{Timeout: 30 * time.Second}
	}
	s := &Session{t: t, cfg: cfg, ids: map[string]string{}, rec: &recorder.Recording{
		Scenario:    name,
		Requirement: requirement,
		RecordedAt:  time.Now().UTC(),
		Original:    map[string]string{"commit": cfg.Commit},
		Runs:        1,
		Service:     Service,
	}}
	s.Remember("brand", "brand-"+NewUUID()[:8])
	defer s.write()
	body(s)
}

// UUID mints a fresh UUID under name (the original's twin ids must be UUIDs) and records it as
// `{name}` in every later request, so a replay mints its own.
func (s *Session) UUID(name string) string {
	if v, ok := s.ids[name]; ok {
		return v
	}
	v := NewUUID()
	s.Remember(name, v)
	return v
}

// Value is a value minted or captured under name, or "" when there is none.
func (s *Session) Value(name string) string { return s.ids[name] }

// Remember names a value this run made or read, so every later request carries `{name}` in its place.
func (s *Session) Remember(name, value string) {
	if _, ok := s.ids[name]; !ok {
		s.order = append(s.order, name)
	}
	s.ids[name] = value
}

// Grab names a value the original generated in the last response, for later steps' `{name}`.
func (s *Session) Grab(name, jsonPath, value string) {
	if value == "" {
		s.t.Errorf("capture %s: %s was empty in the last response", name, jsonPath)
		return
	}
	last := s.last()
	if last.Capture == nil {
		last.Capture = map[string]string{}
	}
	last.Capture[name] = jsonPath
	s.Remember(name, value)
}

// Vary marks a field of the last answer as one the original mints or stamps (an id, a timestamp):
// checked for presence only.
func (s *Session) Vary(jsonPath, why string) {
	last := s.last()
	if last.Vary == nil {
		last.Vary = map[string]string{}
	}
	last.Vary[jsonPath] = why
}

func (s *Session) last() *recorder.Step {
	if len(s.rec.Steps) == 0 {
		s.t.Fatalf("no step yet")
	}
	return &s.rec.Steps[len(s.rec.Steps)-1]
}

// Want records a mismatch between the original's status and the one expected as a failure of the
// scenario; the recording is still written.
func (s *Session) Want(r Response, status int) Response {
	if r.Status != status {
		s.t.Errorf("%s: the original answered %d (want %d): %s", s.rec.Scenario, r.Status, status, string(r.Raw))
	}
	return r
}

// WantField is Want for one field of the answer.
func (s *Session) WantField(r Response, field, value string) Response {
	if got := r.Str(field); got != value {
		s.t.Errorf("%s: the original answered %s %q (want %q): %s", s.rec.Scenario, field, got, value, string(r.Raw))
	}
	return r
}

// Auth is whether a step carries the internal secret.
type Auth int

const (
	Public Auth = iota
	Internal
)

// Affiliate calls affiliate-service.
func (s *Session) Affiliate(name, method, path string, body any, auth Auth) Response {
	return s.call(name, "affiliate", method, s.cfg.AffiliateURL, path, body, auth)
}

// Notify posts one of the original's internal notifications for the session's brand.
func (s *Session) Notify(name, kind string, body map[string]any) Response {
	return s.Affiliate(name, http.MethodPost, "/internal/v1/affiliates/"+s.Value("brand")+"/notify/"+kind, body, Internal)
}

// Setup writes a twin to substrate as the original's own seed does; recorded as a `setup:` step.
func (s *Session) Setup(name, namespace, id string, props map[string]string) Response {
	r := s.call("setup: "+name, "substrate", http.MethodPost, s.cfg.SubstrateURL, "/twins/"+namespace+"/"+id, props, Public)
	return s.Want(r, http.StatusCreated)
}

// Observe reads a twin until `until` holds of it, within 30 seconds, and records only the read that
// held, as an `observe:` step: the original writes its twins after it answers.
func (s *Session) Observe(name, namespace, id string, until func(Response) bool) Response {
	s.t.Helper()
	deadline := time.Now().Add(30 * time.Second)
	for {
		r := s.call("observe: "+name, "substrate", http.MethodGet, s.cfg.SubstrateURL, "/twins/"+namespace+"/"+id, nil, Public)
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

func (s *Session) call(name, service, method, base, path string, body any, auth Auth) Response {
	s.t.Helper()
	u, err := url.Parse(base + path)
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
	if auth == Internal {
		req.Header.Set("X-Internal-Secret", s.cfg.InternalSecret) // never recorded
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
		Name:     name,
		Request:  recorder.Request{Method: method, Path: s.template(u.RequestURI()), Service: service, Headers: headers},
		Response: recorder.Response{Status: resp.StatusCode},
	}
	if b := rawJSON(raw); len(b) > 0 {
		step.Response.Body = json.RawMessage(s.template(string(b)))
	}
	if payload != nil {
		step.Request.Body = json.RawMessage(s.template(string(payload)))
	}
	s.rec.Steps = append(s.rec.Steps, step)
	return out
}

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
	path := filepath.Join(s.cfg.RecordTo, Service, FileName(s.rec.Scenario)+".json")
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

// NameUUID is Java's UUID.nameUUIDFromBytes: an MD5 name-based (version 3) UUID.
func NameUUID(name string) string {
	h := md5.Sum([]byte(name))
	h[6] = (h[6] & 0x0f) | 0x30
	h[8] = (h[8] & 0x3f) | 0x80
	return formatUUID(h)
}

// NewUUID is a random (version 4) UUID.
func NewUUID() string {
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

func rawJSON(b []byte) []byte {
	b = bytes.TrimSpace(b)
	if len(b) == 0 || json.Valid(b) {
		return b
	}
	q, _ := json.Marshal(string(b))
	return q
}

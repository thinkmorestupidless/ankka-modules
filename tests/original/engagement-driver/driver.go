// Package engagementdriver drives the original's casino-service and push-gateway at the routes its Go
// end-to-end suite never calls (spec 014 research R2 item 2, T050): the CRM configuration routes with
// their masking and the seed, the Customer.io webhook's refusals and its `send_message`,
// `target_offer` and refused `grant_bonus` paths, the daily deposit count reset, the backoffice agent's
// stream ticket and stream, the player's push stream and its frames, and the pod-local presence route.
// Each scenario of features/engagement/*.feature the suite does not reach is one recording; two
// recordings name a requirement no feature scenario states (Def.Unfeatured).
//
// What a frontend cannot do for itself goes in as `setup:` steps a replay does not compare: a
// verification link minted from the original's signing secret, an offer written to substrate as
// scenario 21's s21CreateOffer writes it, a daily deposit count written to the player twin where the
// deposit path writes it, and the webhook's signing key saved through the configuration route as the
// backoffice page saves it. The brand's stored CRM configuration is read and put back through
// substrate without a step (Session.quiet): what is stored carries the brand's credentials.
//
// A stream is one step whose answer is every frame read before the scenario stopped reading: a JSON
// array of {event, id, data} and {comment} objects, data parsed when it is JSON (Session.Open).
//
// What crm-stub received is read without a step (Session.CRM) to check the feature's expectations;
// original/crm-captures attaches it to the recordings after the run.
//
// Every token is the run's own and is written as its `{name}`. The webhook's signature and timestamp,
// the internal secret and the signing secret are sent and never written.
//
// The machinery in this file needs no running original, so `go test ./original/engagement-driver/...`
// checks it against a stub; the scenarios themselves are tagged `e2e`.
package engagementdriver

import (
	"bufio"
	"bytes"
	"context"
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
	"sync"
	"testing"
	"time"

	"github.com/eitheror-io/ankka-casino/tests/original/recorder"
)

// Config is where the original runs, who the run acts as, and where recordings go.
type Config struct {
	CasinoURL    string // the original's casino-service, e.g. http://localhost:8091
	KeycloakURL  string // the original's Keycloak, e.g. http://localhost:8180
	SubstrateURL string // the original's substrate, e.g. http://localhost:18080
	PushURL      string // the original's push-gateway, e.g. http://localhost:18096 (port-forwarded)
	CRMStubURL   string // crm-stub's Track port, e.g. http://localhost:18201; its log is read, never recorded
	Brand        string // the original's seeded brand, whose realm is casino-<brand>
	// SigningSecret is the original's EMAIL_TOKEN_SIGNING_SECRET, from which a verification link is
	// minted as the original mints it (EmailVerificationTokenStore). Never written.
	SigningSecret string
	// InternalSecret is casino-service's WALLET_SERVICE_INTERNAL_SECRET, which the reset route takes as
	// `X-Internal-Secret`. Never written.
	InternalSecret string
	OperatorToken  string // an operator holding brand_admin at the brand; written as {operatorToken}
	RecordTo       string // EITHEROR_RECORD_TO; empty runs without writing
	Commit         string // EITHEROR_ORIGINAL_COMMIT
	Client         *http.Client
	Now            func() time.Time
	// Patience is how long an `observe:` step or a repeated call waits for the original's reads to
	// catch up; 40 seconds when unset.
	Patience time.Duration
	// StreamPatience is how long a stream is read for a frame the scenario waits for; 25 seconds when
	// unset (the original's first heartbeat comes after 15).
	StreamPatience time.Duration
	// RecycleWait is how long a stream is held for the original to recycle it; 7 minutes when unset
	// (MAX_CONNECTION_AGE_MS 300000 × up to 1.19, swept every 30 seconds).
	RecycleWait time.Duration
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

// Session is one scenario: its own players, offers, messages, streams and tokens.
type Session struct {
	t        testing.TB
	cfg      Config
	rec      *recorder.Recording
	ids      map[string]string // name -> the value minted or captured for it this run
	order    []string
	cleanups []func()
	skipped  bool
	kept     bool // the brand's stored CRM configuration is put back after the scenario
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
	if cfg.StreamPatience == 0 {
		cfg.StreamPatience = 25 * time.Second
	}
	if cfg.RecycleWait == 0 {
		cfg.RecycleWait = 7 * time.Minute
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
			Service:     "engagement",
		},
	}
	// The run's own credential, so no request or answer carries it as it is.
	s.remember("operatorToken", cfg.OperatorToken)
	// The run's tag: in every address the scenario mints.
	s.remember("tag", "ed"+strings.ReplaceAll(newUUID(), "-", "")[:10])
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
// its timestamp (both differ on every run and are Customer.io's, which `engagement` checks in the
// port), the internal secret, and any credential given as it is.
var unrecorded = map[string]bool{"X-CIO-Signature": true, "X-CIO-Timestamp": true, "X-Internal-Secret": true, "Authorization": true}

// Casino calls casino-service: a step a replay sends to the port and compares.
func (s *Session) Casino(name, method, path string, body any, auth Auth) Response {
	return s.call(name, "casino", method, path, body, auth, nil)
}

// Push calls push-gateway: a step a replay sends to the port and compares.
func (s *Session) Push(name, method, path string, auth Auth) Response {
	return s.call(name, "push", method, path, nil, auth, nil)
}

// Internal calls one of casino-service's internal routes with its internal secret, never written.
func (s *Session) Internal(name, method, path string, body any) Response {
	return s.call(name, "casino", method, path, body, NoAuth, map[string]string{"X-Internal-Secret": s.cfg.InternalSecret})
}

// Setup calls casino-service, Keycloak or substrate for what the frontend cannot do; recorded as a
// `setup:` step a replay skips.
func (s *Session) Setup(name, service, method, path string, body any, auth Auth) Response {
	return s.call("setup: "+name, service, method, path, body, auth, nil)
}

// Signature is how a webhook request is signed.
type Signature int

const (
	Signed    Signature = iota // under the brand's key, as Customer.io signs
	Unsigned                   // no X-CIO-Signature
	Malformed                  // X-CIO-Signature that is not a hex HMAC
	WrongKey                   // a hex HMAC under another key
)

// Webhook posts body to the brand's Customer.io webhook as Customer.io does: `X-CIO-Timestamp` and
// `X-CIO-Signature: hex(HMAC-SHA256(key, "v0:<timestamp>:<body>"))` (CustomerIOWebhookRoutes.
// verifySignature), or the signature `sig` names. Neither header is written: the step's name says how
// it was signed, and a replay signs under the port's key.
func (s *Session) Webhook(name, key string, sig Signature, body map[string]any) Response {
	raw, _ := json.Marshal(body)
	ts := fmt.Sprintf("%d", s.cfg.Now().Unix())
	headers := map[string]string{"X-CIO-Timestamp": ts}
	switch sig {
	case Signed:
		headers["X-CIO-Signature"] = CIOSignature(key, ts, raw)
	case Malformed:
		headers["X-CIO-Signature"] = "not-a-signature"
	case WrongKey:
		headers["X-CIO-Signature"] = CIOSignature(OtherKey, ts, raw)
	}
	return s.callRaw(name, "casino", http.MethodPost, "/v1/casino/customerio/webhook/"+s.Brand(), raw, NoAuth, headers)
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
// only its last answer: the original's lists and offer lookups follow its writes by moments. A call
// that never settles is kept as answered, for the scenario's own check to report.
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
	case "push":
		return s.cfg.PushURL
	case "crm-stub":
		return s.cfg.CRMStubURL
	default:
		return s.cfg.CasinoURL
	}
}

func (s *Session) target(name, service, path string) *url.URL {
	s.t.Helper()
	u, err := url.Parse(strings.TrimSuffix(s.base(service), "/") + path)
	if err != nil {
		s.t.Fatalf("%s: %v", name, err)
	}
	if !recorder.Local(u.Host) {
		s.t.Fatalf("refusing %s: nothing may leave the machine while the original is recorded", u.Host)
	}
	return u
}

func (s *Session) call(name, service, method, path string, body any, auth Auth, extra map[string]string) Response {
	var payload []byte
	if body != nil {
		payload, _ = json.Marshal(body)
	}
	return s.callRaw(name, service, method, path, payload, auth, extra)
}

// send makes one request and reads its answer, with no step.
func (s *Session) send(name, service, method, path string, payload []byte, auth Auth, extra map[string]string) (Response, *url.URL, map[string]string) {
	s.t.Helper()
	u := s.target(name, service, path)
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
	return out, u, headers
}

func (s *Session) callRaw(name, service, method, path string, payload []byte, auth Auth, extra map[string]string) Response {
	s.t.Helper()
	out, u, headers := s.send(name, service, method, path, payload, auth, extra)
	step := recorder.Step{
		Name: name,
		Request: recorder.Request{
			Method:  method,
			Path:    s.template(u.RequestURI()),
			Service: service,
			Headers: headers,
		},
		Response: recorder.Response{Status: out.Status, Body: rawJSON(out.Raw)},
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

// quiet calls substrate with no step: only for the brand's stored CRM configuration, whose reads and
// writes carry the brand's credentials, so a recording never holds them.
func (s *Session) quiet(method, path string, body any) Response {
	s.t.Helper()
	var payload []byte
	if body != nil {
		payload, _ = json.Marshal(body)
	}
	out, _, _ := s.send("the brand's stored CRM configuration", "substrate", method, path, payload, NoAuth, nil)
	return out
}

// Frame is one server-sent event as the original wrote it: its event, id and data (parsed when it is
// JSON), or a comment line (`: heartbeat <instant>`, `: ping`).
type Frame struct {
	Event   string          `json:"event,omitempty"`
	ID      string          `json:"id,omitempty"`
	Data    json.RawMessage `json:"data,omitempty"`
	Comment string          `json:"comment,omitempty"`
}

// Field reads a field of the frame's data as a string, or "".
func (f Frame) Field(name string) string {
	m := map[string]any{}
	_ = json.Unmarshal(f.Data, &m)
	return str(m, name)
}

// Stream is an open server-sent event stream: one step, opened where the scenario opened it, whose
// answer is the frames read until it is closed, as the original wrote them (an answer is never
// templated, as no step's is).
type Stream struct {
	s      *Session
	step   int
	status int
	raw    []byte
	cancel context.CancelFunc
	done   chan struct{}
	mu     sync.Mutex
	frames []Frame
	ended  bool
	closed bool
}

// Open opens a stream as a browser's EventSource does (`Accept: text/event-stream`, the token as a
// bearer when it has one) and reads it in the background. Its step is recorded at this point of the
// scenario; its answer is filled in when it is closed, at the latest after the scenario.
func (s *Session) Open(name, service, path string, auth Auth) *Stream {
	s.t.Helper()
	u := s.target(name, service, path)
	ctx, cancel := context.WithCancel(context.Background())
	req, _ := http.NewRequestWithContext(ctx, http.MethodGet, u.String(), nil)
	req.Header.Set("Accept", "text/event-stream")
	headers := map[string]string{"Accept": "text/event-stream"}
	if auth != NoAuth {
		token := s.ids[string(auth)]
		if token == "" {
			cancel()
			s.t.Fatalf("%s: no %s for this run", name, auth)
		}
		req.Header.Set("Authorization", "Bearer "+token)
		headers["Authorization"] = "Bearer {" + string(auth) + "}"
	}
	// No client timeout: the stream is held until the scenario closes it.
	resp, err := (&http.Client{Transport: s.cfg.Client.Transport}).Do(req)
	if err != nil {
		cancel()
		s.t.Fatalf("%s: %v", name, err)
	}
	st := &Stream{s: s, step: len(s.rec.Steps), status: resp.StatusCode, cancel: cancel, done: make(chan struct{})}
	s.rec.Steps = append(s.rec.Steps, recorder.Step{
		Name:     name,
		Request:  recorder.Request{Method: http.MethodGet, Path: s.template(u.RequestURI()), Service: service, Headers: headers},
		Response: recorder.Response{Status: resp.StatusCode},
	})
	s.Cleanup(func() { st.Close() })
	if resp.StatusCode != http.StatusOK {
		st.raw, _ = io.ReadAll(resp.Body)
		_ = resp.Body.Close()
		st.ended = true
		close(st.done)
		return st
	}
	go st.read(resp.Body)
	return st
}

func (st *Stream) read(body io.ReadCloser) {
	defer close(st.done)
	defer body.Close()
	r := bufio.NewReader(body)
	var cur Frame
	var data []string
	emit := func() {
		if len(data) > 0 {
			d := strings.Join(data, "\n")
			if json.Valid([]byte(d)) {
				cur.Data = json.RawMessage(d)
			} else {
				q, _ := json.Marshal(d)
				cur.Data = q
			}
		}
		if cur.Event != "" || cur.ID != "" || cur.Data != nil || cur.Comment != "" {
			st.mu.Lock()
			st.frames = append(st.frames, cur)
			st.mu.Unlock()
		}
		cur, data = Frame{}, nil
	}
	for {
		line, err := r.ReadString('\n')
		line = strings.TrimRight(line, "\r\n")
		switch {
		case err != nil && line == "":
		case line == "":
			emit()
		case strings.HasPrefix(line, ":"):
			cur.Comment = strings.TrimSpace(strings.TrimPrefix(line, ":"))
		case strings.HasPrefix(line, "event:"):
			cur.Event = strings.TrimSpace(strings.TrimPrefix(line, "event:"))
		case strings.HasPrefix(line, "id:"):
			cur.ID = strings.TrimSpace(strings.TrimPrefix(line, "id:"))
		case strings.HasPrefix(line, "data:"):
			data = append(data, strings.TrimPrefix(strings.TrimPrefix(line, "data:"), " "))
		}
		if err != nil {
			emit()
			st.mu.Lock()
			st.ended = true
			st.mu.Unlock()
			return
		}
	}
}

// Status is the stream's answer's status.
func (st *Stream) Status() int { return st.status }

// Response is the stream's answer so far: its status, and the body of a refusal.
func (st *Stream) Response() Response {
	out := Response{Status: st.status, Raw: st.raw, Body: map[string]any{}}
	_ = json.Unmarshal(st.raw, &out.Body)
	return out
}

// Frames is a copy of every frame read so far.
func (st *Stream) Frames() []Frame {
	st.mu.Lock()
	defer st.mu.Unlock()
	return append([]Frame(nil), st.frames...)
}

// Ended tells whether the original ended the stream.
func (st *Stream) Ended() bool {
	st.mu.Lock()
	defer st.mu.Unlock()
	return st.ended
}

// Until waits up to `within` for `cond` to hold of the frames read so far, and fails the scenario,
// naming `what`, when it never does or the stream ends first.
func (st *Stream) Until(what string, within time.Duration, cond func([]Frame) bool) bool {
	deadline := time.Now().Add(within)
	for {
		frames, ended := st.Frames(), st.Ended()
		if cond(frames) {
			return true
		}
		if ended || time.Now().After(deadline) {
			st.s.t.Errorf("%s: %s not read within %s (ended %v): %d frames", st.s.rec.Scenario, what, within, ended, len(frames))
			return false
		}
		time.Sleep(50 * time.Millisecond)
	}
}

// WaitEnd waits up to `within` for the original to end the stream.
func (st *Stream) WaitEnd(within time.Duration) bool {
	select {
	case <-st.done:
		return true
	case <-time.After(within):
		return false
	}
}

// Close stops reading and writes the frames read as the step's answer. Closing twice changes nothing.
func (st *Stream) Close() {
	if st.closed {
		return
	}
	st.closed = true
	st.cancel()
	<-st.done
	step := &st.s.rec.Steps[st.step]
	if st.status != http.StatusOK {
		step.Response.Body = rawJSON(st.raw)
		return
	}
	frames := st.Frames()
	if frames == nil {
		frames = []Frame{}
	}
	b, _ := json.Marshal(frames)
	step.Response.Body = json.RawMessage(b)
}

// Kind is a condition that holds once a frame of event `event` has been read whose data field `field`
// is `value` (any value when `value` is "").
func Kind(event, field, value string) func([]Frame) bool {
	return func(fs []Frame) bool { return FirstOf(fs, event, field, value) != nil }
}

// FirstOf is the first frame of event `event` whose data field `field` is `value`, or nil.
func FirstOf(fs []Frame, event, field, value string) *Frame {
	for i := range fs {
		if fs[i].Event == event && (field == "" || value == "" || fs[i].Field(field) == value) {
			return &fs[i]
		}
	}
	return nil
}

// CRMCall is one call crm-stub received: the host (`track` or `app`), the path and the body.
type CRMCall struct {
	Host string          `json:"host"`
	Path string          `json:"path"`
	Body json.RawMessage `json:"body"`
}

func (c CRMCall) body() map[string]any {
	m := map[string]any{}
	_ = json.Unmarshal(c.Body, &m)
	return m
}

// Person is the call's person: Track's `identifiers.id` (or `.email`), or App's `identifiers.id` or `to`.
func (c CRMCall) Person() string {
	m := c.body()
	if ids, ok := m["identifiers"].(map[string]any); ok {
		if v := str(ids, "id"); v != "" {
			return v
		}
		if v := str(ids, "email"); v != "" {
			return v
		}
	}
	return str(m, "to")
}

// Action is a Track call's `action` (`identify`, `event`, `delete`), or "send" for App's.
func (c CRMCall) Action() string {
	if c.Host == "app" {
		return "send"
	}
	return str(c.body(), "action")
}

// Attr is a Track call's attribute, as a string whatever its JSON type.
func (c CRMCall) Attr(name string) string {
	a, _ := c.body()["attributes"].(map[string]any)
	return str(a, name)
}

// CRM is every call crm-stub has received, oldest first, read with no step: the stub's log holds its
// own credentials in every call's headers, and original/crm-captures attaches the calls to the
// recordings after the run without them. Nil, with the scenario failed, when the stub cannot be read.
func (s *Session) CRM() []CRMCall {
	s.t.Helper()
	if s.cfg.CRMStubURL == "" {
		s.t.Errorf("%s: no -crm-stub-url: the scenario's expectations are on what the CRM received", s.rec.Scenario)
		return nil
	}
	u := s.target("crm-stub's log", "crm-stub", "/__received")
	resp, err := s.cfg.Client.Get(u.String())
	if err != nil {
		s.t.Errorf("%s: crm-stub's log: %v", s.rec.Scenario, err)
		return nil
	}
	defer resp.Body.Close()
	var calls []CRMCall
	if err := json.NewDecoder(resp.Body).Decode(&calls); err != nil {
		s.t.Errorf("%s: crm-stub's log: %v", s.rec.Scenario, err)
		return nil
	}
	if calls == nil {
		calls = []CRMCall{} // an empty log, which is not a failure to read it
	}
	return calls
}

// CRMFor is the calls after the first `mark` (a length of an earlier CRM()) made for `person`.
func (s *Session) CRMFor(mark int, person string) []CRMCall {
	all := s.CRM()
	if mark > len(all) {
		mark = len(all)
	}
	var out []CRMCall
	for _, c := range all[mark:] {
		if c.Person() == person {
			out = append(out, c)
		}
	}
	return out
}

// CRMSettled waits until crm-stub has received nothing new for `person` for two seconds (the
// original's dispatch of a sign-in or a registration runs behind its consumers), within the run's
// patience, and returns the length of its log then, as a mark for CRMFor.
func (s *Session) CRMSettled(person string) int {
	deadline := time.Now().Add(s.cfg.Patience)
	last, since := -1, time.Now()
	for {
		all := s.CRM()
		n := 0
		for _, c := range all {
			if c.Person() == person {
				n++
			}
		}
		if n != last {
			last, since = n, time.Now()
		}
		if time.Since(since) >= 2*time.Second || time.Now().After(deadline) || all == nil {
			return len(all)
		}
		time.Sleep(250 * time.Millisecond)
	}
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

// NameUUID is Java's UUID.nameUUIDFromBytes: MD5, version 3. The original keys the brand's CRM
// configuration twins by it (SubstrateTwinClient.resolvedEntityId, getHoldingCrmConfig).
func NameUUID(name string) string {
	h := md5.Sum([]byte(name)) //nolint:gosec
	h[6] = (h[6] & 0x0f) | 0x30
	h[8] = (h[8] & 0x3f) | 0x80
	return fmt.Sprintf("%x-%x-%x-%x-%x", h[0:4], h[4:6], h[6:8], h[8:10], h[10:16])
}

// DepositDay is the original's deposit day of t (DepositDay.of): the UTC date of t less three hours.
func DepositDay(t time.Time) string {
	return t.UTC().Add(-3 * time.Hour).Format("2006-01-02")
}

// Mask is a secret as the original's configuration route shows it (CustomerIOConfigRoutes.maskSecret):
// its first three characters and an asterisk for each of the rest.
func Mask(v string) string {
	if len(v) <= 3 {
		return v
	}
	return v[:3] + strings.Repeat("*", len(v)-3)
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

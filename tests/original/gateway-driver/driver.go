// Package gatewaydriver drives the original's psp-gateway at its own routes, with psp-stub as
// every payment provider, and records each scenario as one recording (spec 005 research R16;
// specs/005-psp-gateway/contracts/recording.md).
//
// The original's end-to-end suite reaches the gateway only through DreamzPay (scenarios 13 and 14),
// so the other eight providers, the payouts and the outcome rules would rest on its unit suites,
// which mock the providers and record nothing. This driver calls the gateway as merchant-service and
// casino-service do: the deposit initiation with the terminal's properties (the stub's credentials,
// as the original requires), the payout routes, the status read and the re-drive. A receiver in
// this process stands in for both callers: it serves the terminal properties the gateway reads at
// webhook time and logs every outcome the gateway delivers, answering as the scenario scripted.
//
// Steps:
//
//   - a call to the gateway is recorded as it was made, and its answer is what a replay compares;
//   - a webhook is sent by psp-stub on the driver's word (`POST /__webhook`), and the step's answer is
//     the gateway's; a webhook the stub cannot make (a bad signature, an unknown reference, a tampered
//     amount) the driver posts itself, named `webhook (forged): …`;
//   - psp-stub's `/__control` and the receiver's arrangement are `setup:` steps; psp-stub's
//     `/__received` (the provider call) and the receiver's log (the outcome call) are `observe:` steps,
//     whose answers are templated and filtered to the scenario (see Session.Observe);
//   - the re-drive is `time: …`, the timer a replay advances.
//
// The machinery in this file needs no running original, so `go test ./original/gateway-driver/...`
// checks it against fakes; the scenarios themselves run under the `e2e` tag.
package gatewaydriver

import (
	"bytes"
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

// Config is where the original, psp-stub and the receiver run, and where recordings go.
type Config struct {
	GatewayURL     string // the original's psp-gateway as the driver reaches it, e.g. its node port
	StubHost       string // psp-stub as the driver reaches it, without a port: http://localhost
	ReceiverURL    string // the receiver as the gateway reaches it; the callback and the gateway's two service URLs
	ReceiverLocal  string // the receiver as the driver reaches it, e.g. http://localhost:18110
	InternalSecret string // the gateway's MERCHANT_SERVICE_INTERNAL_SECRET, for the re-drive; never recorded
	RecordTo       string // EITHEROR_RECORD_TO; empty runs without writing
	Commit         string // EITHEROR_ORIGINAL_COMMIT
	Client         *http.Client
	Now            func() time.Time
	// Settle is how long a step that expects no outcome waits before reading the receiver's log.
	Settle time.Duration
	// Wait bounds how long an observation polls for what it expects.
	Wait time.Duration
	// Attempt is past the original's 10 s per attempt (callbackTimeout): how long a step waits for an
	// attempt it saw arrive to time out, so the delivery has ended before the next step.
	Attempt time.Duration
}

// Response is what was answered to one step.
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

// Session is one scenario on its own fresh sub-brand, terminal and ids.
type Session struct {
	t     testing.TB
	cfg   Config
	rec   *recorder.Recording
	ids   map[string]string // name -> the value minted or captured for it this run
	order []string
	// seen is, per provider, how many requests psp-stub had received when the scenario last read it,
	// so each observation records only what this scenario caused.
	seen map[string]int
	// facts are values read off psp-stub's record that a forged webhook needs (the Cozeni token, the
	// Morphosis notify token and QVEI id). They are not templated: they differ on every run and sit
	// only in steps marked `vary` or named `(forged)`.
	facts map[string]string
}

func (s *Session) fact(name string) string { return s.facts[name] }

// Scenario runs body on fresh ids and writes one recording named exactly `name`.
func Scenario(t testing.TB, cfg Config, name, requirement string, body func(s *Session)) {
	t.Helper()
	if cfg.Client == nil {
		// Longer than the original's outcome budget (15 s), which a webhook's answer waits out.
		cfg.Client = &http.Client{Timeout: 45 * time.Second}
	}
	if cfg.Now == nil {
		cfg.Now = time.Now
	}
	if cfg.Settle == 0 {
		cfg.Settle = time.Second
	}
	if cfg.Wait == 0 {
		cfg.Wait = 30 * time.Second
	}
	if cfg.Attempt == 0 {
		cfg.Attempt = 12 * time.Second
	}
	s := &Session{
		t:     t,
		cfg:   cfg,
		ids:   map[string]string{},
		seen:  map[string]int{},
		facts: map[string]string{},
		rec: &recorder.Recording{
			Scenario:    name,
			Requirement: requirement,
			RecordedAt:  time.Now().UTC(),
			Original:    map[string]string{"commit": cfg.Commit},
			Runs:        1,
			Service:     "psp-gateway",
		},
	}
	defer s.write()
	body(s)
}

// ID mints a fresh value for a name the scenario uses (a sub-brand, a payment, a request) and records
// it as `{name}` in every request, so a replay mints its own.
func (s *Session) ID(name string) string {
	if v, ok := s.ids[name]; ok {
		return v
	}
	v := name + "-" + newUUID()[:8]
	s.remember(name, v)
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

func (s *Session) remember(name, value string) {
	if _, ok := s.ids[name]; !ok {
		s.order = append(s.order, name)
	}
	s.ids[name] = value
}

// Want records a mismatch between the answer's status and the one the feature expects as a failure
// of the scenario: the recording is still written, and suite-result.json shows it.
func (s *Session) Want(r Response, status int) Response {
	if r.Status != status {
		s.t.Errorf("%s: answered %d (want %d): %s", s.rec.Scenario, r.Status, status, string(r.Raw))
	}
	return r
}

// WantRelayed records as a failure a webhook psp-stub sent (`Webhook`) that the gateway did not answer
// `status`: the stub answers 200 and carries the gateway's status in its body.
func (s *Session) WantRelayed(r Response, status int) Response {
	if got, _ := r.Body["status"].(float64); r.Status != http.StatusOK || int(got) != status {
		s.t.Errorf("%s: the gateway answered %v (want %d): %s", s.rec.Scenario, r.Body["status"], status, string(r.Raw))
	}
	return r
}

// Body is a request body sent exactly as given, with its content type: a form, or JSON whose bytes a
// signature covers.
type Body struct {
	ContentType string
	Bytes       []byte
}

// Form is a form-encoded body.
func Form(v url.Values) Body {
	return Body{ContentType: "application/x-www-form-urlencoded", Bytes: []byte(v.Encode())}
}

// JSON is a JSON body whose bytes are fixed now, for a signature over them.
func JSON(v any) Body {
	b, _ := json.Marshal(v)
	return Body{ContentType: "application/json", Bytes: b}
}

// target is one of the three things a step calls.
type target struct {
	service string // the recording's service: psp-gateway, psp-stub, receiver
	base    string
}

func (s *Session) gateway() target  { return target{"psp-gateway", s.cfg.GatewayURL} }
func (s *Session) receiver() target { return target{"receiver", s.cfg.ReceiverLocal} }
func (s *Session) stub(p Provider) target {
	return target{"psp-stub", strings.TrimRight(s.cfg.StubHost, "/") + ":" + p.Port}
}

// call makes one request and records it as a step. `headers` are sent as given and recorded less
// any secret; `secret` headers are sent and never recorded.
func (s *Session) call(name string, to target, method, path string, body any, headers, secret map[string]string) Response {
	s.t.Helper()
	u, err := url.Parse(strings.TrimRight(to.base, "/") + path)
	if err != nil {
		s.t.Fatalf("%s: %v", name, err)
	}
	if !recorder.Local(u.Host) {
		s.t.Fatalf("refusing %s: nothing may leave the machine while the original is recorded", u.Host)
	}
	var payload []byte
	contentType := ""
	switch b := body.(type) {
	case nil:
	case Body:
		payload, contentType = b.Bytes, b.ContentType
	default:
		payload, _ = json.Marshal(b)
		contentType = "application/json"
	}
	req, _ := http.NewRequest(method, u.String(), bytes.NewReader(payload))
	recorded := map[string]string{}
	if payload != nil {
		req.Header.Set("Content-Type", contentType)
		recorded["Content-Type"] = contentType
	}
	for k, v := range headers {
		req.Header.Set(k, v)
		if !secretHeader(k) {
			recorded[k] = s.template(v)
		}
	}
	for k, v := range secret {
		req.Header.Set(k, v)
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
			Service: to.service,
			Headers: recorded,
		},
		Response: recorder.Response{Status: resp.StatusCode, Body: rawJSON(raw)},
	}
	if len(recorded) == 0 {
		step.Request.Headers = nil
	}
	if payload != nil {
		step.Request.Body = s.templateJSON(Redact(rawJSON(payload)))
	}
	s.rec.Steps = append(s.rec.Steps, step)
	return out
}

// Gateway calls the original's psp-gateway: a behaviour a replay compares.
func (s *Session) Gateway(name, method, path string, body any, headers map[string]string) Response {
	return s.call(name, s.gateway(), method, path, body, headers, nil)
}

// Setup arranges psp-stub or the receiver; recorded as a `setup:` step a replay sends without comparing.
func (s *Session) Setup(name string, to target, method, path string, body any) Response {
	return s.call("setup: "+name, to, method, path, body, nil, nil)
}

// Observe reads psp-stub's or the receiver's record, repeating the read until `until` holds of what
// `keep` makes of it, within cfg.Wait, and records only the read that held, as an `observe:` step.
// `keep` cuts the answer to what this scenario caused; the step's answer is that cut, templated
// (`{payment}`, `{reference}`) so a replay can set its own run's record beside it, with the fields that
// differ on every run (timestamps, salts, tokens, signatures) marked `vary`.
func (s *Session) Observe(name string, to target, path string, keep func([]byte) any, until func(any) bool) any {
	s.t.Helper()
	deadline := time.Now().Add(s.cfg.Wait)
	for {
		r := s.call("observe: "+name, to, http.MethodGet, path, nil, nil, nil)
		kept := keep(r.Raw)
		if until(kept) || time.Now().After(deadline) {
			if !until(kept) {
				s.t.Errorf("%s: %s not observed within %v: %s", s.rec.Scenario, name, s.cfg.Wait, string(r.Raw))
			}
			last := &s.rec.Steps[len(s.rec.Steps)-1]
			b := Redact(marshal(kept))
			last.Response.Body = s.templateJSON(b)
			last.Vary = volatile(last.Response.Body)
			return kept
		}
		s.rec.Steps = s.rec.Steps[:len(s.rec.Steps)-1]
		time.Sleep(250 * time.Millisecond)
	}
}

// Time is a step a replay stands in for by advancing its clock: the original's re-drive CronJob.
func (s *Session) Time(name, method, path string, secret map[string]string) Response {
	return s.call("time: "+name, s.gateway(), method, path, nil, nil, secret)
}

// template replaces this run's minted and captured values with their `{name}` placeholders, longest
// first. A value shorter than eight characters is left: a short one (a stub's sequential id) would
// replace unrelated text.
func (s *Session) template(text string) string {
	names := append([]string(nil), s.order...)
	sort.SliceStable(names, func(i, j int) bool { return len(s.ids[names[i]]) > len(s.ids[names[j]]) })
	for _, n := range names {
		if v := s.ids[n]; len(v) >= 8 {
			text = strings.ReplaceAll(text, v, "{"+n+"}")
		}
	}
	return text
}

// templateJSON templates a JSON body's strings only: a captured value can also stand as a number in a
// body (Cozeni's `requestAmount` is the `cryptoAmount` it answered), and templating the text would
// leave a bare placeholder there, which is not JSON. A body that is not JSON is templated as text.
func (s *Session) templateJSON(b json.RawMessage) json.RawMessage {
	dec := json.NewDecoder(bytes.NewReader(b))
	dec.UseNumber()
	var v any
	if len(b) == 0 || dec.Decode(&v) != nil {
		return json.RawMessage(s.template(string(b)))
	}
	var walk func(any) any
	walk = func(v any) any {
		switch x := v.(type) {
		case string:
			return s.template(x)
		case map[string]any:
			out := make(map[string]any, len(x))
			for k, e := range x {
				out[s.template(k)] = walk(e)
			}
			return out
		case []any:
			for i, e := range x {
				x[i] = walk(e)
			}
			return x
		}
		return v
	}
	return marshal(walk(v))
}

func (s *Session) write() {
	if s.cfg.RecordTo == "" {
		return
	}
	path := filepath.Join(s.cfg.RecordTo, "psp-gateway", FileName(s.rec.Scenario)+".json")
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		s.t.Errorf("recording %s: %v", s.rec.Scenario, err)
		return
	}
	var buf bytes.Buffer
	enc := json.NewEncoder(&buf)
	enc.SetEscapeHTML(false)
	enc.SetIndent("", "  ")
	if err := enc.Encode(s.rec); err != nil {
		s.t.Errorf("recording %s: %v", s.rec.Scenario, err)
		return
	}
	if err := os.WriteFile(path, buf.Bytes(), 0o644); err != nil {
		s.t.Errorf("recording %s: %v", s.rec.Scenario, err)
	}
}

// FileName is a scenario's file name: its words, with anything a path cannot hold made a dash. The
// provider suffix (`…/cozeni`) becomes `…-cozeni`; merge names the committed file by the scenario.
func FileName(scenario string) string {
	return strings.Trim(pathUnsafe.ReplaceAllString(scenario, "-"), "-")
}

var pathUnsafe = regexp.MustCompile(`[^A-Za-z0-9_.-]+`)

// Secrets are the fields and headers a recording never carries. The values are psp-stub's own test
// credentials, but a recording is committed, and the port reads its credentials from merchant, not
// from the initiation (contracts/merchant.md §1), so nothing a replay needs is lost.
var secretFields = map[string]bool{
	"secureKey": true, "signKey": true, "secretKey": true, "apiKey": true, "privateKey": true,
	"signingSecret": true, "signingKey": true, "passphrase": true, "password": true, "pass": true,
	"accessToken": true, "securityToken": true, "withdrawalApiKey": true, "account_password": true,
}

func secretHeader(k string) bool {
	switch http.CanonicalHeaderKey(k) {
	case "Authorization", "Apikey", "X-Internal-Secret", "Cookie":
		return true
	}
	return false
}

const redacted = "<redacted>"

// Redact replaces every secret field's value in a JSON body, in a form-encoded body (recorded as a
// JSON string) and in every headers object (psp-stub's and the receiver's records) by "<redacted>".
func Redact(b json.RawMessage) json.RawMessage {
	if len(b) == 0 {
		return b
	}
	var v any
	if json.Unmarshal(b, &v) != nil {
		return b
	}
	return marshal(redactValue("", v))
}

// marshal is json.Marshal without HTML escaping, so "<redacted>" reads as written.
func marshal(v any) []byte {
	var buf bytes.Buffer
	enc := json.NewEncoder(&buf)
	enc.SetEscapeHTML(false)
	_ = enc.Encode(v)
	return bytes.TrimSpace(buf.Bytes())
}

func redactValue(key string, v any) any {
	switch x := v.(type) {
	case map[string]any:
		for k, e := range x {
			switch {
			case secretFields[k] && e != nil && e != "":
				x[k] = redacted
			case strings.EqualFold(key, "headers") || strings.EqualFold(key, "header"):
				if secretHeader(k) {
					x[k] = redacted
				} else {
					x[k] = redactValue(k, e)
				}
			default:
				x[k] = redactValue(k, e)
			}
		}
		return x
	case []any:
		for i, e := range x {
			x[i] = redactValue(key, e)
		}
		return x
	case string:
		if form, ok := asForm(x); ok {
			changed := false
			for k := range form {
				if secretFields[k] {
					form.Set(k, redacted)
					changed = true
				}
			}
			if changed {
				return form.Encode()
			}
		}
		return x
	default:
		return v
	}
}

// asForm reads a string as a form-encoded body: two or more fields, each a key and a value.
func asForm(s string) (url.Values, bool) {
	if !strings.Contains(s, "=") || !strings.Contains(s, "&") || strings.ContainsAny(s, " {}<>\n") {
		return nil, false
	}
	v, err := url.ParseQuery(s)
	if err != nil || len(v) < 2 {
		return nil, false
	}
	return v, true
}

// volatileKeys are the fields of a provider call or an outcome call that differ on every run however
// the ids are templated: timestamps, the Cozeni salt and the token over it, the Morphosis token, and
// every signature (contracts/recording.md, *What `vary` covers*).
var volatileKeys = map[string]bool{
	"at": true, "created": true, "ik_inv_crt": true, "validUntil": true, "X-Bouncemoney-Timestamp": true,
	"X-Psp-Timestamp": true, "requestSalt": true, "X-Cozeni-Txnauthz": true, "txnauthz": true,
	"notification_url": true, "ik_sign": true, "checksum": true, "hash": true, "Signature": true,
	"X-Bouncemoney-Signature": true, "X-Psp-Signature": true, "account_sha": true, "resp_sha": true,
}

// volatile lists the `vary` paths of an observed answer: each volatileKeys field, wherever it sits.
func volatile(b json.RawMessage) map[string]string {
	var v any
	if json.Unmarshal(b, &v) != nil {
		return nil
	}
	out := map[string]string{}
	var walk func(path string, v any)
	walk = func(path string, v any) {
		switch x := v.(type) {
		case map[string]any:
			for k, e := range x {
				if volatileKeys[k] {
					out[path+"."+k] = "differs on every run (contracts/recording.md)"
					continue
				}
				walk(path+"."+k, e)
			}
		case []any:
			for i, e := range x {
				walk(fmt.Sprintf("%s[%d]", path, i), e)
			}
		}
	}
	walk("$", v)
	if len(out) == 0 {
		return nil
	}
	return out
}

func newUUID() string {
	var b [16]byte
	_, _ = rand.Read(b[:])
	b[6] = (b[6] & 0x0f) | 0x40
	b[8] = (b[8] & 0x3f) | 0x80
	h := hex.EncodeToString(b[:])
	return h[0:8] + "-" + h[8:12] + "-" + h[12:16] + "-" + h[16:20] + "-" + h[20:32]
}

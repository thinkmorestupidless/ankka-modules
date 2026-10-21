// Package recorder records what the original answered to its own end-to-end suite (spec 001
// FR-030, contracts/recording.md). Installed as http.DefaultTransport by the suite's record_test.go
// when EITHEROR_RECORD_TO is set, it writes one file per test function: every request the test made
// through the default client, as a step. It refuses any request to a host that is not this machine,
// so a recording run cannot reach a real provider (FR-029).
package recorder

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"runtime"
	"sort"
	"strings"
	"sync"
	"time"
)

// Request and Response are a step's halves, as the recording format names them.
type Request struct {
	Method  string            `json:"method"`
	Path    string            `json:"path"`
	Service string            `json:"service"`
	Headers map[string]string `json:"headers,omitempty"`
	Body    json.RawMessage   `json:"body,omitempty"`
}

type Response struct {
	Status int             `json:"status"`
	Body   json.RawMessage `json:"body,omitempty"`
}

// Vary is this step's answer's fields that differed between the two runs: checked for presence only.
type Step struct {
	Name     string            `json:"name"`
	Request  Request           `json:"request"`
	Response Response          `json:"response"`
	Capture  map[string]string `json:"capture,omitempty"`
	Vary     map[string]string `json:"vary,omitempty"`
}

// Recording is one scenario; the merge program turns two runs of it into the committed file.
type Recording struct {
	Scenario    string            `json:"scenario"`
	Requirement string            `json:"requirement,omitempty"`
	RecordedAt  time.Time         `json:"recorded_at"`
	Original    map[string]string `json:"original"`
	Runs        int               `json:"runs"`
	Service     string            `json:"service"`
	Steps       []Step            `json:"steps"`
	// Vary is the recording-wide form recordings made before 2026-10-18 carry: a path there is
	// presence-only in every step, which is why a step now keeps its own (Step.Vary).
	Vary map[string]string `json:"vary,omitempty"`
}

// Services maps a base URL the suite is configured with to the rebuilt service a request to it
// establishes requirements for. Built from the suite's own EITHEROR_*_URL variables.
type Services map[string]string

// FromEnvironment reads the suite's URL variables, with the defaults its suite_test.go documents.
func FromEnvironment(getenv func(string) string) Services {
	pairs := []struct{ env, def, service string }{
		{"EITHEROR_WALLET_URL", "", "wallet"},
		{"EITHEROR_CASINO_URL", "http://localhost:8083", "players"},
		{"EITHEROR_MERCHANT_URL", "http://localhost:8086", "merchant"},
		{"EITHEROR_PSP_GATEWAY_URL", "http://localhost:8087", "psp-gateway"},
		{"EITHEROR_ST8_ADAPTER_URL", "http://localhost:8095", "game-provider"},
		{"EITHEROR_FAKE_ST8_URL", "", "game-provider"},
		{"EITHEROR_INGEST_URL", "http://localhost:8080", "ingest"},
		{"EITHEROR_SUBSTRATE_URL", "http://localhost:9000", "substrate"},
		{"EITHEROR_MIMIR_URL", "http://localhost:8081", "mimir"},
		{"EITHEROR_KEYCLOAK_URL", "http://localhost:8180", "realm"},
	}
	s := Services{}
	for _, p := range pairs {
		v := getenv(p.env)
		if v == "" {
			v = p.def
		}
		if u, err := url.Parse(v); err == nil && u.Host != "" {
			s[u.Host] = p.service
		}
	}
	return s
}

// Local is whether a host is this machine: loopback, a cluster-local name, or a stub's compose name.
func Local(host string) bool {
	h := host
	if hh, _, err := net.SplitHostPort(host); err == nil {
		h = hh
	}
	if h == "localhost" || strings.HasSuffix(h, ".localhost") || strings.HasSuffix(h, ".svc.cluster.local") ||
		strings.HasSuffix(h, ".local") || strings.HasSuffix(h, "-stub") {
		return true
	}
	if ip := net.ParseIP(h); ip != nil {
		return ip.IsLoopback() || ip.IsPrivate()
	}
	return false
}

// Transport records every exchange and refuses any that would leave the machine.
type Transport struct {
	Next     http.RoundTripper
	Dir      string
	Services Services
	Original map[string]string
	Package  string // the test package's import path prefix, to find the test function on the stack

	mu         sync.Mutex
	recordings map[string]*Recording
}

// New makes a transport writing under dir.
func New(next http.RoundTripper, dir string, services Services, original map[string]string, pkg string) *Transport {
	return &Transport{Next: next, Dir: dir, Services: services, Original: original, Package: pkg, recordings: map[string]*Recording{}}
}

// Scenario is the test function on the calling goroutine's stack: `TestX` for `pkg.TestX` and its
// subtests' closures `pkg.TestX.func1`, or "" when the call did not come from a test.
func Scenario(pkg string) string {
	pcs := make([]uintptr, 64)
	n := runtime.Callers(3, pcs)
	frames := runtime.CallersFrames(pcs[:n])
	for {
		f, more := frames.Next()
		name := f.Function
		if i := strings.LastIndex(name, "/"); i >= 0 {
			name = name[i+1:]
		}
		if strings.HasPrefix(name, pkg+".Test") {
			test := strings.TrimPrefix(name, pkg+".")
			if j := strings.Index(test, "."); j >= 0 {
				test = test[:j]
			}
			return test
		}
		if !more {
			return ""
		}
	}
}

func (t *Transport) RoundTrip(r *http.Request) (*http.Response, error) {
	if !Local(r.URL.Host) {
		return nil, fmt.Errorf("recording refused a request to %s: nothing may leave the machine while the original is recorded", r.URL.Host)
	}
	var reqBody []byte
	if r.Body != nil {
		reqBody, _ = io.ReadAll(r.Body)
		_ = r.Body.Close()
		r.Body = io.NopCloser(bytes.NewReader(reqBody))
	}
	resp, err := t.Next.RoundTrip(r)
	if err != nil {
		return resp, err
	}
	respBody, _ := io.ReadAll(resp.Body)
	_ = resp.Body.Close()
	resp.Body = io.NopCloser(bytes.NewReader(respBody))

	scenario := Scenario(t.Package)
	if scenario == "" {
		scenario = "TestMain"
	}
	service := t.Services[r.URL.Host]
	if service == "" {
		service = "unsorted"
	}
	step := Step{
		Name:     fmt.Sprintf("%s %s", r.Method, r.URL.Path),
		Request:  Request{Method: r.Method, Path: r.URL.RequestURI(), Service: service, Headers: kept(r.Header), Body: raw(reqBody)},
		Response: Response{Status: resp.StatusCode, Body: raw(respBody)},
	}
	t.add(scenario, service, step)
	return resp, nil
}

// kept keeps the headers a replay needs and never a credential.
func kept(h http.Header) map[string]string {
	out := map[string]string{}
	for _, k := range []string{"Content-Type", "Accept", "X-Brand-Id"} {
		if v := h.Get(k); v != "" {
			out[k] = v
		}
	}
	if len(out) == 0 {
		return nil
	}
	return out
}

func raw(b []byte) json.RawMessage {
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

func (t *Transport) add(scenario, service string, step Step) {
	t.mu.Lock()
	defer t.mu.Unlock()
	rec, ok := t.recordings[scenario]
	if !ok {
		rec = &Recording{Scenario: scenario, RecordedAt: time.Now().UTC(), Original: t.Original, Runs: 1, Service: service}
		t.recordings[scenario] = rec
	}
	rec.Steps = append(rec.Steps, step)
	// written after every step, so a crash mid-suite keeps what was recorded
	_ = write(filepath.Join(t.Dir, rec.Service, scenario+".json"), rec)
}

// Scenarios returns the recorded scenario names, sorted.
func (t *Transport) Scenarios() []string {
	t.mu.Lock()
	defer t.mu.Unlock()
	names := make([]string, 0, len(t.recordings))
	for n := range t.recordings {
		names = append(names, n)
	}
	sort.Strings(names)
	return names
}

func write(path string, v any) error {
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return err
	}
	b, err := json.MarshalIndent(v, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(path, append(b, '\n'), 0o644)
}

// Install replaces the default transport when EITHEROR_RECORD_TO is set, and returns it (or nil).
func Install(pkg string, getenv func(string) string) *Transport {
	dir := getenv("EITHEROR_RECORD_TO")
	if dir == "" {
		return nil
	}
	original := map[string]string{"commit": getenv("EITHEROR_ORIGINAL_COMMIT"), "brand": getenv("EITHEROR_CASINO_BRAND_ID")}
	t := New(http.DefaultTransport, dir, FromEnvironment(getenv), original, pkg)
	http.DefaultTransport = t
	http.DefaultClient.Transport = t
	return t
}

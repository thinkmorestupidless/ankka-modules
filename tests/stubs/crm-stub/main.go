// Command crm-stub answers Customer.io's two hosts (spec 014 research R2): Track
// (`POST /api/v2/entity`) and App (`POST /v1/send/email`), each on its own port, with one log of
// every call and one script of failures across both. The original and the port are both pointed at
// it, so a CRM dispatch runs with no request leaving the machine. See README.md.
package main

import (
	"bytes"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"flag"
	"io"
	"log"
	"net/http"
	"os"
	"strconv"
	"sync"
	"time"

	"github.com/eitheror-io/ankka-casino/tests/stubs/internal/stubhttp"
)

// Config is the stub's credentials and how long a hang holds.
type Config struct {
	SiteID, TrackKey, AppKey string
	Hang                     time.Duration
}

// Received is one call, as stubhttp records it, with the host it reached.
type Received struct {
	Host string `json:"host"`
	stubhttp.Received
}

// step is one scripted answer: a status, or a hang when status is 0.
type step struct{ status int }

// Stub holds the log and the script, shared by both hosts.
type Stub struct {
	cfg      Config
	mu       sync.Mutex
	received []Received
	script   []step
}

func New(cfg Config) *Stub { return &Stub{cfg: cfg} }

// Received returns a copy of every call, oldest first.
func (s *Stub) Received() []Received {
	s.mu.Lock()
	defer s.mu.Unlock()
	return append([]Received(nil), s.received...)
}

func (s *Stub) record(host string, r *http.Request, body []byte) {
	headers := map[string]string{}
	for k := range r.Header {
		headers[k] = r.Header.Get(k)
	}
	entry := Received{Host: host, Received: stubhttp.Received{At: time.Now().UTC(), Method: r.Method, Path: r.URL.RequestURI(), Header: headers}}
	if json.Valid(body) {
		entry.Body = body
	} else if len(body) > 0 {
		quoted, _ := json.Marshal(string(body))
		entry.Body = quoted
	}
	s.mu.Lock()
	s.received = append(s.received, entry)
	s.mu.Unlock()
}

// next takes the next scripted step, if any.
func (s *Stub) next() (step, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if len(s.script) == 0 {
		return step{}, false
	}
	st := s.script[0]
	s.script = s.script[1:]
	return st, true
}

// Control is the body of POST /__control.
type Control struct {
	Mode  string `json:"mode"`
	Count int    `json:"count"`
}

func (s *Stub) handleControl(w http.ResponseWriter, r *http.Request) {
	var c Control
	if err := json.NewDecoder(r.Body).Decode(&c); err != nil {
		stubhttp.WriteJSON(w, http.StatusBadRequest, map[string]string{"error": `the body is {"mode": "429"|"500"|"503"|"hang"|"reset", "count": n}`})
		return
	}
	var st step
	switch c.Mode {
	case "reset":
		s.mu.Lock()
		s.script, s.received = nil, nil
		s.mu.Unlock()
		stubhttp.WriteJSON(w, http.StatusOK, map[string]any{"mode": "reset"})
		return
	case "429", "500", "503":
		st.status, _ = strconv.Atoi(c.Mode)
	case "hang":
	default:
		stubhttp.WriteJSON(w, http.StatusBadRequest, map[string]string{"error": "mode is 429, 500, 503, hang or reset"})
		return
	}
	if c.Count <= 0 {
		c.Count = 1
	}
	s.mu.Lock()
	for i := 0; i < c.Count; i++ {
		s.script = append(s.script, st)
	}
	queued := len(s.script)
	s.mu.Unlock()
	stubhttp.WriteJSON(w, http.StatusOK, map[string]any{"mode": c.Mode, "count": c.Count, "queued": queued})
}

// Track is the handler for Customer.io's Track host.
func (s *Stub) Track() http.Handler {
	return s.host("track", "POST /api/v2/entity", func(w http.ResponseWriter, r *http.Request) {
		if id, key, ok := r.BasicAuth(); !ok || id != s.cfg.SiteID || key != s.cfg.TrackKey {
			stubhttp.WriteJSON(w, http.StatusUnauthorized, map[string]any{"meta": map[string]any{"error": "Unauthorized request"}})
			return
		}
		stubhttp.WriteJSON(w, http.StatusOK, map[string]any{})
	})
}

// App is the handler for Customer.io's App host.
func (s *Stub) App() http.Handler {
	return s.host("app", "POST /v1/send/email", func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer "+s.cfg.AppKey {
			stubhttp.WriteJSON(w, http.StatusUnauthorized, map[string]any{"meta": map[string]any{"error": "Unauthorized request"}})
			return
		}
		id := make([]byte, 12)
		_, _ = rand.Read(id)
		stubhttp.WriteJSON(w, http.StatusOK, map[string]any{"delivery_id": hex.EncodeToString(id), "queued_at": time.Now().Unix()})
	})
}

// host serves one host's route under the shared controls. Every call to the host's own route is
// logged, then answered by the script if a step is queued, else by the route (credentials, then 200).
func (s *Stub) host(name, pattern string, route http.HandlerFunc) http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, _ *http.Request) {
		stubhttp.WriteJSON(w, http.StatusOK, map[string]string{"stub": "crm-" + name, "status": "ok"})
	})
	mux.HandleFunc("POST /__control", s.handleControl)
	mux.HandleFunc("GET /__received", func(w http.ResponseWriter, _ *http.Request) {
		stubhttp.WriteJSON(w, http.StatusOK, s.Received())
	})
	mux.HandleFunc(pattern, func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		_ = r.Body.Close()
		s.record(name, r, body)
		r.Body = io.NopCloser(bytes.NewReader(body))
		if st, ok := s.next(); ok {
			if st.status == 0 {
				select {
				case <-r.Context().Done():
					return
				case <-time.After(s.cfg.Hang):
					stubhttp.WriteJSON(w, http.StatusGatewayTimeout, map[string]string{"error": "hung"})
					return
				}
			}
			stubhttp.WriteJSON(w, st.status, map[string]any{"meta": map[string]any{"error": http.StatusText(st.status)}})
			return
		}
		if !json.Valid(body) {
			stubhttp.WriteJSON(w, http.StatusBadRequest, map[string]any{"meta": map[string]any{"error": "the body is not JSON"}})
			return
		}
		route(w, r)
	})
	return mux
}

func env(name, def string) string {
	if v := os.Getenv(name); v != "" {
		return v
	}
	return def
}

func main() {
	var cfg Config
	trackPort := flag.String("track-port", env("CRM_STUB_TRACK_PORT", "18201"), "Track host's port")
	appPort := flag.String("app-port", env("CRM_STUB_APP_PORT", "18202"), "App host's port")
	flag.StringVar(&cfg.SiteID, "site-id", env("CRM_STUB_SITE_ID", "stub-site"), "Track basic-auth user")
	flag.StringVar(&cfg.TrackKey, "track-key", env("CRM_STUB_TRACK_KEY", "stub-track-key"), "Track basic-auth password")
	flag.StringVar(&cfg.AppKey, "app-key", env("CRM_STUB_APP_KEY", "stub-app-key"), "App bearer key")
	flag.DurationVar(&cfg.Hang, "hang", 30*time.Second, "how long a hang holds before answering 504")
	flag.Parse()

	s := New(cfg)
	failed := make(chan error)
	for _, l := range []struct {
		name, port string
		h          http.Handler
	}{{"track", *trackPort, s.Track()}, {"app", *appPort, s.App()}} {
		log.Printf("crm-stub: %s on :%s", l.name, l.port)
		server := &http.Server{Addr: ":" + l.port, Handler: l.h}
		go func() { failed <- server.ListenAndServe() }()
	}
	log.Fatal(<-failed)
}

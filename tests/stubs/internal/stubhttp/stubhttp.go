// Package stubhttp is what every payment-provider stub shares (spec 001 FR-029,
// contracts/recording.md): the health route the recording's preflight probes, a control route a
// scenario forces the next outcome with, a log of what the original sent, and the server itself.
// A stub answers as its provider would, so the original can be run and recorded with no request
// leaving the machine.
package stubhttp

import (
	"encoding/json"
	"io"
	"log"
	"net/http"
	"os"
	"sync"
	"time"
)

// Outcome is what the stub answers the next payment with: "success", "failure" or "pending".
type Outcome string

const (
	Success Outcome = "success"
	Failure Outcome = "failure"
	Pending Outcome = "pending"
)

// Received is one request the original made, as the recording's outbound list carries it.
type Received struct {
	At     time.Time         `json:"at"`
	Method string            `json:"method"`
	Path   string            `json:"path"`
	Header map[string]string `json:"headers"`
	Body   json.RawMessage   `json:"body,omitempty"`
}

// Stub holds the forced outcome and the log. Its zero value answers Success.
type Stub struct {
	Name     string
	mu       sync.Mutex
	outcome  Outcome
	received []Received
}

// New makes a stub named after its provider.
func New(name string) *Stub { return &Stub{Name: name, outcome: Success} }

// Next is the outcome the next payment gets.
func (s *Stub) Next() Outcome {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.outcome
}

// Received returns a copy of what the original sent, oldest first.
func (s *Stub) Received() []Received {
	s.mu.Lock()
	defer s.mu.Unlock()
	return append([]Received(nil), s.received...)
}

func (s *Stub) record(r *http.Request, body []byte) {
	headers := map[string]string{}
	for k := range r.Header {
		headers[k] = r.Header.Get(k)
	}
	entry := Received{At: time.Now().UTC(), Method: r.Method, Path: r.URL.RequestURI(), Header: headers}
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

// Handler wraps the provider's own routes with /healthz, /__control and /__received, and logs every
// request to the provider's routes (with its body, which the provider handler can still read).
func (s *Stub) Handler(provider http.Handler) http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, _ *http.Request) {
		WriteJSON(w, http.StatusOK, map[string]string{"stub": s.Name, "status": "ok"})
	})
	mux.HandleFunc("POST /__control", func(w http.ResponseWriter, r *http.Request) {
		var c struct {
			Outcome Outcome `json:"outcome"`
		}
		if err := json.NewDecoder(r.Body).Decode(&c); err != nil || (c.Outcome != Success && c.Outcome != Failure && c.Outcome != Pending) {
			WriteJSON(w, http.StatusBadRequest, map[string]string{"error": `outcome is "success", "failure" or "pending"`})
			return
		}
		s.mu.Lock()
		s.outcome = c.Outcome
		s.mu.Unlock()
		WriteJSON(w, http.StatusOK, map[string]string{"outcome": string(c.Outcome)})
	})
	mux.HandleFunc("GET /__received", func(w http.ResponseWriter, _ *http.Request) {
		WriteJSON(w, http.StatusOK, s.Received())
	})
	mux.Handle("/", http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		_ = r.Body.Close()
		s.record(r, body)
		r.Body = io.NopCloser(bytesReader(body))
		provider.ServeHTTP(w, r)
	}))
	return mux
}

// WriteJSON answers with a JSON body.
func WriteJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

// Serve runs the stub on $PORT (default the given port) until the process is stopped.
func Serve(s *Stub, provider http.Handler, defaultPort string) {
	port := os.Getenv("PORT")
	if port == "" {
		port = defaultPort
	}
	log.Printf("%s stub on :%s", s.Name, port)
	log.Fatal(http.ListenAndServe(":"+port, s.Handler(provider)))
}

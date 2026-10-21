package gatewaydriver

import (
	"bytes"
	"encoding/json"
	"io"
	"net/http"
	"sort"
	"strings"
	"sync"
	"time"
)

// Receiver stands in for the two services the original's psp-gateway calls back: merchant-service
// (`PSP_GATEWAY_MERCHANT_SERVICE_URL`: the terminal properties and credentials it reads, the
// capture and fail routes it posts deposit outcomes to) and casino-service (`CASINO_SERVICE_URL`:
// the withdrawal outcome route). It logs every outcome call (path, body, the headers minus any
// secret) and answers it as the scenario scripted: a status, or silence.
//
// The driver arranges it through its own routes, recorded as `setup:` and `observe:` steps:
//
//	PUT  /__terminals/{terminalId}                 {"properties": {…}}: what the terminal read answers
//	PUT  /__credentials/{subBrandId}/{provider}    {"credentials": {…}}: what the credential read answers
//	POST /__script                                 {"match", "answers": [{"status", "body"?, "silentMs"?}]}
//	GET  /__received?path=…                        the outcome calls whose path holds any of the values
//	GET  /healthz                                  for preflight
type Receiver struct {
	mu          sync.Mutex
	calls       []Call
	scripts     []script
	terminals   map[string]map[string]string
	credentials map[string]map[string]string
	// MaxSilence bounds a silent answer when the caller never gives up.
	MaxSilence time.Duration
}

// Call is one outcome call the original made.
type Call struct {
	At      time.Time         `json:"at"`
	Method  string            `json:"method"`
	Path    string            `json:"path"`
	Headers map[string]string `json:"headers,omitempty"`
	Body    json.RawMessage   `json:"body,omitempty"`
	// Answered is the status the receiver gave, 0 when it stayed silent.
	Answered int `json:"answered"`
}

// Answer is one scripted answer: a status and body, or, with SilentMs, no answer at all: the
// connection is held that long (or until the caller gives up) and closed.
type Answer struct {
	Status   int             `json:"status,omitempty"`
	Body     json.RawMessage `json:"body,omitempty"`
	SilentMs int             `json:"silentMs,omitempty"`
}

// script answers the calls whose path holds match, in order; the last answer holds once the
// others are spent, as psp-stub's control does.
type script struct {
	Match   string   `json:"match"`
	Answers []Answer `json:"answers"`
}

// NewReceiver answers every outcome call 200 until scripted otherwise.
func NewReceiver() *Receiver {
	return &Receiver{terminals: map[string]map[string]string{}, credentials: map[string]map[string]string{}, MaxSilence: 60 * time.Second}
}

// noted are the headers an outcome call's record leaves out: the secret the original sends
// (`X-Internal-Secret`), any other credential, and the transport's own.
var noted = map[string]bool{
	"X-Internal-Secret": true, "Authorization": true, "Cookie": true, "Content-Length": true,
	"Accept-Encoding": true, "User-Agent": true, "Connection": true, "Traceparent": true, "Tracestate": true,
}

func (rc *Receiver) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	parts := strings.Split(strings.Trim(r.URL.Path, "/"), "/")
	switch {
	case r.Method == http.MethodGet && r.URL.Path == "/healthz":
		writeJSON(w, http.StatusOK, map[string]string{"receiver": "gateway-driver", "status": "ok"})
	case r.Method == http.MethodGet && r.URL.Path == "/__received":
		writeJSON(w, http.StatusOK, rc.Received(r.URL.Query()["path"]...))
	case r.Method == http.MethodPost && r.URL.Path == "/__script":
		var sc script
		if err := json.NewDecoder(r.Body).Decode(&sc); err != nil || sc.Match == "" || len(sc.Answers) == 0 {
			writeJSON(w, http.StatusBadRequest, map[string]string{"error": `the body is {"match": …, "answers": [{"status": …}]}`})
			return
		}
		rc.Script(sc.Match, sc.Answers...)
		writeJSON(w, http.StatusOK, map[string]any{"match": sc.Match, "answers": len(sc.Answers)})
	case r.Method == http.MethodPut && len(parts) == 2 && parts[0] == "__terminals":
		var body struct {
			Properties map[string]string `json:"properties"`
		}
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
			writeJSON(w, http.StatusBadRequest, map[string]string{"error": `the body is {"properties": {…}}`})
			return
		}
		rc.Terminal(parts[1], body.Properties)
		writeJSON(w, http.StatusOK, map[string]any{"terminalId": parts[1], "properties": len(body.Properties)})
	case r.Method == http.MethodPut && len(parts) == 3 && parts[0] == "__credentials":
		var body struct {
			Credentials map[string]string `json:"credentials"`
		}
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
			writeJSON(w, http.StatusBadRequest, map[string]string{"error": `the body is {"credentials": {…}}`})
			return
		}
		rc.Credentials(parts[1], parts[2], body.Credentials)
		writeJSON(w, http.StatusOK, map[string]any{"subBrandId": parts[1], "provider": parts[2]})
	// merchant-service's reads, as the original's merchant.Client makes them (internal/merchant/client.go)
	case r.Method == http.MethodGet && len(parts) == 5 && parts[0] == "internal" && parts[2] == "terminals" && parts[4] == "properties":
		rc.mu.Lock()
		props, ok := rc.terminals[parts[3]]
		rc.mu.Unlock()
		if !ok {
			writeJSON(w, http.StatusNotFound, map[string]string{"error": "terminal_not_found"})
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{"properties": props})
	case r.Method == http.MethodGet && len(parts) == 6 && parts[0] == "internal" && parts[2] == "sub-brands" && parts[4] == "psp-credentials":
		rc.mu.Lock()
		creds, ok := rc.credentials[parts[3]+"/"+strings.ToUpper(parts[5])]
		rc.mu.Unlock()
		if !ok {
			writeJSON(w, http.StatusNotFound, map[string]string{"error": "not_found"})
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{"provider": strings.ToUpper(parts[5]), "credentials": creds})
	default:
		rc.outcome(w, r)
	}
}

// outcome logs one outcome call and answers it as scripted.
func (rc *Receiver) outcome(w http.ResponseWriter, r *http.Request) {
	body, _ := io.ReadAll(r.Body)
	headers := map[string]string{}
	for k := range r.Header {
		if !noted[k] {
			headers[k] = r.Header.Get(k)
		}
	}
	answer := rc.next(r.URL.Path)
	call := Call{At: time.Now().UTC(), Method: r.Method, Path: r.URL.RequestURI(), Headers: headers, Body: rawJSON(body), Answered: answer.Status}
	if answer.SilentMs > 0 {
		call.Answered = 0
	}
	rc.mu.Lock()
	rc.calls = append(rc.calls, call)
	rc.mu.Unlock()

	if answer.SilentMs > 0 {
		hold := time.Duration(answer.SilentMs) * time.Millisecond
		if hold > rc.MaxSilence {
			hold = rc.MaxSilence
		}
		select {
		case <-r.Context().Done():
		case <-time.After(hold):
		}
		// No answer at all: a handler that returns without writing would answer 200.
		if hj, ok := w.(http.Hijacker); ok {
			if conn, _, err := hj.Hijack(); err == nil {
				_ = conn.Close()
				return
			}
		}
		panic(http.ErrAbortHandler)
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(answer.Status)
	if len(answer.Body) > 0 {
		_, _ = w.Write(answer.Body)
	} else {
		_, _ = w.Write([]byte(`{}`))
	}
}

// next is the answer for a call to path: the first script whose match the path holds, else 200.
func (rc *Receiver) next(path string) Answer {
	rc.mu.Lock()
	defer rc.mu.Unlock()
	for i := range rc.scripts {
		sc := &rc.scripts[i]
		if !strings.Contains(path, sc.Match) {
			continue
		}
		a := sc.Answers[0]
		if len(sc.Answers) > 1 {
			sc.Answers = sc.Answers[1:]
		}
		if a.Status == 0 {
			a.Status = http.StatusOK
		}
		return a
	}
	return Answer{Status: http.StatusOK}
}

// Script makes the calls whose path holds match get these answers, in order, the last holding.
// It replaces any script for the same match.
func (rc *Receiver) Script(match string, answers ...Answer) {
	rc.mu.Lock()
	defer rc.mu.Unlock()
	for i := range rc.scripts {
		if rc.scripts[i].Match == match {
			rc.scripts[i].Answers = answers
			return
		}
	}
	rc.scripts = append(rc.scripts, script{Match: match, Answers: answers})
}

// Terminal sets what the original's terminal read answers for terminalId.
func (rc *Receiver) Terminal(terminalId string, properties map[string]string) {
	rc.mu.Lock()
	defer rc.mu.Unlock()
	rc.terminals[terminalId] = properties
}

// Credentials sets what the original's credential read answers for a sub-brand and provider.
func (rc *Receiver) Credentials(subBrandId, provider string, credentials map[string]string) {
	rc.mu.Lock()
	defer rc.mu.Unlock()
	rc.credentials[subBrandId+"/"+strings.ToUpper(provider)] = credentials
}

// Received is every outcome call whose path holds any of the values (all of them with none),
// oldest first.
func (rc *Receiver) Received(paths ...string) []Call {
	rc.mu.Lock()
	defer rc.mu.Unlock()
	out := []Call{}
	for _, c := range rc.calls {
		if len(paths) == 0 || holdsAny(c.Path, paths) {
			out = append(out, c)
		}
	}
	sort.SliceStable(out, func(i, j int) bool { return out[i].At.Before(out[j].At) })
	return out
}

func holdsAny(s string, values []string) bool {
	for _, v := range values {
		if v != "" && strings.Contains(s, v) {
			return true
		}
	}
	return false
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
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

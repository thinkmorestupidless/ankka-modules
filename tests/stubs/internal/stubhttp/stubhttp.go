// Package stubhttp is what every payment-provider stub shares (spec 001 FR-029,
// contracts/recording.md; spec 005 FR-025): the health route the recording's preflight probes, a
// control route a scenario forces the next outcome with, a webhook route that makes the stub send
// its provider's webhook for one reference now, a log of what the original sent, and the server
// itself. A stub answers as its provider would, so the original (and the port) can be run and
// recorded with no request leaving the machine.
package stubhttp

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"sort"
	"strings"
	"sync"
	"time"
)

// Outcome is what the stub answers the next payment with.
type Outcome string

const (
	Success      Outcome = "success"      // captured, completed
	Failure      Outcome = "failure"      // declined or failed, with Control.Code where the provider has one
	Pending      Outcome = "pending"      // left open; settled by the next control
	Duplicate    Outcome = "duplicate"    // the provider refuses the reference as already used
	NoRecord     Outcome = "no_record"    // the provider's status read holds nothing for the reference
	Reversed     Outcome = "reversed"     // completed, then charged back
	Underpayment Outcome = "underpayment" // less crypto arrived than quoted
	Overpayment  Outcome = "overpayment"  // more crypto arrived than quoted
	Unsigned     Outcome = "unsigned"     // the webhook is sent without its signature
	Unauthorised Outcome = "unauthorised" // the provider answers 401, as to empty credentials
)

// base is what every stub supports; a provider adds the rest with Supports.
var base = []Outcome{Success, Failure, Pending}

// Control is the body of POST /__control, and of POST /__webhook's overrides. Only Outcome is
// required; the rest are read by the providers that have the behaviour.
type Control struct {
	Outcome Outcome `json:"outcome"`
	// Code is the provider's failure code (a status, an rcode, an errorCode) where it has one.
	Code string `json:"code,omitempty"`
	// ReceivedAmount is the crypto amount reported as arrived (Cozeni, Morphosis).
	ReceivedAmount json.Number `json:"receivedAmount,omitempty"`
	// ConvertAmount is Cozeni's fiat valuation of what arrived ("0" for an unpriced underpayment).
	ConvertAmount json.Number `json:"convertAmount,omitempty"`
	// SignatureEncoding is the webhook signature's encoding where the provider's is ambiguous
	// (Digitarium: "hex", "base64", "base64url").
	SignatureEncoding string `json:"signatureEncoding,omitempty"`
	// Envelope is the webhook body's shape where the provider's is ambiguous (Digitarium: "flat"
	// or "result").
	Envelope string `json:"envelope,omitempty"`
	// DelayMs overrides the provider's delay before an automatic webhook.
	DelayMs *int `json:"delayMs,omitempty"`
}

// merge lays the fields set in o over c.
func (c Control) merge(o Control) Control {
	if o.Outcome != "" {
		c.Outcome = o.Outcome
	}
	if o.Code != "" {
		c.Code = o.Code
	}
	if o.ReceivedAmount != "" {
		c.ReceivedAmount = o.ReceivedAmount
	}
	if o.ConvertAmount != "" {
		c.ConvertAmount = o.ConvertAmount
	}
	if o.SignatureEncoding != "" {
		c.SignatureEncoding = o.SignatureEncoding
	}
	if o.Envelope != "" {
		c.Envelope = o.Envelope
	}
	if o.DelayMs != nil {
		c.DelayMs = o.DelayMs
	}
	return c
}

// Delivery is one webhook the stub sent and what the gateway answered.
type Delivery struct {
	URL    string
	Status int
	Body   []byte
	Err    error
}

// ErrUnknownReference is what a WebhookFunc answers for a reference the stub holds no session for.
var ErrUnknownReference = errors.New("no session for that reference")

// WebhookFunc sends the provider's webhook for one reference with the given control, now, and
// answers what the gateway said. Any error but ErrUnknownReference means the control does not
// make a webhook for that session (400).
type WebhookFunc func(reference string, c Control) (Delivery, error)

// Received is one request the original made, as the recording's outbound list carries it.
type Received struct {
	At     time.Time         `json:"at"`
	Method string            `json:"method"`
	Path   string            `json:"path"`
	Header map[string]string `json:"headers"`
	Body   json.RawMessage   `json:"body,omitempty"`
}

// Stub holds the forced outcome, the log and the provider's webhook hook.
type Stub struct {
	Name      string
	mu        sync.Mutex
	control   Control
	supported map[Outcome]bool
	webhook   WebhookFunc
	held      []string
	received  []Received
}

// New makes a stub named after its provider. It answers Success until told otherwise, and accepts
// success, failure and pending; Supports adds the rest.
func New(name string) *Stub {
	s := &Stub{Name: name, control: Control{Outcome: Success}, supported: map[Outcome]bool{}}
	s.Supports(base...)
	return s
}

// Supports adds outcomes this provider has the behaviour for. A control naming any other is 400.
func (s *Stub) Supports(outcomes ...Outcome) *Stub {
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, o := range outcomes {
		s.supported[o] = true
	}
	return s
}

// Supported lists the outcomes this stub accepts, sorted.
func (s *Stub) Supported() []string {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := make([]string, 0, len(s.supported))
	for o := range s.supported {
		out = append(out, string(o))
	}
	sort.Strings(out)
	return out
}

// OnWebhook installs the provider's webhook sender, which POST /__webhook and settling use.
func (s *Stub) OnWebhook(f WebhookFunc) {
	s.mu.Lock()
	s.webhook = f
	s.mu.Unlock()
}

// Next is the outcome the next payment gets.
func (s *Stub) Next() Outcome { return s.Control().Outcome }

// Control is the whole forced control: the outcome and its fields. It is sticky: it holds until
// the next POST /__control.
func (s *Stub) Control() Control {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.control
}

// Delay is the control's delayMs when set, else def.
func (c Control) Delay(def time.Duration) time.Duration {
	if c.DelayMs != nil && *c.DelayMs >= 0 {
		return time.Duration(*c.DelayMs) * time.Millisecond
	}
	return def
}

// Hold marks a reference left pending: the next control with another outcome settles it, by
// sending its webhook with that control.
func (s *Stub) Hold(reference string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, r := range s.held {
		if r == reference {
			return
		}
	}
	s.held = append(s.held, reference)
}

func (s *Stub) release(reference string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	for i, r := range s.held {
		if r == reference {
			s.held = append(s.held[:i], s.held[i+1:]...)
			return
		}
	}
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

// answer is the JSON form of a Delivery: the gateway's status and body.
func answer(d Delivery) map[string]any {
	var body any = string(d.Body)
	if len(d.Body) > 0 && json.Valid(d.Body) {
		body = json.RawMessage(d.Body)
	}
	return map[string]any{"status": d.Status, "body": body}
}

func (s *Stub) check(o Outcome) error {
	s.mu.Lock()
	ok := s.supported[o]
	s.mu.Unlock()
	if !ok {
		return fmt.Errorf("%s does not support outcome %q; it supports %s", s.Name, o, strings.Join(s.Supported(), ", "))
	}
	return nil
}

// Handler wraps the provider's own routes with /healthz, /__control, /__webhook and /__received,
// and logs every request to the provider's routes (with its body, which the provider handler can
// still read).
func (s *Stub) Handler(provider http.Handler) http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, _ *http.Request) {
		WriteJSON(w, http.StatusOK, map[string]string{"stub": s.Name, "status": "ok"})
	})
	mux.HandleFunc("POST /__control", s.handleControl)
	mux.HandleFunc("POST /__webhook", s.handleWebhook)
	mux.HandleFunc("GET /__received", func(w http.ResponseWriter, _ *http.Request) {
		WriteJSON(w, http.StatusOK, s.Received())
	})
	mux.Handle("/", http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		_ = r.Body.Close()
		s.record(r, body)
		r.Body = io.NopCloser(bytes.NewReader(body))
		provider.ServeHTTP(w, r)
	}))
	return mux
}

// handleControl sets the sticky control. When the previous outcome left references pending and
// this one is not pending, each is settled now with this control and the answer lists them.
func (s *Stub) handleControl(w http.ResponseWriter, r *http.Request) {
	var c Control
	if err := json.NewDecoder(r.Body).Decode(&c); err != nil || c.Outcome == "" {
		WriteJSON(w, http.StatusBadRequest, map[string]any{"error": "the body is {\"outcome\": …}", "supported": s.Supported()})
		return
	}
	if err := s.check(c.Outcome); err != nil {
		WriteJSON(w, http.StatusBadRequest, map[string]any{"error": err.Error(), "supported": s.Supported()})
		return
	}
	s.mu.Lock()
	s.control = c
	var settle []string
	if c.Outcome != Pending {
		settle, s.held = s.held, nil
	}
	hook := s.webhook
	s.mu.Unlock()

	out := map[string]any{"outcome": string(c.Outcome)}
	if len(settle) > 0 && hook != nil {
		settled := []map[string]any{}
		for _, ref := range settle {
			d, err := hook(ref, c)
			entry := map[string]any{"reference": ref}
			switch {
			case err != nil:
				entry["error"] = err.Error()
			case d.Err != nil:
				entry["error"] = d.Err.Error()
			default:
				for k, v := range answer(d) {
					entry[k] = v
				}
			}
			settled = append(settled, entry)
		}
		out["settled"] = settled
	}
	WriteJSON(w, http.StatusOK, out)
}

// handleWebhook sends the provider's webhook for one reference now, with the sticky control under
// the body's overrides, and answers the gateway's status and body.
func (s *Stub) handleWebhook(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Reference string `json:"reference"`
		Control
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.Reference == "" {
		WriteJSON(w, http.StatusBadRequest, map[string]string{"error": `the body is {"reference": …} with optional control fields`})
		return
	}
	c := s.Control().merge(req.Control)
	if err := s.check(c.Outcome); err != nil {
		WriteJSON(w, http.StatusBadRequest, map[string]string{"error": err.Error()})
		return
	}
	s.mu.Lock()
	hook := s.webhook
	s.mu.Unlock()
	if hook == nil {
		WriteJSON(w, http.StatusBadRequest, map[string]string{"error": s.Name + " sends no webhooks"})
		return
	}
	d, err := hook(req.Reference, c)
	switch {
	case errors.Is(err, ErrUnknownReference):
		WriteJSON(w, http.StatusNotFound, map[string]string{"error": fmt.Sprintf("%s holds no session for %q", s.Name, req.Reference)})
		return
	case err != nil:
		WriteJSON(w, http.StatusBadRequest, map[string]string{"error": err.Error()})
		return
	case d.Err != nil:
		WriteJSON(w, http.StatusBadGateway, map[string]string{"error": d.Err.Error(), "url": d.URL})
		return
	}
	if c.Outcome != Pending {
		s.release(req.Reference)
	}
	WriteJSON(w, http.StatusOK, answer(d))
}

// Post sends one webhook and reads the gateway's answer.
func Post(client *http.Client, target, contentType string, header map[string]string, body []byte) Delivery {
	d := Delivery{URL: target}
	req, err := http.NewRequest(http.MethodPost, target, bytes.NewReader(body))
	if err != nil {
		d.Err = err
		return d
	}
	req.Header.Set("Content-Type", contentType)
	for k, v := range header {
		req.Header.Set(k, v)
	}
	resp, err := client.Do(req)
	if err != nil {
		d.Err = err
		return d
	}
	defer resp.Body.Close()
	d.Status = resp.StatusCode
	d.Body, _ = io.ReadAll(io.LimitReader(resp.Body, 1<<16))
	return d
}

// Retarget is where a webhook goes when the request named requestURL and the stub is configured
// with configured. Empty configured: requestURL. Otherwise configured plus whatever requestURL
// carries after marker (a payout's "/withdrawal", Morphosis's "/deposit/{token}", a query), so
// the stub can reach a gateway whose own idea of its address is not reachable from the stub.
func Retarget(requestURL, configured, marker string) string {
	if configured == "" {
		return requestURL
	}
	if marker != "" {
		if i := strings.Index(requestURL, marker); i >= 0 {
			return strings.TrimRight(configured, "/") + requestURL[i+len(marker):]
		}
	}
	return configured
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

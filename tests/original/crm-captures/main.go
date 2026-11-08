// Command crm-captures gives each recording of a run the calls crm-stub received for its players
// (spec 014 research R2). The original's sends to Customer.io are made by its consumers and its TL
// capability, not by the suite, so the recorder never sees them; after a run this reads the stub's
// whole log and attaches to each recording's `outbound` (as `crm-track` or `crm-app`), in order,
// every call whose person (`identifiers.id`, `identifiers.email` or `to`) appears in that
// recording's steps. Credentials never reach a
// recording: headers are dropped, and a message_data value whose key names a token, link or secret is
// replaced. Run once per run, before merge; then reset the stub.
//
//	go run ./original/crm-captures -received http://localhost:18201/__received -dir /tmp/engagement-1
package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strings"

	"github.com/eitheror-io/ankka-casino/tests/original/recorder"
)

// Received is one entry of the stub's `GET /__received`.
type Received struct {
	Host   string          `json:"host"`
	Method string          `json:"method"`
	Path   string          `json:"path"`
	Body   json.RawMessage `json:"body"`
}

// Secret is a message_data key whose value is not kept.
var Secret = regexp.MustCompile(`(?i)token|link|url|password|secret|code`)

// People are the persons a call is about: the identifiers' id and email, and the transactional `to`.
func People(body json.RawMessage) []string {
	var b struct {
		To          string            `json:"to"`
		Identifiers map[string]string `json:"identifiers"`
	}
	if json.Unmarshal(body, &b) != nil {
		return nil
	}
	var out []string
	for _, k := range []string{"id", "email"} {
		if v := b.Identifiers[k]; v != "" {
			out = append(out, v)
		}
	}
	if b.To != "" {
		out = append(out, b.To)
	}
	return out
}

// Redacted is the body with each secret message_data value replaced.
func Redacted(body json.RawMessage) json.RawMessage {
	var m map[string]any
	if json.Unmarshal(body, &m) != nil {
		return body
	}
	data, ok := m["message_data"].(map[string]any)
	if !ok {
		return body
	}
	for k := range data {
		if Secret.MatchString(k) {
			data[k] = "<redacted>"
		}
	}
	out, err := json.Marshal(m)
	if err != nil {
		return body
	}
	return out
}

// Attach gives rec the calls about the people its steps name, and returns how many it took.
func Attach(rec *recorder.Recording, calls []Received) int {
	text, _ := json.Marshal(rec.Steps)
	steps := string(text)
	rec.Outbound = nil
	for _, c := range calls {
		for _, p := range People(c.Body) {
			quoted, _ := json.Marshal(p)
			if strings.Contains(steps, string(quoted[1:len(quoted)-1])) {
				rec.Outbound = append(rec.Outbound, recorder.Outbound{Stub: "crm-" + c.Host,
					Request: recorder.Request{Method: c.Method, Path: c.Path, Body: Redacted(c.Body)}})
				break
			}
		}
	}
	return len(rec.Outbound)
}

func main() {
	received := flag.String("received", "http://localhost:18201/__received", "crm-stub's log")
	dir := flag.String("dir", "", "the run's directory (EITHEROR_RECORD_TO)")
	flag.Parse()
	if *dir == "" {
		fmt.Fprintln(os.Stderr, "crm-captures needs -dir")
		os.Exit(2)
	}
	resp, err := http.Get(*received)
	check(err)
	raw, err := io.ReadAll(resp.Body)
	_ = resp.Body.Close()
	check(err)
	var calls []Received
	check(json.Unmarshal(raw, &calls))
	err = filepath.WalkDir(*dir, func(p string, d os.DirEntry, err error) error {
		if err != nil || d.IsDir() || !strings.HasSuffix(p, ".json") {
			return err
		}
		b, err := os.ReadFile(p)
		if err != nil {
			return err
		}
		var rec recorder.Recording
		if err := json.Unmarshal(b, &rec); err != nil {
			return fmt.Errorf("%s: %w", p, err)
		}
		n := Attach(&rec, calls)
		out, err := json.MarshalIndent(rec, "", "  ")
		if err != nil {
			return err
		}
		fmt.Printf("%s: %d CRM calls\n", rec.Scenario, n)
		return os.WriteFile(p, append(out, '\n'), 0o644)
	})
	check(err)
}

func check(err error) {
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}

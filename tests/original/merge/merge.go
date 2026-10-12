// Command merge turns two recording runs of the original's suite into the recordings this
// repository keeps (contracts/recording.md): a field that differs between the runs goes under
// `vary`, an id an earlier answer gave and a later request reuses becomes a capture, and the suite's
// own result is written beside each service's recordings.
//
//	go run ./original/merge -run1 /tmp/rec1 -run2 /tmp/rec2 -results1 run1.json -results2 run2.json -out port
package main

import (
	"bufio"
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"

	"github.com/eitheror-io/ankka-casino/tests/original/recorder"
)

func main() {
	run1 := flag.String("run1", "", "the first run's directory")
	run2 := flag.String("run2", "", "the second run's directory, on fresh ids")
	results1 := flag.String("results1", "", "`go test -json` output of the first run")
	results2 := flag.String("results2", "", "`go test -json` output of the second run")
	out := flag.String("out", "port", "where the recordings are kept (tests/port)")
	flag.Parse()
	if *run1 == "" || *run2 == "" {
		fmt.Fprintln(os.Stderr, "merge needs -run1 and -run2")
		os.Exit(2)
	}
	first, err := load(*run1)
	check(err)
	second, err := load(*run2)
	check(err)
	var unclaimed []string
	for key, a := range first {
		b, ok := second[key]
		merged := a
		if ok {
			merged = Merge(a, b)
		} else {
			fmt.Fprintf(os.Stderr, "%s ran once only: kept with runs 1, which `brands recordings check` refuses\n", key)
		}
		path := filepath.Join(*out, merged.Service, merged.Scenario+".json")
		check(write(path, merged))
		unclaimed = append(unclaimed, filepath.ToSlash(filepath.Join("tests", path)))
	}
	sort.Strings(unclaimed)
	check(appendUnclaimed(filepath.Join(*out, "unclaimed.txt"), unclaimed))
	if *results1 != "" {
		r1, err := results(*results1)
		check(err)
		r2 := map[string]string{}
		if *results2 != "" {
			r2, err = results(*results2)
			check(err)
		}
		check(writeResults(*out, first, r1, r2))
	}
}

func check(err error) {
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}

func load(dir string) (map[string]recorder.Recording, error) {
	recs := map[string]recorder.Recording{}
	err := filepath.WalkDir(dir, func(p string, d os.DirEntry, err error) error {
		if err != nil || d.IsDir() || !strings.HasSuffix(p, ".json") {
			return err
		}
		b, err := os.ReadFile(p)
		if err != nil {
			return err
		}
		var r recorder.Recording
		if err := json.Unmarshal(b, &r); err != nil {
			return fmt.Errorf("%s: %w", p, err)
		}
		recs[r.Service+"/"+r.Scenario] = r
		return nil
	})
	return recs, err
}

// Merge keeps run a's steps, marks every response field that differs in b as varying, and turns a
// varying id that a later request reuses into a capture. Both runs are collapsed first: a test that
// polls until something settles reads the same thing a number of times that differs between runs.
// Credentials are redacted from both (Redact).
func Merge(a, b recorder.Recording) recorder.Recording {
	a, b = Redact(Collapse(a)), Redact(Collapse(b))
	out := a
	out.Runs = 2
	out.Vary = map[string]string{}
	if len(a.Steps) != len(b.Steps) {
		out.Runs = 1
		fmt.Fprintf(os.Stderr, "%s: %d steps then %d; kept with runs 1\n", a.Scenario, len(a.Steps), len(b.Steps))
		return out
	}
	out.Steps = append([]recorder.Step(nil), a.Steps...)
	captures := 0
	for i := range out.Steps {
		var x, y any
		_ = json.Unmarshal(a.Steps[i].Response.Body, &x)
		_ = json.Unmarshal(b.Steps[i].Response.Body, &y)
		for _, d := range differences("$", x, y) {
			out.Vary[d.path] = "differs between two runs of the original"
			text, ok := d.value.(string)
			if !ok || len(text) < 4 {
				continue
			}
			reused := false
			for j := i + 1; j < len(out.Steps); j++ {
				if strings.Contains(out.Steps[j].Request.Path, text) || strings.Contains(string(out.Steps[j].Request.Body), text) {
					reused = true
					name := fmt.Sprintf("c%d", captures)
					out.Steps[j].Request.Path = strings.ReplaceAll(out.Steps[j].Request.Path, text, "{"+name+"}")
					out.Steps[j].Request.Body = json.RawMessage(strings.ReplaceAll(string(out.Steps[j].Request.Body), text, "{"+name+"}"))
					if out.Steps[i].Capture == nil {
						out.Steps[i].Capture = map[string]string{}
					}
					out.Steps[i].Capture[name] = d.path
				}
			}
			if reused {
				captures++
			}
		}
	}
	if len(out.Vary) == 0 {
		out.Vary = nil
	}
	return out
}

// Collapse keeps the last of consecutive GETs of the same path: a poll's earlier answers are only the
// state not having settled yet.
func Collapse(r recorder.Recording) recorder.Recording {
	var steps []recorder.Step
	for _, s := range r.Steps {
		if n := len(steps); n > 0 && s.Request.Method == "GET" && steps[n-1].Request.Method == "GET" && steps[n-1].Request.Path == s.Request.Path {
			steps[n-1] = s
			continue
		}
		steps = append(steps, s)
	}
	r.Steps = steps
	return r
}

// Secrets are the body fields a recording never keeps: the recorder drops credential headers, but a
// login or a token exchange carries its password in the body and its tokens in the answer.
var secrets = map[string]bool{"password": true, "access_token": true, "refresh_token": true, "id_token": true,
	"client_secret": true, "accessToken": true, "refreshToken": true, "idToken": true}

const redacted = "<redacted>"

var jwt = regexp.MustCompile(`^eyJ[A-Za-z0-9_-]*\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]*$`)

var formSecret =regexp.MustCompile(`(^|&)(password|client_secret|refresh_token)=[^&]*`)

// Redact replaces every secret in the steps' request and response bodies.
func Redact(r recorder.Recording) recorder.Recording {
	steps := make([]recorder.Step, len(r.Steps))
	for i, s := range r.Steps {
		s.Request.Body = redactBody(s.Request.Body)
		s.Response.Body = redactBody(s.Response.Body)
		steps[i] = s
	}
	r.Steps = steps
	return r
}

func redactBody(b json.RawMessage) json.RawMessage {
	if len(b) == 0 {
		return b
	}
	var v any
	if json.Unmarshal(b, &v) != nil {
		return b
	}
	if text, ok := v.(string); ok { // a form-encoded body is recorded as a JSON string
		v = formSecret.ReplaceAllString(text, "${1}${2}="+redacted)
	} else {
		v = redactValue(v)
	}
	out, _ := json.Marshal(v)
	return out
}

func redactValue(v any) any {
	switch x := v.(type) {
	case map[string]any:
		for k, e := range x {
			if secrets[k] {
				x[k] = redacted
			} else {
				x[k] = redactValue(e)
			}
		}
	case []any:
		for i := range x {
			x[i] = redactValue(x[i])
		}
	case string:
		if jwt.MatchString(x) { // a bearer token, whatever field carries it ("token" is also ST8's wallet token)
			return redacted
		}
	}
	return v
}

type difference struct {
	path  string
	value any
}

// differences lists the leaf paths where x and y differ, with x's value.
func differences(path string, x, y any) []difference {
	switch xv := x.(type) {
	case map[string]any:
		yv, ok := y.(map[string]any)
		if !ok {
			return []difference{{path, x}}
		}
		var out []difference
		keys := make([]string, 0, len(xv))
		for k := range xv {
			keys = append(keys, k)
		}
		sort.Strings(keys)
		for _, k := range keys {
			out = append(out, differences(path+"."+k, xv[k], yv[k])...)
		}
		return out
	case []any:
		yv, ok := y.([]any)
		if !ok || len(yv) != len(xv) {
			return []difference{{path, x}}
		}
		var out []difference
		for i := range xv {
			out = append(out, differences(fmt.Sprintf("%s[%d]", path, i), xv[i], yv[i])...)
		}
		return out
	default:
		if fmt.Sprint(x) != fmt.Sprint(y) {
			return []difference{{path, x}}
		}
		return nil
	}
}

func write(path string, v any) error {
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return err
	}
	b, _ := json.MarshalIndent(v, "", "  ")
	return os.WriteFile(path, append(b, '\n'), 0o644)
}

func appendUnclaimed(path string, paths []string) error {
	existing := map[string]bool{}
	if b, err := os.ReadFile(path); err == nil {
		for _, l := range strings.Split(string(b), "\n") {
			existing[strings.TrimSpace(l)] = true
		}
	}
	f, err := os.OpenFile(path, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o644)
	if err != nil {
		return err
	}
	defer f.Close()
	if len(existing) <= 1 {
		_, _ = f.WriteString("# Recordings no service spec claims yet. A spec claims one by naming it; then remove it here.\n")
	}
	for _, p := range paths {
		if !existing[p] {
			_, _ = f.WriteString(p + "\n")
		}
	}
	return nil
}

// results reads `go test -json` output: the last action of each top-level test.
func results(path string) (map[string]string, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	out := map[string]string{}
	s := bufio.NewScanner(f)
	s.Buffer(make([]byte, 1<<20), 1<<24)
	for s.Scan() {
		var e struct{ Action, Test string }
		if json.Unmarshal(s.Bytes(), &e) != nil || e.Test == "" || strings.Contains(e.Test, "/") {
			continue
		}
		if e.Action == "pass" || e.Action == "fail" || e.Action == "skip" {
			out[e.Test] = e.Action
		}
	}
	return out, s.Err()
}

func writeResults(dir string, recs map[string]recorder.Recording, r1, r2 map[string]string) error {
	byService := map[string]map[string][2]string{}
	for _, r := range recs {
		if byService[r.Service] == nil {
			byService[r.Service] = map[string][2]string{}
		}
		byService[r.Service][r.Scenario] = [2]string{r1[r.Scenario], r2[r.Scenario]}
	}
	// every test of the suite, recorded or not, under unsorted/, so a skipped scenario is visible
	all := map[string][2]string{}
	for t, a := range r1 {
		all[t] = [2]string{a, r2[t]}
	}
	byService["_suite"] = all
	for service, tests := range byService {
		if err := write(filepath.Join(dir, service, "suite-result.json"), tests); err != nil {
			return err
		}
	}
	return nil
}

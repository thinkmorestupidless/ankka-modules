package main

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/eitheror-io/ankka-casino/tests/original/recorder"
)

func TestADriversSubtestResultIsItsScenariosAndASecondMergeAddsToTheServicesResults(t *testing.T) {
	dir := t.TempDir()
	first := map[string]recorder.Recording{"a": {Scenario: "a closed player's sign-in is refused", Service: "players"}}
	// the driver's offline check names its subtest after the scenario too, and runs first
	events := filepath.Join(dir, "run.json")
	lines := `{"Action":"pass","Test":"TestEveryScenarioRecordsNoCredential/a_closed_player's_sign-in_is_refused"}
{"Action":"fail","Test":"TestPlayers/a_closed_player's_sign-in_is_refused"}
{"Action":"fail","Test":"TestPlayers"}
`
	if err := os.WriteFile(events, []byte(lines), 0o600); err != nil {
		t.Fatal(err)
	}
	r, err := results(events)
	if err != nil {
		t.Fatal(err)
	}
	if err := writeResults(dir, first, r, r); err != nil {
		t.Fatal(err)
	}
	second := map[string]recorder.Recording{"b": {Scenario: "TestScenario18_X", Service: "players"}}
	s18 := map[string]string{"TestScenario18_X": "pass"}
	if err := writeResults(dir, second, s18, s18); err != nil {
		t.Fatal(err)
	}
	read := func(service string) map[string][2]string {
		b, err := os.ReadFile(filepath.Join(dir, service, "suite-result.json"))
		if err != nil {
			t.Fatal(err)
		}
		out := map[string][2]string{}
		if err := json.Unmarshal(b, &out); err != nil {
			t.Fatal(err)
		}
		return out
	}
	players, suite := read("players"), read("_suite")
	if players["a closed player's sign-in is refused"] != [2]string{"fail", "fail"} || players["TestScenario18_X"] != [2]string{"pass", "pass"} {
		t.Fatalf("players: %v", players)
	}
	if _, sub := suite["TestPlayers/a_closed_player's_sign-in_is_refused"]; sub || suite["TestPlayers"] != [2]string{"fail", "fail"} || suite["TestScenario18_X"] != [2]string{"pass", "pass"} {
		t.Fatalf("_suite: %v", suite)
	}
}

func step(path, req, resp string) recorder.Step {
	return recorder.Step{Request: recorder.Request{Method: "GET", Path: path, Body: json.RawMessage(req)}, Response: recorder.Response{Status: 200, Body: json.RawMessage(resp)}}
}

func TestMergeFindsVaryingFieldsAndCaptures(t *testing.T) {
	a := recorder.Recording{Scenario: "TestX", Service: "wallet", Runs: 1, Steps: []recorder.Step{
		step("/players", `{}`, `{"player_id":"p-aaaa","balance":"10.00","at":"t1"}`),
		step("/players/p-aaaa/wallet", `{}`, `{"balance":"10.00"}`),
	}}
	b := recorder.Recording{Scenario: "TestX", Service: "wallet", Runs: 1, Steps: []recorder.Step{
		step("/players", `{}`, `{"player_id":"p-bbbb","balance":"10.00","at":"t2"}`),
		step("/players/p-bbbb/wallet", `{}`, `{"balance":"10.00"}`),
	}}
	m := Merge(a, b)
	if m.Runs != 2 {
		t.Fatalf("runs %d", m.Runs)
	}
	if _, ok := m.Steps[0].Vary["$.player_id"]; !ok {
		t.Fatalf("vary %v", m.Steps[0].Vary)
	}
	if _, ok := m.Steps[0].Vary["$.balance"]; ok {
		t.Fatal("an equal field was marked varying")
	}
	if m.Steps[1].Vary != nil || m.Vary != nil {
		t.Fatalf("a field that varied in one step is marked only there: %v / %v", m.Steps[1].Vary, m.Vary)
	}
	if m.Steps[1].Request.Path != "/players/{c0}/wallet" || m.Steps[0].Capture["c0"] != "$.player_id" {
		t.Fatalf("capture not inferred: %+v / %+v", m.Steps[1].Request, m.Steps[0].Capture)
	}
}

// A varying value whose digits a later body holds as a number is captured only where it is a string:
// a placeholder for the number is not JSON (a forged webhook's `created`, 2026-10-20).
func TestACaptureReplacesStringsOnlyAndLeavesTheBodyJSON(t *testing.T) {
	rec := func(at, ref string) recorder.Recording {
		return recorder.Recording{Scenario: "TestZ", Service: "psp-gateway", Steps: []recorder.Step{
			step("/a", `{}`, `{"at":"`+at+`","ref":"`+ref+`"}`),
			step("/b", `{"created":`+at+`,"referenceId":"`+ref+`"}`, `{}`),
		}}
	}
	m := Merge(rec("1792485430", "ref-aaaa"), rec("1792485999", "ref-bbbb"))
	body := string(m.Steps[1].Request.Body)
	if !json.Valid(m.Steps[1].Request.Body) {
		t.Fatalf("not JSON: %s", body)
	}
	if !strings.Contains(body, `"created":1792485430`) || !strings.Contains(body, `"referenceId":"{c0}"`) {
		t.Fatalf("captured where it should not, or not where it should: %s / %v", body, m.Steps[0].Capture)
	}
}

func TestDifferentStepCountsKeepOneRun(t *testing.T) {
	a := recorder.Recording{Scenario: "TestY", Steps: []recorder.Step{step("/a", `{}`, `{}`)}}
	b := recorder.Recording{Scenario: "TestY"}
	if Merge(a, b).Runs != 1 {
		t.Fatal("a scenario that did not repeat was marked as run twice")
	}
}

func TestCredentialsAreRedacted(t *testing.T) {
	login := recorder.Step{
		Request:  recorder.Request{Method: "POST", Path: "/token", Body: json.RawMessage(`"client_id=x&password=hunter2%21&username=a"`)},
		Response: recorder.Response{Status: 200, Body: json.RawMessage(`{"access_token":"eyJ.a.b","nested":{"refreshToken":"r"},"token":"st8-wallet-token"}`)},
	}
	join := recorder.Step{
		Request:  recorder.Request{Method: "POST", Path: "/login", Body: json.RawMessage(`{"email":"e@x","password":"Pwd1!"}`)},
		Response: recorder.Response{Status: 200, Body: json.RawMessage(`{"token":"eyJhbGc.eyJzdWI.c2ln"}`)},
	}
	r := recorder.Recording{Scenario: "TestS", Steps: []recorder.Step{login, join}}
	m := Merge(r, r)
	all := string(m.Steps[0].Request.Body) + string(m.Steps[0].Response.Body) + string(m.Steps[1].Request.Body) + string(m.Steps[1].Response.Body)
	for _, secret := range []string{"hunter2", "eyJ.a.b", `"r"`, "Pwd1!", "eyJhbGc"} {
		if strings.Contains(all, secret) {
			t.Fatalf("%s kept: %s", secret, all)
		}
	}
	if !strings.Contains(all, "st8-wallet-token") || !strings.Contains(all, "username=a") {
		t.Fatalf("a field that is not a credential was redacted: %s", all)
	}
}

func TestTheInternalSecretsHeaderIsRedactedAndOtherHeadersKept(t *testing.T) {
	outcome := recorder.Step{
		Request: recorder.Request{Method: "POST", Path: "/internal/v1/casino/withdrawal-requests/{requestId}/outcome",
			Headers: map[string]string{"X-Internal-Secret": "dev-wallet-secret", "x-internal-secret": "again", "Content-Type": "application/json"}},
		Response: recorder.Response{Status: 200, Body: json.RawMessage(`{"ok":true}`)},
	}
	r := recorder.Recording{Scenario: "TestS", Steps: []recorder.Step{outcome}}
	h := Merge(r, r).Steps[0].Request.Headers
	if h["X-Internal-Secret"] != "<redacted>" || h["x-internal-secret"] != "<redacted>" || h["Content-Type"] != "application/json" {
		t.Fatalf("headers: %v", h)
	}
	if r.Steps[0].Request.Headers["X-Internal-Secret"] != "dev-wallet-secret" {
		t.Fatal("the merge changed the run it was given")
	}
}

func TestRepeatedReadsCollapseToTheLast(t *testing.T) {
	poll := func(resp string) recorder.Step { return step("/twins/grant-1", ``, resp) }
	act := recorder.Step{Request: recorder.Request{Method: "POST", Path: "/spin"}, Response: recorder.Response{Status: 200}}
	a := recorder.Recording{Scenario: "TestZ", Steps: []recorder.Step{poll(`{"s":"open"}`), act, poll(`{"s":"open"}`), poll(`{"s":"open"}`), poll(`{"s":"used"}`)}}
	b := recorder.Recording{Scenario: "TestZ", Steps: []recorder.Step{poll(`{"s":"open"}`), poll(`{"s":"open"}`), act, poll(`{"s":"used"}`)}}
	m := Merge(a, b)
	if m.Runs != 2 || len(m.Steps) != 3 {
		t.Fatalf("runs %d, %d steps", m.Runs, len(m.Steps))
	}
	if string(m.Steps[2].Response.Body) != `{"s":"used"}` {
		t.Fatalf("the last read was not kept: %s", m.Steps[2].Response.Body)
	}
	if len(Collapse(recorder.Recording{Steps: []recorder.Step{act, act}}).Steps) != 2 {
		t.Fatal("a repeated write was collapsed")
	}
}

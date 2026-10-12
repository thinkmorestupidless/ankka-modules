package main

import (
	"encoding/json"
	"strings"
	"testing"

	"github.com/eitheror-io/ankka-casino/tests/original/recorder"
)

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
	if _, ok := m.Vary["$.player_id"]; !ok {
		t.Fatalf("vary %v", m.Vary)
	}
	if _, ok := m.Vary["$.balance"]; ok {
		t.Fatal("an equal field was marked varying")
	}
	if m.Steps[1].Request.Path != "/players/{c0}/wallet" || m.Steps[0].Capture["c0"] != "$.player_id" {
		t.Fatalf("capture not inferred: %+v / %+v", m.Steps[1].Request, m.Steps[0].Capture)
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

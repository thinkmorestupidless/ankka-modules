package main

import (
	"encoding/json"
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

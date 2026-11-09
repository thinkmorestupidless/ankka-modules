package main

import (
	"encoding/json"
	"strings"
	"testing"

	"github.com/eitheror-io/ankka-casino/tests/original/recorder"
)

func TestAttachTakesTheCallsAboutTheRecordingsPeopleInOrder(t *testing.T) {
	rec := recorder.Recording{Scenario: "TestScenario40", Steps: []recorder.Step{{
		Request:  recorder.Request{Body: json.RawMessage(`{"email":"a@example.com"}`)},
		Response: recorder.Response{Body: json.RawMessage(`{"playerId":"p-1"}`)},
	}}}
	calls := []Received{
		{Host: "track", Path: "/api/v2/entity", Body: json.RawMessage(`{"action":"identify","identifiers":{"id":"p-1"}}`)},
		{Host: "track", Path: "/api/v2/entity", Body: json.RawMessage(`{"action":"identify","identifiers":{"id":"p-2"}}`)},
		{Host: "app", Path: "/v1/send/email", Body: json.RawMessage(`{"to":"a@example.com","identifiers":{"email":"a@example.com"},"message_data":{"verify_link":"https://x/t","amount":"10.00"}}`)},
		{Host: "track", Path: "/api/v2/entity", Body: json.RawMessage(`{"action":"event","name":"deposit_confirmed","identifiers":{"id":"p-1"}}`)},
	}
	if n := Attach(&rec, calls); n != 3 {
		t.Fatalf("took %d calls, want 3: %+v", n, rec.Outbound)
	}
	if rec.Outbound[0].Stub != "crm-track" || rec.Outbound[1].Stub != "crm-app" || !strings.Contains(string(rec.Outbound[2].Request.Body), "deposit_confirmed") {
		t.Fatalf("order: %+v", rec.Outbound)
	}
	if strings.Contains(string(rec.Outbound[1].Request.Body), "https://x/t") || !strings.Contains(string(rec.Outbound[1].Request.Body), `"amount":"10.00"`) {
		t.Fatalf("the link must be redacted and the amount kept: %s", rec.Outbound[1].Request.Body)
	}
}

func TestARecordingNamingNoOneTakesNothing(t *testing.T) {
	rec := recorder.Recording{Scenario: "TestX", Steps: []recorder.Step{{Response: recorder.Response{Status: 200}}}}
	calls := []Received{{Host: "track", Body: json.RawMessage(`{"identifiers":{"id":"p-9"}}`)}}
	if n := Attach(&rec, calls); n != 0 {
		t.Fatalf("took %d", n)
	}
}

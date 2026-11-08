package main

import (
	"reflect"
	"testing"
)

func TestSeededGivesEveryTriggerAStubIdAndKeepsTheMaskedSigningKey(t *testing.T) {
	found := map[string]any{
		"siteId": "", "trackApiKey": "", "appApiKey": "", "region": "eu", "webhookSigningKey": "abc*****",
		"fromEmail": "",
		"triggers": map[string]any{
			"DepositConfirmed": map[string]any{"transactionalId": ""},
			"PaymentFailed":    map[string]any{"transactionalId": "9"},
		},
	}
	got := Seeded(found, Stub{"stub-site", "stub-track-key", "stub-app-key"})
	if got["siteId"] != "stub-site" || got["trackApiKey"] != "stub-track-key" || got["appApiKey"] != "stub-app-key" {
		t.Fatalf("credentials: %v", got)
	}
	if got["region"] != "eu" || got["webhookSigningKey"] != "abc*****" || got["fromEmail"] != "recording@example.com" {
		t.Fatalf("kept fields: %v", got)
	}
	want := map[string]any{
		"DepositConfirmed": map[string]any{"transactionalId": "stub-DepositConfirmed"},
		"PaymentFailed":    map[string]any{"transactionalId": "stub-PaymentFailed"},
	}
	if !reflect.DeepEqual(got["triggers"], want) {
		t.Fatalf("triggers: %v", got["triggers"])
	}
	if names := Triggers(got); !reflect.DeepEqual(names, []string{"DepositConfirmed", "PaymentFailed"}) {
		t.Fatalf("names: %v", names)
	}
}

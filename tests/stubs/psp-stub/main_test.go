package main

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestEveryProviderHasItsPortAndAnswersHealthz(t *testing.T) {
	ports := map[string]bool{}
	for _, p := range providers {
		if ports[p.port] {
			t.Fatalf("port %s twice", p.port)
		}
		ports[p.port] = true
		stub, handler := p.start(p.getenv)
		srv := httptest.NewServer(stub.Handler(handler))
		resp, err := http.Get(srv.URL + "/healthz")
		srv.Close()
		if err != nil || resp.StatusCode != http.StatusOK || stub.Name != p.name {
			t.Fatalf("%s: healthz %v %v (stub %q)", p.name, resp, err, stub.Name)
		}
	}
	if len(providers) != 9 {
		t.Fatalf("%d providers", len(providers))
	}
}

func TestTheWebhookAddressDefaultsToTheGatewayURL(t *testing.T) {
	cozeni := providers[1]
	t.Setenv("PSP_STUB_GATEWAY_URL", "http://host.testcontainers.internal:9001/")
	if got := cozeni.getenv("COZENI_STUB_WEBHOOK_URL"); got != "http://host.testcontainers.internal:9001/v1/webhooks/cozeni" {
		t.Fatalf("default %q", got)
	}
	t.Setenv("COZENI_STUB_WEBHOOK_URL", "http://elsewhere/hook")
	if got := cozeni.getenv("COZENI_STUB_WEBHOOK_URL"); got != "http://elsewhere/hook" {
		t.Fatalf("override %q", got)
	}
	if got := cozeni.getenv("COZENI_STUB_API_KEY"); got != "" {
		t.Fatalf("other variables are untouched: %q", got)
	}
}

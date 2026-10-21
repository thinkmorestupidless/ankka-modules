// Command psp-stub serves the nine payment-provider stubs in one programme, each on its own port
// (spec 005 FR-025, research R15): the original's psp-gateway and the port are both pointed at it,
// so a deposit or payout runs with no request leaving the machine. See README.md.
package main

import (
	"log"
	"net/http"
	"os"
	"strings"

	"github.com/eitheror-io/ankka-casino/tests/stubs/bouncemoney"
	"github.com/eitheror-io/ankka-casino/tests/stubs/cozeni"
	"github.com/eitheror-io/ankka-casino/tests/stubs/digitarium"
	"github.com/eitheror-io/ankka-casino/tests/stubs/dreamzpay"
	"github.com/eitheror-io/ankka-casino/tests/stubs/eupaymentz"
	"github.com/eitheror-io/ankka-casino/tests/stubs/gigadat"
	"github.com/eitheror-io/ankka-casino/tests/stubs/internal/stubhttp"
	"github.com/eitheror-io/ankka-casino/tests/stubs/morphosis"
	"github.com/eitheror-io/ankka-casino/tests/stubs/t365"
	"github.com/eitheror-io/ankka-casino/tests/stubs/unityfinance"
)

// provider is one stub: its name, port, environment prefix, the gateway's webhook route for it
// (specs/005-psp-gateway/contracts/routes.md) and how to start it.
type provider struct {
	name, port, env, webhook string
	start                    func(getenv func(string) string) (*stubhttp.Stub, http.Handler)
}

var providers = []provider{
	{"bouncemoney", "18101", "BOUNCEMONEY", "/v1/webhooks/bouncemoney", bouncemoney.Start},
	{"cozeni", "18102", "COZENI", "/v1/webhooks/cozeni", cozeni.Start},
	{"dreamzpay", "18103", "DREAMZPAY", "/v1/webhooks/dreamzpay", dreamzpay.Start},
	{"eupaymentz", "18104", "EUPAYMENTZ", "/v1/webhooks/eupaymentz", eupaymentz.Start},
	{"gigadat", "18105", "GIGADAT", "/v1/webhooks/gigadat", gigadat.Start},
	{"morphosis", "18106", "MORPHOSIS", "/v1/webhooks/morphosis", morphosis.Start},
	{"t365", "18107", "T365", "/v1/webhooks/t365", t365.Start},
	{"unityfinance", "18108", "UNITYFINANCE", "/v1/webhooks/unityfinance", unityfinance.Start},
	{"digitarium", "18109", "DIGITARIUM", "/v1/webhooks/digitarium", digitarium.Start},
}

// getenv is os.Getenv for one provider, with <PROVIDER>_STUB_WEBHOOK_URL defaulting to
// PSP_STUB_GATEWAY_URL plus the provider's webhook route when the gateway's address is given.
func (p provider) getenv(name string) string {
	v := os.Getenv(name)
	if v == "" && name == p.env+"_STUB_WEBHOOK_URL" {
		if gw := strings.TrimRight(os.Getenv("PSP_STUB_GATEWAY_URL"), "/"); gw != "" {
			return gw + p.webhook
		}
	}
	return v
}

func main() {
	only := map[string]bool{}
	for _, n := range strings.Split(os.Getenv("PSP_STUB_PROVIDERS"), ",") {
		if n = strings.TrimSpace(n); n != "" {
			only[n] = true
		}
	}
	failed := make(chan error)
	started := 0
	for _, p := range providers {
		if len(only) > 0 && !only[p.name] {
			continue
		}
		port := p.port
		if v := os.Getenv(p.env + "_STUB_PORT"); v != "" {
			port = v
		}
		stub, handler := p.start(p.getenv)
		log.Printf("psp-stub: %s on :%s (webhooks to %q)", p.name, port, p.getenv(p.env+"_STUB_WEBHOOK_URL"))
		server := &http.Server{Addr: ":" + port, Handler: stub.Handler(handler)}
		go func() { failed <- server.ListenAndServe() }()
		started++
	}
	if started == 0 {
		log.Fatalf("psp-stub: PSP_STUB_PROVIDERS=%q names none of the providers", os.Getenv("PSP_STUB_PROVIDERS"))
	}
	log.Fatal(<-failed)
}

// Command preflight refuses to start a recording of the original while any outside system it calls
// has no stub answering (spec 001 FR-029): every payment adapter under the original's
// services/psp-gateway/internal/ (each on its psp-stub port), and the game provider; and, for a
// recording of the gateway (spec 005), the gateway driver's receiver, which stands in for
// merchant-service and casino-service. Exit 0 when all answer, 1 naming each that does not, 2 when
// the original cannot be read.
//
//	go run ./original/preflight -original ../../eitheror-platform
//	go run ./original/preflight -original ../../eitheror-platform -receiver http://localhost:18110/healthz
package main

import (
	"flag"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"
)

// NotAdapters are the gateway's own packages, not providers.
var NotAdapters = map[string]bool{"config": true, "handler": true, "merchant": true, "simulator": true, "stub": true, "substrate": true}

// Dropped are adapters the rebuild does not carry over, so the original is not recorded calling them (spec 005).
var Dropped = map[string]string{"connecx": "ConnecX is not carried over (spec 005)"}

// Stubs is each outside system's stub and where it answers its health route by default: the nine
// payment providers on psp-stub's ports (tests/stubs/psp-stub/README.md; Unity Finance there too,
// not the original's tests/uf-stub, which speaks superseded shapes), and the game provider.
var Stubs = map[string]string{
	"st8":          "http://localhost:8091/health", // the original's fake-st8
	"bouncemoney":  "http://localhost:18101/healthz",
	"cozeni":       "http://localhost:18102/healthz",
	"dreamzpay":    "http://localhost:18103/healthz",
	"eupaymentz":   "http://localhost:18104/healthz",
	"gigadat":      "http://localhost:18105/healthz",
	"morphosis":    "http://localhost:18106/healthz",
	"t365":         "http://localhost:18107/healthz",
	"unityfinance": "http://localhost:18108/healthz",
	"digitarium":   "http://localhost:18109/healthz",
}

// Receiver is the name the gateway driver's receiver is reported under.
const Receiver = "gateway-driver receiver"

// Providers lists what the original calls: its adapters, less the dropped ones, plus the game provider.
func Providers(original string) ([]string, error) {
	entries, err := os.ReadDir(filepath.Join(original, "services", "psp-gateway", "internal"))
	if err != nil {
		return nil, err
	}
	providers := []string{"st8"}
	for _, e := range entries {
		if e.IsDir() && !NotAdapters[e.Name()] {
			if _, dropped := Dropped[e.Name()]; !dropped {
				providers = append(providers, e.Name())
			}
		}
	}
	sort.Strings(providers)
	return providers, nil
}

// Missing returns, for each provider, why it has no stub answering; empty when every one answers.
func Missing(providers []string, url func(string) string, client *http.Client) []string {
	var missing []string
	for _, p := range providers {
		u := url(p)
		if u == "" {
			missing = append(missing, fmt.Sprintf("%s: the original calls it and no stub is known for it", p))
			continue
		}
		resp, err := client.Get(u)
		if err != nil {
			missing = append(missing, fmt.Sprintf("%s: no stub answers at %s (%v)", p, u, err))
			continue
		}
		_ = resp.Body.Close()
		if resp.StatusCode != http.StatusOK {
			missing = append(missing, fmt.Sprintf("%s: the stub at %s answered %d", p, u, resp.StatusCode))
		}
	}
	return missing
}

// URL is a stub's health address: `<PROVIDER>_STUB_HEALTH` overrides the default.
func URL(p string) string {
	if v := os.Getenv(strings.ToUpper(p) + "_STUB_HEALTH"); v != "" {
		return v
	}
	return Stubs[p]
}

// Systems is what a recording must find answering: the providers, and the receiver at its health
// address when one is given, with each one's address.
func Systems(providers []string, receiver string) ([]string, func(string) string) {
	if receiver == "" {
		return providers, URL
	}
	return append(append([]string(nil), providers...), Receiver), func(p string) string {
		if p == Receiver {
			return receiver
		}
		return URL(p)
	}
}

func main() {
	original := flag.String("original", "../../eitheror-platform", "the original's checkout")
	receiver := flag.String("receiver", os.Getenv("GATEWAY_RECEIVER_HEALTH"),
		"the gateway driver's receiver's health address, checked when given (a recording of the gateway)")
	flag.Parse()
	providers, err := Providers(*original)
	if err != nil {
		fmt.Fprintf(os.Stderr, "cannot read the original's adapters: %v\n", err)
		os.Exit(2)
	}
	systems, url := Systems(providers, *receiver)
	missing := Missing(systems, url, &http.Client{Timeout: 3 * time.Second})
	for _, m := range missing {
		fmt.Println("refused", m)
	}
	if len(missing) > 0 {
		fmt.Printf("recording refused: %d of %d outside systems have no stub answering\n", len(missing), len(systems))
		os.Exit(1)
	}
	fmt.Printf("every outside system the original calls is stubbed: %s\n", strings.Join(systems, ", "))
}

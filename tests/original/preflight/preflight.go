// Command preflight refuses to start a recording of the original while any outside system it calls
// has no stub answering (spec 001 FR-029): every payment adapter under the original's
// services/psp-gateway/internal/ (each on its psp-stub port), and the game provider; and, for a
// recording of the gateway (spec 005), the gateway driver's receiver, which stands in for
// merchant-service and casino-service; and, for a recording of the cashier edge (spec 006), the
// original's cashier route itself, so a run does not record a merchant-service that is not up; and, for a
// recording of attribution (spec 012), the original's affiliate service. Exit 0 when all answer, 1 naming each that does not, 2 when
// the original cannot be read.
//
//	go run ./original/preflight -original ../../eitheror-platform
//	go run ./original/preflight -original ../../eitheror-platform -receiver http://localhost:18110/healthz
//	go run ./original/preflight -original ../../eitheror-platform -cashier http://localhost:8085
//	go run ./original/preflight -original ../../eitheror-platform -affiliate http://localhost:8082
//	go run ./original/preflight -original ../../eitheror-platform -register http://localhost:8085 -brand spinvibe
//
// For a recording of players (spec 008) the original's registration route must answer too.
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

// Affiliate is the name the original's affiliate service is reported under.
const Affiliate = "affiliate-service"

// AffiliateAnswers says why the affiliate service at base does not answer its health route, or ""
// when it does (a recording of attribution, spec 012).
func AffiliateAnswers(base string, client *http.Client) string {
	resp, err := client.Get(strings.TrimSuffix(base, "/") + "/health")
	if err != nil {
		return fmt.Sprintf("%s: nothing answers at %s (%v)", Affiliate, base, err)
	}
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Sprintf("%s: %s answered %d", Affiliate, base, resp.StatusCode)
	}
	return ""
}

// Cashier is the name the original's cashier route is reported under.
const Cashier = "merchant-service cashier"

// CashierAnswers says why the cashier route at base does not answer, or "" when it does: the browser
// preflight the original serves on `/v1/cashier/sessions` (CashierRoutes), which needs no key and
// makes no session.
func CashierAnswers(base string, client *http.Client) string {
	req, _ := http.NewRequest(http.MethodOptions, strings.TrimSuffix(base, "/")+"/v1/cashier/sessions", nil)
	resp, err := client.Do(req)
	if err != nil {
		return fmt.Sprintf("%s: nothing answers at %s (%v)", Cashier, base, err)
	}
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Sprintf("%s: %s answered %d", Cashier, base, resp.StatusCode)
	}
	return ""
}

// Register is the name the original's registration route is reported under.
const Register = "casino-service registration"

// RegisterAnswers says why the registration route at base does not answer for brand, or "" when it
// does: an empty registration, which the original refuses 400 naming the required fields and which
// creates nothing (PlayerRoutes), so a players recording does not start on a casino-service that is
// not up or a brand it does not serve.
func RegisterAnswers(base, brand string, client *http.Client) string {
	url := strings.TrimSuffix(base, "/") + "/v1/casino/players/" + brand + "/register"
	resp, err := client.Post(url, "application/json", strings.NewReader("{}"))
	if err != nil {
		return fmt.Sprintf("%s: nothing answers at %s (%v)", Register, base, err)
	}
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusBadRequest {
		return fmt.Sprintf("%s: %s answered %d to an empty registration (want 400)", Register, url, resp.StatusCode)
	}
	return ""
}

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
	cashier := flag.String("cashier", "", "the original merchant-service, whose cashier route is checked when given (a recording of the cashier edge)")
	register := flag.String("register", "", "the original casino-service, whose registration route is checked when given (a recording of players)")
	brand := flag.String("brand", "spinvibe", "the brand the registration route is checked for")
	affiliate := flag.String("affiliate", "", "the original affiliate-service, whose health route is checked when given (a recording of attribution)")
	flag.Parse()
	providers, err := Providers(*original)
	if err != nil {
		fmt.Fprintf(os.Stderr, "cannot read the original's adapters: %v\n", err)
		os.Exit(2)
	}
	systems, url := Systems(providers, *receiver)
	client := &http.Client{Timeout: 3 * time.Second}
	missing := Missing(systems, url, client)
	if *cashier != "" {
		systems = append(systems, Cashier)
		if why := CashierAnswers(*cashier, client); why != "" {
			missing = append(missing, why)
		}
	}
	if *register != "" {
		systems = append(systems, Register)
		if why := RegisterAnswers(*register, *brand, client); why != "" {
			missing = append(missing, why)
		}
	}
	if *affiliate != "" {
		systems = append(systems, Affiliate)
		if why := AffiliateAnswers(*affiliate, client); why != "" {
			missing = append(missing, why)
		}
	}
	for _, m := range missing {
		fmt.Println("refused", m)
	}
	if len(missing) > 0 {
		fmt.Printf("recording refused: %d of %d outside systems have no stub answering\n", len(missing), len(systems))
		os.Exit(1)
	}
	fmt.Printf("every outside system the original calls is stubbed: %s\n", strings.Join(systems, ", "))
}

package main

import (
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func original(t *testing.T, adapters ...string) string {
	root := t.TempDir()
	for _, a := range adapters {
		if err := os.MkdirAll(filepath.Join(root, "services", "psp-gateway", "internal", a), 0o755); err != nil {
			t.Fatal(err)
		}
	}
	return root
}

func TestProvidersAreTheAdaptersLessDroppedPlusTheGameProvider(t *testing.T) {
	ps, err := Providers(original(t, "config", "handler", "connecx", "dreamzpay", "unityfinance"))
	if err != nil {
		t.Fatal(err)
	}
	if strings.Join(ps, ",") != "dreamzpay,st8,unityfinance" {
		t.Fatalf("providers %v", ps)
	}
}

func TestRecordingIsRefusedNamingAProviderWithNoStub(t *testing.T) {
	up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { w.WriteHeader(200) }))
	defer up.Close()
	urls := map[string]string{"dreamzpay": up.URL, "st8": up.URL, "newpay": ""}
	missing := Missing([]string{"dreamzpay", "newpay", "st8"}, func(p string) string { return urls[p] }, up.Client())
	if len(missing) != 1 || !strings.HasPrefix(missing[0], "newpay:") {
		t.Fatalf("missing %v", missing)
	}
}

func TestAStubThatDoesNotAnswerIsMissing(t *testing.T) {
	missing := Missing([]string{"gigadat"}, func(string) string { return "http://127.0.0.1:1/healthz" }, http.DefaultClient)
	if len(missing) != 1 || !strings.Contains(missing[0], "gigadat") {
		t.Fatalf("missing %v", missing)
	}
}

func TestTheNineProvidersAreProbedOnPspStubsPorts(t *testing.T) {
	want := map[string]string{"bouncemoney": "18101", "cozeni": "18102", "dreamzpay": "18103", "eupaymentz": "18104",
		"gigadat": "18105", "morphosis": "18106", "t365": "18107", "unityfinance": "18108", "digitarium": "18109"}
	for p, port := range want {
		if Stubs[p] != "http://localhost:"+port+"/healthz" {
			t.Errorf("%s is probed at %q, not psp-stub's %s", p, Stubs[p], port)
		}
	}
	if len(Stubs) != len(want)+1 {
		t.Errorf("stubs %v", Stubs)
	}
}

func TestTheReceiverIsCheckedOnlyWhenGivenAndRefusedWhenSilent(t *testing.T) {
	up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { w.WriteHeader(200) }))
	defer up.Close()
	systems, _ := Systems([]string{"cozeni"}, "")
	if strings.Join(systems, ",") != "cozeni" {
		t.Fatalf("with no receiver: %v", systems)
	}
	systems, url := Systems([]string{"cozeni"}, up.URL+"/healthz")
	if strings.Join(systems, ",") != "cozeni,"+Receiver || url(Receiver) != up.URL+"/healthz" || url("cozeni") != Stubs["cozeni"] {
		t.Fatalf("with a receiver: %v", systems)
	}
	if m := Missing([]string{Receiver}, url, up.Client()); len(m) != 0 {
		t.Fatalf("an answering receiver: %v", m)
	}
	_, url = Systems(nil, "http://127.0.0.1:1/healthz")
	if m := Missing([]string{Receiver}, url, http.DefaultClient); len(m) != 1 || !strings.HasPrefix(m[0], Receiver) {
		t.Fatalf("a silent receiver: %v", m)
	}
}

func TestTheCashierRouteMustAnswerItsPreflight(t *testing.T) {
	up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodOptions && r.URL.Path == "/v1/cashier/sessions" {
			w.WriteHeader(200)
			return
		}
		w.WriteHeader(404)
	}))
	defer up.Close()
	if why := CashierAnswers(up.URL, up.Client()); why != "" {
		t.Fatalf("an answering cashier: %s", why)
	}
	if why := CashierAnswers(up.URL+"/elsewhere", up.Client()); !strings.HasPrefix(why, Cashier) {
		t.Fatalf("a cashier answering 404: %q", why)
	}
	if why := CashierAnswers("http://127.0.0.1:1", http.DefaultClient); !strings.HasPrefix(why, Cashier) {
		t.Fatalf("a silent cashier: %q", why)
	}
}

func TestTheAffiliateServiceMustAnswerItsHealthRoute(t *testing.T) {
	up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/health" {
			w.WriteHeader(200)
			return
		}
		w.WriteHeader(404)
	}))
	defer up.Close()
	if why := AffiliateAnswers(up.URL, up.Client()); why != "" {
		t.Fatalf("an answering affiliate service: %s", why)
	}
	if why := AffiliateAnswers(up.URL+"/elsewhere", up.Client()); !strings.HasPrefix(why, Affiliate) {
		t.Fatalf("an affiliate service answering 404: %q", why)
	}
	if why := AffiliateAnswers("http://127.0.0.1:1", http.DefaultClient); !strings.HasPrefix(why, Affiliate) {
		t.Fatalf("a silent affiliate service: %q", why)
	}
}

func TestEveryAdapterOfTheOriginalHasAStubKnown(t *testing.T) {
	ps, err := Providers("../../../../eitheror-platform")
	if err != nil {
		t.Skipf("the original is not checked out beside this repository: %v", err)
	}
	for _, p := range ps {
		if Stubs[p] == "" {
			t.Errorf("the original calls %s and preflight knows no stub for it", p)
		}
	}
}

func TestTheRegistrationRouteMustRefuseAnEmptyRegistration(t *testing.T) {
	up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodPost && r.URL.Path == "/v1/casino/players/spinvibe/register" {
			w.WriteHeader(400)
			return
		}
		w.WriteHeader(404)
	}))
	defer up.Close()
	if why := RegisterAnswers(up.URL, "spinvibe", up.Client()); why != "" {
		t.Fatalf("an answering route: %s", why)
	}
	if why := RegisterAnswers(up.URL, "goldvibe", up.Client()); !strings.HasPrefix(why, Register) {
		t.Fatalf("a brand the original does not serve: %q", why)
	}
	if why := RegisterAnswers("http://127.0.0.1:1", "spinvibe", http.DefaultClient); !strings.HasPrefix(why, Register) {
		t.Fatalf("a silent casino-service: %q", why)
	}
}

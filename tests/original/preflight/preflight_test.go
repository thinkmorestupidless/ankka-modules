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

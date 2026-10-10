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

//go:build e2e

package rewardsdriver

// Running the driver against a local original (never a shared one; tests/original/README.md), its
// kind stack with casino-service, Keycloak, substrate and fake-st8 reachable from this machine, and its
// Unleash port-forwarded for the kill switch's scenario:
//
//	cd tests && go test -tags e2e ./original/rewards-driver/... -count=1 -timeout 60m -args \
//	    -casino-url http://localhost:8091 -keycloak-url http://localhost:8180 -substrate-url http://localhost:9000 \
//	    -fake-st8-url http://localhost:30096 -unleash-url http://localhost:4242 -brand spinvibe \
//	    -operator-client casino-backoffice
//
// with EITHEROR_RECORD_TO naming the run's directory, EITHEROR_ORIGINAL_COMMIT the original's commit,
// the operator in TEST_OPERATOR_EMAIL and TEST_OPERATOR_PASSWORD (brand_admin at the brand), and
// Unleash's admin token in EITHEROR_UNLEASH_TOKEN (without it, the kill switch's scenario is skipped).
// Secrets and passwords are read from the environment only, never flags, so they stay out of the
// shell's history. Each scenario writes <dir>/rewards/<scenario>.json; run twice and merge with
// ./original/merge into tests/port.

import (
	"flag"
	"net/http"
	"os"
	"testing"
	"time"
)

var (
	casinoURL     = flag.String("casino-url", "http://localhost:8091", "the original's casino-service")
	keycloakURL   = flag.String("keycloak-url", "http://localhost:8180", "the original's Keycloak")
	substrateURL  = flag.String("substrate-url", envOr("EITHEROR_SUBSTRATE_URL", "http://localhost:9000"), "the original's substrate, which casino-service is pointed at")
	fakeSt8URL    = flag.String("fake-st8-url", envOr("EITHEROR_FAKE_ST8_URL", "http://localhost:30096"), "the original's fake-st8 (its NodePort)")
	unleashURL    = flag.String("unleash-url", envOr("EITHEROR_UNLEASH_URL", "http://localhost:4242"), "the original's Unleash (port-forward svc/eitheror-bundle-unleash 4242)")
	unleashProj   = flag.String("unleash-project", "default", "the Unleash project holding casino-service's flags")
	unleashEnv    = flag.String("unleash-environment", "development", "the Unleash environment casino-service's token reads")
	brand         = flag.String("brand", envOr("EITHEROR_CASINO_BRAND_ID", "spinvibe"), "the original's seeded brand; its realm is casino-<brand>")
	operatorRealm = flag.String("operator-realm", "eitheror", "the realm the backoffice signs operators in to")
	operatorApp   = flag.String("operator-client", "backoffice", "the backoffice's client in that realm")
	operatorUser  = flag.String("operator-user", os.Getenv("TEST_OPERATOR_EMAIL"), "an operator holding brand_admin at the brand")
)

func envOr(name, fallback string) string {
	if v := os.Getenv(name); v != "" {
		return v
	}
	return fallback
}

// config is the run's configuration, read after flag.Parse, with the operator's sign-in fetched now.
func config(t *testing.T) Config {
	t.Helper()
	client := &http.Client{Timeout: 30 * time.Second}
	password := os.Getenv("TEST_OPERATOR_PASSWORD")
	if *operatorUser == "" || password == "" {
		t.Fatalf("no %s operator or TEST_OPERATOR_PASSWORD: the scenarios act as one", *operatorRealm)
	}
	token, err := PasswordToken(client, *keycloakURL, *operatorRealm, *operatorApp, *operatorUser, password)
	if err != nil {
		t.Fatal(err)
	}
	return Config{
		CasinoURL:          *casinoURL,
		KeycloakURL:        *keycloakURL,
		SubstrateURL:       *substrateURL,
		FakeSt8URL:         *fakeSt8URL,
		UnleashURL:         *unleashURL,
		UnleashToken:       os.Getenv("EITHEROR_UNLEASH_TOKEN"),
		UnleashProject:     *unleashProj,
		UnleashEnvironment: *unleashEnv,
		Brand:              *brand,
		SigningSecret:      envOr("EMAIL_TOKEN_SIGNING_SECRET", "dev-email-token-secret"),
		OperatorToken:      token,
		RecordTo:           os.Getenv("EITHEROR_RECORD_TO"),
		Commit:             os.Getenv("EITHEROR_ORIGINAL_COMMIT"),
		Client:             client,
	}
}

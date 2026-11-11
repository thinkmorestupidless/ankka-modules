//go:build e2e

package reportingdriver

// Running the driver against a local original (never a shared one; tests/original/README.md,
// *Recording the reports*), its kind stack with casino-service, Keycloak and fake-st8 reachable from
// this machine, and wallet-service's internal API and Unleash port-forwarded:
//
//	cd tests && go test -tags e2e ./original/reporting-driver/... -count=1 -timeout 30m -args \
//	    -casino-url http://localhost:8091 -keycloak-url http://localhost:8180 -fake-st8-url http://localhost:8096 \
//	    -wallet-internal-url http://localhost:19001 -unleash-url http://localhost:4242 -unleash-environment default \
//	    -brand spinvibe -flag-off-brand reports-off -operator-client casino-backoffice
//
// with EITHEROR_RECORD_TO naming the run's directory, EITHEROR_ORIGINAL_COMMIT the original's commit,
// the operators in EITHEROR_OPERATOR_USER (one who may read the reports) and EITHEROR_SUPPORT_USER (one
// who may not) and their passwords in EITHEROR_OPERATOR_PASSWORD and EITHEROR_SUPPORT_PASSWORD, the
// wallet's internal secret in WALLET_SERVICE_INTERNAL_SECRET, and Unleash's admin token in
// EITHEROR_UNLEASH_TOKEN (without it the Reports flag is taken as it is). Secrets and passwords are
// read from the environment only, never flags, so they stay out of the shell's history. Each recording
// writes <dir>/reporting/<name>.json; run twice and merge with ./original/merge into tests/port.

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
	fakeSt8URL    = flag.String("fake-st8-url", envOr("EITHEROR_FAKE_ST8_URL", "http://localhost:8096"), "the original's fake-st8 (its node port's host mapping)")
	walletIntURL  = flag.String("wallet-internal-url", envOr("EITHEROR_WALLET_INTERNAL_URL", "http://localhost:19001"), "wallet-service's internal API (port-forward svc/wallet-service 19001:9001)")
	unleashURL    = flag.String("unleash-url", envOr("EITHEROR_UNLEASH_URL", "http://localhost:4242"), "the original's Unleash")
	unleashProj   = flag.String("unleash-project", "default", "the Unleash project holding casino-service's flags")
	unleashEnv    = flag.String("unleash-environment", "development", "the Unleash environment casino-service's token reads")
	brand         = flag.String("brand", envOr("EITHEROR_CASINO_BRAND_ID", "spinvibe"), "the original's seeded brand; its realm is casino-<brand>")
	flagOffBrand  = flag.String("flag-off-brand", "reports-off", "a brand the Reports flag is off for; it need not exist")
	operatorRealm = flag.String("operator-realm", "eitheror", "the realm the backoffice signs operators in to")
	operatorApp   = flag.String("operator-client", "casino-backoffice", "the backoffice's client in that realm")
	operatorUser  = flag.String("operator-user", os.Getenv("EITHEROR_OPERATOR_USER"), "an operator holding platform_admin, resource_admin or brand_admin at the brand")
	supportUser   = flag.String("support-user", os.Getenv("EITHEROR_SUPPORT_USER"), "an operator of the brand holding none of those (support_agent)")
)

func envOr(name, fallback string) string {
	if v := os.Getenv(name); v != "" {
		return v
	}
	return fallback
}

// config is the run's configuration, read after flag.Parse, with the operators' sign-ins fetched now.
// The support operator is optional: without one, the refused role's recording is skipped.
func config(t *testing.T) Config {
	t.Helper()
	client := &http.Client{Timeout: 60 * time.Second}
	token := func(user, passwordVar string, required bool) string {
		password := os.Getenv(passwordVar)
		if user == "" || password == "" {
			if required {
				t.Fatalf("no %s operator or %s: the recordings act as one", *operatorRealm, passwordVar)
			}
			return ""
		}
		v, err := PasswordToken(client, *keycloakURL, *operatorRealm, *operatorApp, user, password)
		if err != nil {
			t.Fatal(err)
		}
		return v
	}
	return Config{
		CasinoURL:          *casinoURL,
		KeycloakURL:        *keycloakURL,
		FakeSt8URL:         *fakeSt8URL,
		WalletInternalURL:  *walletIntURL,
		InternalSecret:     os.Getenv("WALLET_SERVICE_INTERNAL_SECRET"),
		UnleashURL:         *unleashURL,
		UnleashToken:       os.Getenv("EITHEROR_UNLEASH_TOKEN"),
		UnleashProject:     *unleashProj,
		UnleashEnvironment: *unleashEnv,
		Brand:              *brand,
		FlagOffBrand:       *flagOffBrand,
		SigningSecret:      envOr("EMAIL_TOKEN_SIGNING_SECRET", "dev-email-token-secret"),
		OperatorToken:      token(*operatorUser, "EITHEROR_OPERATOR_PASSWORD", true),
		SupportToken:       token(*supportUser, "EITHEROR_SUPPORT_PASSWORD", false),
		RecordTo:           os.Getenv("EITHEROR_RECORD_TO"),
		Commit:             os.Getenv("EITHEROR_ORIGINAL_COMMIT"),
		Client:             client,
	}
}

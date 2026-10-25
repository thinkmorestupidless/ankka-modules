//go:build e2e

package playersdriver

// Running the driver against a local original (never a shared one; tests/original/README.md), its
// KinD stack with casino-service, Keycloak and substrate port-forwarded to this machine:
//
//	cd tests && go run ./original/preflight -original ../../eitheror-platform -register http://localhost:8085 -brand spinvibe
//	cd tests && go test -tags e2e ./original/players-driver/... -count=1 -args \
//	    -casino-url http://localhost:8085 -keycloak-url http://localhost:8080 -substrate-url http://localhost:8082 \
//	    -brand spinvibe -operator-user <an operator of the brand> -outsider-user <an operator of no brand>
//
// with EITHEROR_RECORD_TO naming the run's directory, EITHEROR_ORIGINAL_COMMIT the original's commit,
// and the passwords in EITHEROR_KEYCLOAK_ADMIN_PASSWORD, EITHEROR_OPERATOR_PASSWORD and
// EITHEROR_OUTSIDER_PASSWORD (never flags, so they stay out of the shell's history). Each scenario
// writes <dir>/players/<scenario>.json; run twice and merge with ./original/merge into tests/port.

import (
	"flag"
	"net/http"
	"os"
	"testing"
	"time"
)

var (
	casinoURL     = flag.String("casino-url", "http://localhost:8085", "the original's casino-service")
	keycloakURL   = flag.String("keycloak-url", "http://localhost:8080", "the original's Keycloak")
	substrateURL  = flag.String("substrate-url", envOr("EITHEROR_SUBSTRATE_URL", "http://localhost:8082"), "the original's substrate, which casino-service is pointed at")
	brand         = flag.String("brand", envOr("EITHEROR_CASINO_BRAND_ID", "spinvibe"), "the original's seeded brand; its realm is casino-<brand>")
	operatorRealm = flag.String("operator-realm", "eitheror", "the realm the backoffice signs operators in to")
	operatorApp   = flag.String("operator-client", "backoffice", "the backoffice's client in that realm")
	operatorUser  = flag.String("operator-user", os.Getenv("EITHEROR_OPERATOR_USER"), "an operator holding brand_admin at the brand")
	outsiderUser  = flag.String("outsider-user", os.Getenv("EITHEROR_OUTSIDER_USER"), "an operator holding no role at the brand")
)

func envOr(name, fallback string) string {
	if v := os.Getenv(name); v != "" {
		return v
	}
	return fallback
}

// config is the run's configuration, read after flag.Parse, with the run's sign-ins fetched once.
func config(t *testing.T) Config {
	t.Helper()
	client := &http.Client{Timeout: 30 * time.Second}
	token := func(realm, app, user, passwordVar, fallback string) string {
		password := envOr(passwordVar, fallback)
		if user == "" || password == "" {
			t.Fatalf("no %s user or %s: the scenarios act as one", realm, passwordVar)
		}
		v, err := PasswordToken(client, *keycloakURL, realm, app, user, password)
		if err != nil {
			t.Fatal(err)
		}
		return v
	}
	return Config{
		CasinoURL:     *casinoURL,
		KeycloakURL:   *keycloakURL,
		SubstrateURL:  *substrateURL,
		Brand:         *brand,
		SigningSecret: envOr("EMAIL_TOKEN_SIGNING_SECRET", "dev-email-token-secret"),
		AdminToken:    token("master", "admin-cli", "admin", "EITHEROR_KEYCLOAK_ADMIN_PASSWORD", "keycloak-dev-admin"),
		OperatorToken: token(*operatorRealm, *operatorApp, *operatorUser, "EITHEROR_OPERATOR_PASSWORD", ""),
		OutsiderToken: token(*operatorRealm, *operatorApp, *outsiderUser, "EITHEROR_OUTSIDER_PASSWORD", ""),
		RecordTo:      os.Getenv("EITHEROR_RECORD_TO"),
		Commit:        os.Getenv("EITHEROR_ORIGINAL_COMMIT"),
		Client:        client,
	}
}

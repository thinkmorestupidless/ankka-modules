//go:build e2e

package payoutsdriver

// Running the driver against a local original (never a shared one; tests/original/README.md), its
// KinD stack with casino-service, Keycloak, substrate, wallet-service and psp-stub reachable from
// this machine, and psp-gateway pointed at psp-stub:
//
//	cd tests && go test -tags e2e ./original/payouts-driver/... -count=1 -timeout 60m -args \
//	    -casino-url http://localhost:8091 -keycloak-url http://localhost:8180 -substrate-url http://localhost:9000 \
//	    -wallet-url http://localhost:8081 -stub-url http://localhost:18108 -brand spinvibe \
//	    -operator-client casino-backoffice -outsider-user <an operator of no brand>
//
// with EITHEROR_RECORD_TO naming the run's directory, EITHEROR_ORIGINAL_COMMIT the original's commit,
// the operator in TEST_OPERATOR_EMAIL and TEST_OPERATOR_PASSWORD (scenario 251's), the outsider's
// password in EITHEROR_OUTSIDER_PASSWORD, and the original's internal secret in
// WALLET_SERVICE_INTERNAL_SECRET. Secrets and passwords are read from the environment only, never
// flags, so they stay out of the shell's history. Each scenario writes <dir>/payouts/<scenario>.json;
// run twice and merge with ./original/merge into tests/port.

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
	walletURL     = flag.String("wallet-url", envOr("EITHEROR_WALLET_URL", "http://localhost:8092"), "the original's wallet-service (its NodePort)")
	walletIntURL  = flag.String("wallet-internal-url", envOr("EITHEROR_WALLET_INTERNAL_URL", "http://localhost:19001"), "wallet-service's internal API (port-forward svc/wallet-service 19001:9001)")
	stubURL       = flag.String("stub-url", "http://localhost:18108", "psp-stub's Unity Finance, which psp-gateway is pointed at")
	brand         = flag.String("brand", envOr("EITHEROR_CASINO_BRAND_ID", "spinvibe"), "the original's seeded brand; its realm is casino-<brand>")
	operatorRealm = flag.String("operator-realm", "eitheror", "the realm the backoffice signs operators in to")
	operatorApp   = flag.String("operator-client", "backoffice", "the backoffice's client in that realm")
	operatorUser  = flag.String("operator-user", os.Getenv("TEST_OPERATOR_EMAIL"), "an operator holding brand_admin at the brand")
	outsiderUser  = flag.String("outsider-user", os.Getenv("EITHEROR_OUTSIDER_USER"), "an operator holding no role at the brand")
)

func envOr(name, fallback string) string {
	if v := os.Getenv(name); v != "" {
		return v
	}
	return fallback
}

// config is the run's configuration, read after flag.Parse, with the operators' sign-ins fetched now.
func config(t *testing.T) Config {
	t.Helper()
	client := &http.Client{Timeout: 30 * time.Second}
	token := func(user, passwordVar string) string {
		password := os.Getenv(passwordVar)
		if user == "" || password == "" {
			t.Fatalf("no %s user or %s: the scenarios act as one", *operatorRealm, passwordVar)
		}
		v, err := PasswordToken(client, *keycloakURL, *operatorRealm, *operatorApp, user, password)
		if err != nil {
			t.Fatal(err)
		}
		return v
	}
	secret := os.Getenv("WALLET_SERVICE_INTERNAL_SECRET")
	if secret == "" {
		t.Fatal("no WALLET_SERVICE_INTERNAL_SECRET: the outcome route and the wallet's deposit are signed with it")
	}
	return Config{
		CasinoURL:         *casinoURL,
		KeycloakURL:       *keycloakURL,
		SubstrateURL:      *substrateURL,
		WalletURL:         *walletURL,
		WalletInternalURL: *walletIntURL,
		StubURL:           *stubURL,
		Brand:             *brand,
		SigningSecret:     envOr("EMAIL_TOKEN_SIGNING_SECRET", "dev-email-token-secret"),
		InternalSecret:    secret,
		OperatorToken:     token(*operatorUser, "TEST_OPERATOR_PASSWORD"),
		OutsiderToken:     token(*outsiderUser, "EITHEROR_OUTSIDER_PASSWORD"),
		RecordTo:          os.Getenv("EITHEROR_RECORD_TO"),
		Commit:            os.Getenv("EITHEROR_ORIGINAL_COMMIT"),
		Client:            client,
	}
}

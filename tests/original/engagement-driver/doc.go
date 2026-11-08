//go:build e2e

package engagementdriver

// Running the driver against a local original (never a shared one; tests/original/README.md), its
// kind stack with casino-service, Keycloak and substrate reachable from this machine, push-gateway
// port-forwarded, and crm-stub running on the host with the brand's CRM configuration seeded against
// it (`original/crm-seed`, README *Recording the engagement*):
//
//	kubectl --context kind-eitheror-dev -n eitheror port-forward svc/eitheror-bundle-substrate 18080:9000 &
//	kubectl --context kind-eitheror-dev -n eitheror port-forward svc/eitheror-bundle-push-gateway 18096:8096 &
//	cd tests && go test -tags e2e ./original/engagement-driver/... -count=1 -timeout 90m -args \
//	    -casino-url http://localhost:8091 -keycloak-url http://localhost:8180 -substrate-url http://localhost:18080 \
//	    -push-url http://localhost:18096 -crm-stub-url http://localhost:18201 -brand spinvibe \
//	    -operator-client casino-backoffice
//
// push-gateway has no node port on the kind stack (bundle/helm/templates/push-gateway/service.yaml is a
// ClusterIP on 8096, and host port 8096 is fake-st8's node port 30096), hence the port-forward to 18096.
// Its KEYCLOAK_REALMS on kind is `casino-spinvibe` alone (deploy/kind/bundle-values.yaml), so a token
// of the operators' realm is refused 401 by `/v1/presence` and `/v1/stream` unless the recording's
// install adds the operator realm to `pushGateway.keycloakRealms`.
//
// With EITHEROR_RECORD_TO naming the run's directory, EITHEROR_ORIGINAL_COMMIT the original's commit,
// and from the environment:
//
//   - TEST_OPERATOR_EMAIL and TEST_OPERATOR_PASSWORD: an operator holding brand_admin at the brand
//     (the configuration routes take brand_admin, resource_admin or platform_admin);
//   - EMAIL_TOKEN_SIGNING_SECRET: casino-service's verification-link secret ("dev-email-token-secret",
//     the original's default, when unset);
//   - WALLET_SERVICE_INTERNAL_SECRET: casino-service's internal secret, which the reset route takes as
//     `X-Internal-Secret` ("dev-wallet-secret", the original's default and the kind stack's, when unset).
//
// Secrets and passwords are read from the environment only, never flags, so they stay out of the
// shell's history. Each scenario writes <dir>/engagement/<scenario>.json; run twice, attach the CRM
// calls with ./original/crm-captures, and merge with ./original/merge into tests/port. The recycle
// scenario holds its stream for up to -recycle-wait (7 minutes).

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
	substrateURL  = flag.String("substrate-url", envOr("EITHEROR_SUBSTRATE_URL", "http://localhost:18080"), "the original's substrate, which casino-service is pointed at")
	pushURL       = flag.String("push-url", envOr("EITHEROR_PUSH_URL", "http://localhost:18096"), "the original's push-gateway (port-forward svc/eitheror-bundle-push-gateway 18096:8096)")
	crmStubURL    = flag.String("crm-stub-url", envOr("EITHEROR_CRM_STUB_URL", "http://localhost:18201"), "crm-stub's Track port, whose log is read")
	brand         = flag.String("brand", envOr("EITHEROR_CASINO_BRAND_ID", "spinvibe"), "the original's seeded brand; its realm is casino-<brand>")
	operatorRealm = flag.String("operator-realm", "eitheror", "the realm the backoffice signs operators in to")
	operatorApp   = flag.String("operator-client", "casino-backoffice", "the backoffice's client in that realm")
	operatorUser  = flag.String("operator-user", os.Getenv("TEST_OPERATOR_EMAIL"), "an operator holding brand_admin at the brand")
	recycleWait   = flag.Duration("recycle-wait", 7*time.Minute, "how long the recycle scenario holds its stream")
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
		CasinoURL:      *casinoURL,
		KeycloakURL:    *keycloakURL,
		SubstrateURL:   *substrateURL,
		PushURL:        *pushURL,
		CRMStubURL:     *crmStubURL,
		Brand:          *brand,
		SigningSecret:  envOr("EMAIL_TOKEN_SIGNING_SECRET", "dev-email-token-secret"),
		InternalSecret: envOr("WALLET_SERVICE_INTERNAL_SECRET", "dev-wallet-secret"),
		OperatorToken:  token,
		RecordTo:       os.Getenv("EITHEROR_RECORD_TO"),
		Commit:         os.Getenv("EITHEROR_ORIGINAL_COMMIT"),
		Client:         client,
		RecycleWait:    *recycleWait,
	}
}

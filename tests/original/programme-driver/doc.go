//go:build e2e

package programmedriver

// Running the driver against a local original (never a shared one; tests/original/README.md), its
// KinD stack with the affiliate service installed: affiliate-service answers on its NodePort,
// localhost:8093, and Keycloak on localhost:8180. Before a run, the service's Customer.io settings
// (`AFFILIATE_CIO_*` and the holding's `crm_config`) must be empty, so its emails go nowhere:
//
//	cd tests && go test -tags e2e ./original/programme-driver/... -count=1 -args \
//	    -affiliate-url http://localhost:8093 -keycloak-url http://localhost:8180 -brand spinvibe \
//	    -staff-user <a staff member holding brand_admin and brand_spinvibe>
//
// with EITHEROR_RECORD_TO naming the run's directory, EITHEROR_ORIGINAL_COMMIT the original's commit,
// and the passwords in EITHEROR_STAFF_PASSWORD and EITHEROR_KEYCLOAK_ADMIN_PASSWORD (never flags, so
// they stay out of the shell's history). The staff member signs in through the backoffice's client;
// `brand_admin` because the managers routes take no `affiliate_manager`. Each affiliate a scenario
// makes is given a password through the master realm's admin API and signs in through
// `affiliate-portal` (Session.SignIn): the original's accept-invitation route needs the token it only
// emails. Each scenario writes <dir>/programme/<scenario>.json; run twice and merge with
// ./original/merge into tests/port.
//
// The run changes the brand: its settings are put at the original's defaults with no fees, its
// programme page is changed, and every close closes the period for all of the brand's affiliates.

import (
	"flag"
	"net/http"
	"os"
	"testing"
	"time"
)

var (
	affiliateURL = flag.String("affiliate-url", envOr("EITHEROR_AFFILIATE_URL", "http://localhost:8093"), "the original's affiliate-service")
	keycloakURL  = flag.String("keycloak-url", envOr("EITHEROR_KEYCLOAK_URL", "http://localhost:8180"), "the original's Keycloak")
	realm        = flag.String("realm", "eitheror", "the realm the service validates operators and affiliates against")
	staffClient  = flag.String("staff-client", "casino-backoffice", "the backoffice's client in that realm")
	portalClient = flag.String("portal-client", "affiliate-portal", "the affiliate portal's client in that realm")
	brand        = flag.String("brand", envOr("EITHEROR_CASINO_BRAND_ID", "spinvibe"), "the original's seeded brand")
	staffUser    = flag.String("staff-user", os.Getenv("EITHEROR_STAFF_USER"), "a staff member holding brand_admin and brand_<brand>")
	secret       = flag.String("internal-secret", envOr("EITHEROR_AFFILIATE_INTERNAL_SECRET", "dev-affiliate-secret"), "the local affiliate-service's internal secret")
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
		AffiliateURL:   *affiliateURL,
		KeycloakURL:    *keycloakURL,
		Realm:          *realm,
		PortalClient:   *portalClient,
		Brand:          *brand,
		InternalSecret: *secret,
		StaffToken:     token(*realm, *staffClient, *staffUser, "EITHEROR_STAFF_PASSWORD", ""),
		AdminToken:     token("master", "admin-cli", "admin", "EITHEROR_KEYCLOAK_ADMIN_PASSWORD", "keycloak-dev-admin"),
		RecordTo:       os.Getenv("EITHEROR_RECORD_TO"),
		Commit:         os.Getenv("EITHEROR_ORIGINAL_COMMIT"),
		Client:         client,
	}
}

//go:build e2e

package publicapidriver

// Running the driver against a local original (never a shared one; tests/original/README.md), its kind
// stack with casino-public-api, casino-service, Keycloak and fake-st8 reachable from this machine, and
// wallet-service's internal API port-forwarded:
//
//	cd tests && go test -tags e2e ./original/public-api-driver/... -count=1 -timeout 150m -args \
//	    -casino-url http://localhost:8091 -keycloak-url http://localhost:8180 -api-url http://localhost:8097 \
//	    -fake-st8-url http://localhost:8096 -wallet-internal-url http://localhost:19001
//
// (`just public-api-record` runs it with the first three) with EITHEROR_RECORD_TO naming the run's
// directory (each recording writes <dir>/public-api/<name>.json), EITHEROR_ORIGINAL_COMMIT the
// original's commit, the operator in EITHEROR_OPERATOR_USER (brand_admin or platform_admin at both
// brands) and their password in EITHEROR_OPERATOR_PASSWORD, Keycloak's admin password in
// EITHEROR_KEYCLOAK_ADMIN_PASSWORD (`keycloak-dev-admin` on kind when unset), the wallet's internal
// secret in WALLET_SERVICE_INTERNAL_SECRET (`dev-wallet-secret` on kind when unset) and the signing
// secret in EMAIL_TOKEN_SIGNING_SECRET. Secrets and passwords are read from the environment only, never
// flags, so they stay out of the shell's history.
//
// The run takes up to two hours: the original's statistics count only whole hours before the run (see
// Config.StatsWait), so the seed waits for the next hour before running them. Pass `-stats-wait=false`
// to record at once, with figures that hold none of the seed's activity. A brand whose players'
// statistics ran in the last 30 minutes is skipped by the original's retention guard, and the seed's
// own run is one, so `player-ranking` waits it out (`-retention-wait`, 31m by default; 0 fails instead).
//
// # Two steps the original offers no route for
//
// Both are made before the run, outside any recording:
//
//  1. The grants. The original's partner grants are Helm values (`casinoPublicApi.partners`), mounted
//     as a file and re-read every minute (grants/entitlements.ts); no route writes one. Write the run's
//     four grants with `-write-grants <file>` (the run writes the file and stops), and give them to the
//     original's casino release on kind as an extra values file (`helm upgrade … -f <file>`, the kind
//     context named explicitly). The run waits up to three minutes for each partner to be admitted.
//  2. Two partner clients. The kind realm defines `partner-local` (spinvibe) and
//     `partner-goldvibe-local` (goldvibe); the run adds `partner-spinvibe-bare-local` and
//     `partner-spinvibe-quota-local` through Keycloak's admin API with the same three hard-coded claims,
//     when they are missing.
//
// # What is seeded through which route
//
// Every other part of the seed is the original's own route, recorded in `public-api-seed` as `setup:`:
//
//   - players: casino-service's `POST /v1/casino/players/{brand}/register`, `/verify-email` (with the
//     link the original would send, minted from the signing secret), `/login`; player-1-of-A's every
//     identifying field through the player's own `PATCH /v1/casino/players/{brand}/{player}`;
//   - deposits: wallet-service's `POST /internal/v1/wallet/{brand}/{player}/{currency}/deposit`, signed
//     as merchant-service signs it;
//   - game sessions: casino-service's `POST /v1/casino/sessions/{brand}/launch`, bound by loading
//     fake-st8's game page; rounds, bets and their transactions: fake-st8's `/test/bet`, `/test/win`
//     and `/test/lose`, which call the ST8 adapter as ST8 does;
//   - bonus awards: an offer through casino-service's `POST /v1/casino/bonus/offers/{brand}`, granted
//     with `POST /v1/casino/bonus/grant/{brand}/{player}`; and free rounds with no offer through the
//     same grant route's `type: FREE_SPINS` shape;
//   - a disabled game: `PUT /v1/casino/brands/{brand}/game-catalog-config`, put back after;
//   - the statistics: casino-service's `POST /internal/v1/casino/{brand}/game-stats/recompute` and
//     `/player-stats/recompute`, the routes the hourly CronJobs call.
//
// The games are fake-st8's catalogue as the cluster has it. The kind stack holds other activity, so
// the brand's lists and rankings hold more than the seed; the seed's own records are the ones named.

import (
	"flag"
	"net/http"
	"os"
	"testing"
	"time"
)

var (
	apiURL        = flag.String("api-url", "http://localhost:8097", "the original's casino-public-api (NodePort 30097)")
	casinoURL     = flag.String("casino-url", "http://localhost:8091", "the original's casino-service")
	keycloakURL   = flag.String("keycloak-url", "http://localhost:8180", "the original's Keycloak")
	fakeSt8URL    = flag.String("fake-st8-url", envOr("EITHEROR_FAKE_ST8_URL", "http://localhost:8096"), "the original's fake-st8 (its node port's host mapping)")
	walletIntURL  = flag.String("wallet-internal-url", envOr("EITHEROR_WALLET_INTERNAL_URL", "http://localhost:19001"), "wallet-service's internal API (port-forward svc/wallet-service 19001:9001)")
	brandA        = flag.String("brand-a", BrandA, "the brand whose records are walked")
	brandB        = flag.String("brand-b", BrandB, "the other brand")
	operatorRealm = flag.String("operator-realm", "eitheror", "the realm the backoffice signs operators in to")
	operatorApp   = flag.String("operator-client", "casino-backoffice", "the backoffice's client in that realm")
	operatorUser  = flag.String("operator-user", os.Getenv("EITHEROR_OPERATOR_USER"), "an operator holding brand_admin or platform_admin at both brands")
	statsWait     = flag.Bool("stats-wait", true, "wait for the next full hour before the statistics runs")
	retentionWait = flag.Duration("retention-wait", 31*time.Minute, "how long a players' statistics run skipped for retention may wait")
	writeGrants   = flag.String("write-grants", "", "write the run's grants as a Helm values file here, and stop")
)

func envOr(name, fallback string) string {
	if v := os.Getenv(name); v != "" {
		return v
	}
	return fallback
}

// config is the run's configuration, read after flag.Parse. Tokens are fetched when a step first needs
// them and again after four minutes; the partners' clients are made in the realm when missing.
func config(t *testing.T) Config {
	t.Helper()
	client := &http.Client{Timeout: 6 * time.Minute}
	kc := Keycloak{Client: client, URL: *keycloakURL, AdminPassword: envOr("EITHEROR_KEYCLOAK_ADMIN_PASSWORD", "keycloak-dev-admin")}
	password := os.Getenv("EITHEROR_OPERATOR_PASSWORD")
	if *operatorUser == "" || password == "" {
		t.Fatal("no EITHEROR_OPERATOR_USER or EITHEROR_OPERATOR_PASSWORD: the offers, the awards and the catalogue are the operator's")
	}
	for _, p := range Partners {
		brand := *brandA
		if p.Letter == "B" {
			brand = *brandB
		}
		if err := kc.EnsureClient(p, brand); err != nil {
			t.Fatal(err)
		}
	}
	return Config{
		APIURL:            *apiURL,
		CasinoURL:         *casinoURL,
		KeycloakURL:       *keycloakURL,
		FakeSt8URL:        *fakeSt8URL,
		WalletInternalURL: *walletIntURL,
		InternalSecret:    envOr("WALLET_SERVICE_INTERNAL_SECRET", "dev-wallet-secret"),
		SigningSecret:     envOr("EMAIL_TOKEN_SIGNING_SECRET", "dev-email-token-secret"),
		BrandA:            *brandA,
		BrandB:            *brandB,
		Token: func(name Auth) (string, error) {
			if name == Operator {
				return kc.PasswordToken(*operatorRealm, *operatorApp, *operatorUser, password)
			}
			p, _ := PartnerOf(name)
			return kc.ClientToken(p.ClientID)
		},
		RecordTo:      os.Getenv("EITHEROR_RECORD_TO"),
		Commit:        os.Getenv("EITHEROR_ORIGINAL_COMMIT"),
		Client:        client,
		StatsWait:     *statsWait,
		RetentionWait: *retentionWait,
	}
}

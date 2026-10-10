//go:build e2e

package walletdriver

// Running the driver against a local original (never a shared one; tests/original/README.md):
//
//	cd tests && go test -tags e2e ./original/wallet-driver/... -count=1 -args \
//	    -wallet-url http://localhost:8081 -substrate-url http://localhost:8080 -brand spinvibe
//
// with EITHEROR_RECORD_TO naming the run's directory and EITHEROR_ORIGINAL_COMMIT the original's
// commit. Each scenario writes <dir>/wallet/<scenario>.json; run twice and merge with
// ./original/merge into tests/port.

import (
	"flag"
	"os"
	"testing"
)

var (
	walletURL    = flag.String("wallet-url", "http://localhost:8081", "the original's wallet-service (`just dev-wallet-service` serves 8081)")
	substrateURL = flag.String("substrate-url", envOr("EITHEROR_SUBSTRATE_URL", "http://localhost:8080"), "the original's substrate, which wallet-service is pointed at")
	brand        = flag.String("brand", envOr("EITHEROR_CASINO_BRAND_ID", "spinvibe"), "the original's seeded brand")
	secret       = flag.String("secret", envOr("EITHEROR_WALLET_INTERNAL_SECRET", "dev-wallet-secret"), "WALLET_INTERNAL_SECRET of the local wallet-service")
)

func envOr(name, fallback string) string {
	if v := os.Getenv(name); v != "" {
		return v
	}
	return fallback
}

// config is the run's configuration, read after flag.Parse.
func config() Config {
	return Config{
		WalletURL:    *walletURL,
		SubstrateURL: *substrateURL,
		Brand:        *brand,
		Secret:       *secret,
		RecordTo:     os.Getenv("EITHEROR_RECORD_TO"),
		Commit:       os.Getenv("EITHEROR_ORIGINAL_COMMIT"),
	}
}

// run is one scenario against the configured original.
func run(t *testing.T, name, requirement string, body func(s *Session)) {
	t.Helper()
	Scenario(t, config(), name, requirement, body)
}

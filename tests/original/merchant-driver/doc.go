//go:build e2e

package merchantdriver

// Running the driver against a local original (never a shared one; tests/original/README.md):
//
//	cd tests && go test -tags e2e ./original/merchant-driver/... -count=1 -args \
//	    -merchant-url http://localhost:8085 -substrate-url http://localhost:8080
//
// with EITHEROR_RECORD_TO naming the run's directory and EITHEROR_ORIGINAL_COMMIT the original's
// commit. Each scenario writes <dir>/merchant/<scenario>.json; run twice and merge with
// ./original/merge into tests/port.

import (
	"flag"
	"os"
	"testing"
)

var (
	merchantURL  = flag.String("merchant-url", "http://localhost:8085", "the original's merchant-service")
	substrateURL = flag.String("substrate-url", envOr("EITHEROR_SUBSTRATE_URL", "http://localhost:8080"), "the original's substrate, which merchant-service is pointed at")
	secret       = flag.String("secret", envOr("MERCHANT_SERVICE_INTERNAL_SECRET", "changeme"), "MERCHANT_SERVICE_INTERNAL_SECRET of the local merchant-service")
)

func envOr(name, fallback string) string {
	if v := os.Getenv(name); v != "" {
		return v
	}
	return fallback
}

func config() Config {
	return Config{
		MerchantURL:    *merchantURL,
		SubstrateURL:   *substrateURL,
		InternalSecret: *secret,
		RecordTo:       os.Getenv("EITHEROR_RECORD_TO"),
		Commit:         os.Getenv("EITHEROR_ORIGINAL_COMMIT"),
	}
}

// run is one scenario against the configured original.
func run(t *testing.T, name, requirement string, body func(s *Session)) {
	t.Helper()
	Scenario(t, config(), name, requirement, body)
}

// sc names a subtest after the scenario and records it.
func sc(t *testing.T, name, requirement string, body func(s *Session)) {
	t.Run(name, func(t *testing.T) { run(t, name, requirement, body) })
}

//go:build e2e

package attributiondriver

// Running the driver against a local original (never a shared one; tests/original/README.md). On the
// original's KinD stack the affiliate service answers on its NodePort, localhost:8093 (host 8082 is
// substrate-graphql), and the substrate's twin API needs a forward:
//
//	kubectl --kubeconfig <the original's kind-only kubeconfig> -n eitheror port-forward svc/eitheror-bundle-substrate 9000:9000
//	cd tests && go run ./original/preflight -affiliate http://localhost:8093
//	cd tests && go test -tags e2e ./original/attribution-driver/... -count=1 -args \
//	    -affiliate-url http://localhost:8093 -substrate-url http://localhost:9000
//
// with EITHEROR_RECORD_TO naming the run's directory and EITHEROR_ORIGINAL_COMMIT the original's
// commit. Each scenario writes <dir>/attribution/<scenario>.json; run twice and merge with
// ./original/merge into tests/port.

import (
	"flag"
	"os"
)

var (
	affiliateURL = flag.String("affiliate-url", envOr("EITHEROR_AFFILIATE_URL", "http://localhost:8093"), "the original's affiliate-service")
	substrateURL = flag.String("substrate-url", envOr("EITHEROR_SUBSTRATE_URL", "http://localhost:9000"), "the original's substrate, which affiliate-service is pointed at")
	secret       = flag.String("internal-secret", envOr("EITHEROR_AFFILIATE_INTERNAL_SECRET", "dev-affiliate-secret"), "the local affiliate-service's internal secret")
)

func envOr(name, fallback string) string {
	if v := os.Getenv(name); v != "" {
		return v
	}
	return fallback
}

func config() Config {
	return Config{
		AffiliateURL:   *affiliateURL,
		SubstrateURL:   *substrateURL,
		InternalSecret: *secret,
		RecordTo:       os.Getenv("EITHEROR_RECORD_TO"),
		Commit:         os.Getenv("EITHEROR_ORIGINAL_COMMIT"),
	}
}

//go:build e2e

package ingestdriver

// Running the driver against a local original (never a shared one; tests/original/README.md):
//
//	cd tests && go test -tags e2e ./original/ingest-driver/... -count=1 -args \
//	    -merchant-url http://localhost:8085 -substrate-url http://localhost:8080
//
// with EITHEROR_RECORD_TO naming the run's directory and EITHEROR_ORIGINAL_COMMIT the original's
// commit. Each scenario writes <dir>/ingest/<scenario>.json; run twice and merge with ./original/merge
// into tests/port.

import (
	"flag"
	"os"

	md "github.com/eitheror-io/ankka-casino/tests/original/merchant-driver"
)

// The flags are merchant-driver's (its doc.go, built with the same tag): -merchant-url, -substrate-url
// and -secret.
func setting(name string) string { return flag.Lookup(name).Value.String() }

func config() md.Config {
	return md.Config{
		MerchantURL:    setting("merchant-url"),
		SubstrateURL:   setting("substrate-url"),
		InternalSecret: setting("secret"),
		RecordTo:       os.Getenv("EITHEROR_RECORD_TO"),
		Commit:         os.Getenv("EITHEROR_ORIGINAL_COMMIT"),
		Service:        "ingest",
	}
}

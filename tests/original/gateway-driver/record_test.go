//go:build e2e

package gatewaydriver

// Running the driver against a local original (never a shared one; tests/original/README.md,
// *Recording the gateway directly*), with psp-stub and the receiver running:
//
//	cd tests && go test -tags e2e ./original/gateway-driver/... -count=1 -timeout 60m -args \
//	    -gateway-url http://localhost:30087 -receiver-url http://172.18.0.1:18110
//
// with EITHEROR_RECORD_TO naming the run's directory and EITHEROR_ORIGINAL_COMMIT the original's
// commit. Each scenario writes <dir>/psp-gateway/<scenario>.json; run twice and merge with
// ./original/merge into tests/port.

import (
	"flag"
	"os"
	"strings"
	"testing"
)

var (
	gatewayURL    = flag.String("gateway-url", envOr("EITHEROR_PSP_GATEWAY_URL", "http://localhost:8087"), "the original's psp-gateway, as the driver reaches it")
	stubHost      = flag.String("stub-host", "http://localhost", "psp-stub, as the driver reaches it, without a port (each provider has its own, 18101-18109)")
	receiverURL   = flag.String("receiver-url", envOr("GATEWAY_RECEIVER_URL", "http://host.docker.internal:18110"), "the receiver, as the gateway reaches it: the deposit callback, PSP_GATEWAY_MERCHANT_SERVICE_URL and CASINO_SERVICE_URL")
	receiverLocal = flag.String("receiver-local", "http://localhost:18110", "the receiver, as the driver reaches it")
	secret        = flag.String("secret", envOr("MERCHANT_SERVICE_INTERNAL_SECRET", "changeme"), "the gateway's MERCHANT_SERVICE_INTERNAL_SECRET, for the re-drive route")
)

func envOr(name, fallback string) string {
	if v := os.Getenv(name); v != "" {
		return v
	}
	return fallback
}

func config() Config {
	return Config{
		GatewayURL:     *gatewayURL,
		StubHost:       *stubHost,
		ReceiverURL:    *receiverURL,
		ReceiverLocal:  *receiverLocal,
		InternalSecret: *secret,
		RecordTo:       os.Getenv("EITHEROR_RECORD_TO"),
		Commit:         os.Getenv("EITHEROR_ORIGINAL_COMMIT"),
	}
}

// feature runs every scenario of one feature, each a subtest named after its recording.
func feature(t *testing.T, file string) {
	for _, d := range Scenarios() {
		if d.Feature != file {
			continue
		}
		d := d
		t.Run(strings.ReplaceAll(d.Scenario(), "/", " for "), func(t *testing.T) {
			Scenario(t, config(), d.Scenario(), d.Requirement, d.Run)
		})
	}
}

// features/payments/providers.feature, scenario for scenario and provider for provider. A scenario
// the original answers otherwise than its feature expects fails in the run's result and is still
// recorded: the recording is the original's behaviour, and the failure is a finding for the spec.
func TestProviders(t *testing.T) { feature(t, providersFeature) }

// features/payments/crypto.feature, for Cozeni and Morphosis.
func TestCrypto(t *testing.T) { feature(t, cryptoFeature) }

// features/payments/withdrawal-payments.feature, for Unity Finance, Cozeni, Morphosis and Gigadat.
func TestPayouts(t *testing.T) { feature(t, payoutsFeature) }

// features/payments/outcomes.feature, through Digitarium.
func TestOutcomes(t *testing.T) { feature(t, outcomesFeature) }

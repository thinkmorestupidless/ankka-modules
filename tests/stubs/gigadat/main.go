// Command gigadat answers as Gigadat Express Connect (Interac) does to the original's psp-gateway
// adapter (services/psp-gateway/internal/gigadat), so a CPI deposit and an ETO or ACH payout can
// run with no request leaving the machine. See README.md for the setting that points the gateway here.
package main

import (
	"os"

	"github.com/eitheror-io/ankka-casino/tests/stubs/internal/stubhttp"
)

func main() {
	stub := stubhttp.New("gigadat")
	p := NewProvider(stub, ConfigFromEnv(os.Getenv))
	stubhttp.Serve(stub, p.Handler(), "18105")
}

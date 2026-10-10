// Command eupaymentz answers as EuPaymentz (Secure1Gateway) does to the original's psp-gateway
// adapter (services/psp-gateway/internal/eupaymentz), so a deposit can run with no request leaving
// the machine. See README.md for the setting that points the gateway here.
package main

import (
	"os"

	"github.com/eitheror-io/ankka-casino/tests/stubs/internal/stubhttp"
)

func main() {
	stub := stubhttp.New("eupaymentz")
	p := NewProvider(stub, ConfigFromEnv(os.Getenv))
	stubhttp.Serve(stub, p.Handler(), "18104")
}

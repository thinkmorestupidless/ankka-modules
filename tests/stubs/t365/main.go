// Command t365 is a stub of the T365 hosted-payment API, for running the original's psp-gateway
// with no request leaving the machine. See README.md.
package main

import "github.com/eitheror-io/ankka-casino/tests/stubs/internal/stubhttp"

func main() {
	stub := stubhttp.New("t365")
	stubhttp.Serve(stub, NewProvider(stub, ConfigFromEnv()), "18107")
}

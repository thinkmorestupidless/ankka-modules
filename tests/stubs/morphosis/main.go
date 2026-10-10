// Command morphosis is a stub of QVEI's WPB API (the Morphosis crypto provider), for running the
// original's psp-gateway with no request leaving the machine. See README.md.
package main

import "github.com/eitheror-io/ankka-casino/tests/stubs/internal/stubhttp"

func main() {
	stub := stubhttp.New("morphosis")
	stubhttp.Serve(stub, NewProvider(stub, CallbackDelayFromEnv()), "18106")
}

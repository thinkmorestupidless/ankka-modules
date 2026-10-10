// Command cozeni is the Cozeni stub: it answers the original psp-gateway's Cozeni adapter as Cozeni
// would (exchange rate, pay-in, payout) and calls the gateway back with the signed outcome.
package main

import "github.com/eitheror-io/ankka-casino/tests/stubs/internal/stubhttp"

func main() {
	stub := stubhttp.New("cozeni")
	stubhttp.Serve(stub, NewProvider(stub, ConfigFromEnv()).Routes(), "18102")
}

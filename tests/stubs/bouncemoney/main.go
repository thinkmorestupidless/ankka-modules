// Command bouncemoney is the BounceMoney stub: it answers the original psp-gateway's BounceMoney
// adapter as BounceMoney would, and sends the signed webhook back to the gateway.
package main

import "github.com/eitheror-io/ankka-casino/tests/stubs/internal/stubhttp"

func main() {
	stub := stubhttp.New("bouncemoney")
	stubhttp.Serve(stub, NewProvider(stub, ConfigFromEnv()).Routes(), "18101")
}

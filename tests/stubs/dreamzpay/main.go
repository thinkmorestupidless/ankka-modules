// Command dreamzpay is the DreamzPay stub: it stands in for DreamzPay's hosted checkout page, sends
// the player's browser back to the gateway, and sends the server-to-server notification.
package main

import "github.com/eitheror-io/ankka-casino/tests/stubs/internal/stubhttp"

func main() {
	stub := stubhttp.New("dreamzpay")
	stubhttp.Serve(stub, NewProvider(stub, ConfigFromEnv()).Routes(), "18103")
}

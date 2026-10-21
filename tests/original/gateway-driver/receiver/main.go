// Command receiver serves the gateway driver's receiver on its own, so preflight can see it answer
// before a recording starts and both runs of the driver share it (tests/original/README.md,
// *Recording the gateway directly*). The original's psp-gateway is pointed at it as merchant-service
// and casino-service:
//
//	go run ./original/gateway-driver/receiver -addr :18110
package main

import (
	"flag"
	"log"
	"net/http"

	gatewaydriver "github.com/eitheror-io/ankka-casino/tests/original/gateway-driver"
)

func main() {
	addr := flag.String("addr", ":18110", "where the receiver listens; the gateway must reach it")
	flag.Parse()
	log.Printf("gateway-driver receiver on %s", *addr)
	log.Fatal(http.ListenAndServe(*addr, gatewaydriver.NewReceiver()))
}

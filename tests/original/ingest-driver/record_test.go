//go:build e2e

package ingestdriver

import (
	"testing"

	md "github.com/eitheror-io/ankka-casino/tests/original/merchant-driver"
)

// TestCashier is features/payments/cashier.feature against the original, one recording per scenario
// and per outline example, each on its own sub-brands.
func TestCashier(t *testing.T) {
	for _, d := range Scenarios() {
		d := d
		t.Run(d.Scenario(), func(t *testing.T) {
			md.Scenario(t, config(), d.Scenario(), d.Requirement, func(s *Session) { d.Run(t, s) })
		})
	}
}

//go:build e2e

package payoutsdriver

import "testing"

// TestPayouts is features/payouts/*.feature against the original (scenario 30), one recording per
// scenario the original can show and per outline example, each on its own players and terminals. The
// operators' sign-ins are fetched afresh for each scenario: the run is longer than a realm token lives.
func TestPayouts(t *testing.T) {
	for _, d := range Scenarios() {
		d := d
		t.Run(d.Scenario(), func(t *testing.T) {
			Scenario(t, config(t), d.Scenario(), d.Requirement, func(s *Session) { d.Run(t, s) })
		})
	}
}

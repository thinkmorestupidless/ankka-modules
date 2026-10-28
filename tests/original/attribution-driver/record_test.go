//go:build e2e

package attributiondriver

import "testing"

// TestAttribution is features/attribution/ against the original, one recording per scenario and per
// outline example the original can show, each on its own brand.
func TestAttribution(t *testing.T) {
	for _, d := range Scenarios() {
		d := d
		t.Run(d.Scenario(), func(t *testing.T) {
			Scenario(t, config(), d.Scenario(), d.Requirement, func(s *Session) { d.Run(t, s) })
		})
	}
}

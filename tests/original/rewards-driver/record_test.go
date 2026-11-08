//go:build e2e

package rewardsdriver

import "testing"

// TestRewards is the scenarios of features/rewards/*.feature the original's suite does not reach,
// against the original (spec 011 T065), one recording per scenario and per outline example, each on its
// own player and offers. The operator's sign-in is fetched afresh for each scenario: the run is longer
// than a realm token lives.
func TestRewards(t *testing.T) {
	for _, d := range Scenarios() {
		d := d
		t.Run(d.Scenario(), func(t *testing.T) {
			Scenario(t, config(t), d.Scenario(), d.Requirement, func(s *Session) { d.Run(t, s) })
		})
	}
}

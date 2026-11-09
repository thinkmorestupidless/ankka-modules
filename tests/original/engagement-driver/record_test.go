//go:build e2e

package engagementdriver

import "testing"

// TestEngagement is the scenarios of features/engagement/*.feature the original's suite does not reach,
// and the two routes no feature scenario names, against the original (spec 014 T050), one recording per
// scenario and per outline example, each on its own players. The operator's sign-in is fetched afresh
// for each scenario: the run (the recycle scenario alone holds a stream for minutes) is longer than a
// realm token lives.
func TestEngagement(t *testing.T) {
	for _, d := range Scenarios() {
		d := d
		t.Run(d.Scenario(), func(t *testing.T) {
			Scenario(t, config(t), d.Scenario(), d.Requirement, func(s *Session) { d.Run(t, s) })
		})
	}
}

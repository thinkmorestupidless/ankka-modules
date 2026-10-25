//go:build e2e

package playersdriver

import "testing"

// TestPlayers is features/players/*.feature against the original, one recording per scenario the
// original can show and per outline example, each on its own players. The run's sign-ins are fetched
// once; a token that expires during a long run fails the scenarios after it, which a rerun repeats.
func TestPlayers(t *testing.T) {
	cfg := config(t)
	for _, d := range Scenarios() {
		d := d
		t.Run(d.Scenario(), func(t *testing.T) {
			Scenario(t, cfg, d.Scenario(), d.Requirement, func(s *Session) { d.Run(t, s) })
		})
	}
}

//go:build e2e

package programmedriver

import "testing"

// TestProgramme is contracts/recording.md's thirteen scenarios against the original, one recording
// each, named by the feature scenario it shows first.
func TestProgramme(t *testing.T) {
	cfg := config(t)
	for _, d := range Scenarios() {
		d := d
		t.Run(d.Scenario(), func(t *testing.T) {
			Scenario(t, cfg, d.Scenario(), d.Requirement, func(s *Session) { d.Run(t, s) })
		})
	}
}

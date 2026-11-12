//go:build e2e

package publicapidriver

import (
	"os"
	"testing"
)

// TestPublicAPI is contracts/recording.md against the original (spec 016 T090): the seed sown
// (`public-api-seed`), then one recording per row of its table, each on the seed. With -write-grants it
// only writes the grants the run needs, for the person to give the original before the run.
func TestPublicAPI(t *testing.T) {
	if *writeGrants != "" {
		if err := os.WriteFile(*writeGrants, GrantsValues(*brandA, *brandB), 0o644); err != nil {
			t.Fatal(err)
		}
		t.Skipf("the grants are in %s: give them to the original's casino release, then run again without -write-grants", *writeGrants)
	}
	cfg := config(t)
	var seed Seed
	t.Run(SeedName, func(st *testing.T) {
		s := newSession(st, cfg, SeedName, "016/FR-040")
		defer s.write()
		seed = s.Sow()
	})
	for _, d := range Scenarios() {
		d := d
		t.Run(d.Name, func(t *testing.T) {
			Scenario(t, cfg, seed, d.Name, d.Requirement, func(s *Session) { d.Run(t, s) })
		})
	}
}

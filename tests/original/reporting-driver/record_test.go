//go:build e2e

package reportingdriver

import (
	"os"
	"testing"
)

// TestReporting is contracts/recording.md against the original (spec 015 T070): the Reports tab shown
// for the brand and the seed sown (`reporting-seed`), then one recording per row and per suffix of a
// `-*` row, each on the seed. The operators' sign-ins are fetched afresh for each recording; the
// Reports flag's clean-ups run when every recording is made.
func TestReporting(t *testing.T) {
	if os.Getenv("WALLET_SERVICE_INTERNAL_SECRET") == "" {
		t.Fatal("no WALLET_SERVICE_INTERNAL_SECRET: the seed's deposit is signed with it")
	}
	var seed Seed
	t.Run(SeedName, func(st *testing.T) {
		s := newSession(st, config(st), SeedName, "015/FR-027")
		defer s.write()
		// The clean-ups outlive the seed's subtest: they report to the whole run's.
		s.ReportsOn(func(f func()) { t.Cleanup(func() { s.t = t; f() }) })
		seed = s.Sow()
	})
	for _, d := range Scenarios() {
		d := d
		t.Run(d.Name, func(t *testing.T) {
			Scenario(t, config(t), seed, d.Name, d.Requirement, func(s *Session) { d.Run(t, s) })
		})
	}
}

package reportingdriver

import (
	"net/http"
	"strings"
	"testing"
)

// A recording the original answers otherwise than the contract expects fails in the run's result and
// is still written: the recording is the original's behaviour, and the failure is for the person to
// read before the merge.

// Def is one recording of specs/015-reporting/contracts/recording.md, named as its table names it; a
// row whose name ends in `-*` is one recording per suffix in Wildcards.
type Def struct {
	Name        string
	Requirement string
	Run         func(t testing.TB, s *Session)
}

// Wildcards are the suffixes of the contract's `-*` rows: the trend's and the rankings' ranges as its
// driver-call column lists them, and the dispute report's five 400s and two 404s.
var Wildcards = map[string][]string{
	"dashboard-trend-":    {"today", "7d", "30d", "90d"},
	"dashboard-rankings-": {"today", "90d"},
	"dispute-refusals-": {
		"session-and-range", "neither", "range-over-a-year", "end-before-start", "not-a-date",
		"unknown-player", "unknown-session",
	},
}

// Extra are recordings the contract's table does not name: the seed, whose every step is setup.
var Extra = map[string]bool{SeedName: true}

// NotDriven are the contract's recordings the original cannot show, and why. The original shows every
// one: the Reports flag is its Unleash flag, which the run sets for the brand, and the refused role is a
// support operator the person creates.
var NotDriven = map[string]string{}

// refusal is one of the dispute report's refusals: its query, the player it names and what it answers.
type refusal struct {
	query  func(s *Session) [][2]string
	player func(s *Session) string
	status int
	error  string
}

func seedPlayer(s *Session) string    { return s.Value("playerId") }
func unknownPlayer(s *Session) string { return s.UUID("unknownPlayerId") }

// refusals are DashboardRoutes' dispute-report refusals, in its order: the two of its parameter shape,
// the three of its range, then the player and the session it cannot find.
var refusals = map[string]refusal{
	"session-and-range": {func(s *Session) [][2]string {
		return [][2]string{{"sessionId", s.Value("playerSessionId")}, {"fromDate", s.Value("from")}, {"toDate", s.Value("to")}, {"format", "html"}}
	}, seedPlayer, http.StatusBadRequest, "provide either sessionId or fromDate+toDate, not both"},
	"neither": {func(s *Session) [][2]string { return [][2]string{{"format", "html"}} },
		seedPlayer, http.StatusBadRequest, "provide either sessionId or both fromDate and toDate"},
	"range-over-a-year": {func(s *Session) [][2]string {
		return [][2]string{{"fromDate", "2024-01-01"}, {"toDate", "2025-01-02"}, {"format", "html"}}
	}, seedPlayer, http.StatusBadRequest, "date range must not exceed 365 days"},
	"end-before-start": {func(s *Session) [][2]string {
		return [][2]string{{"fromDate", "2026-02-01"}, {"toDate", "2026-01-01"}, {"format", "html"}}
	}, seedPlayer, http.StatusBadRequest, "fromDate must be before toDate"},
	"not-a-date": {func(s *Session) [][2]string {
		return [][2]string{{"fromDate", "01/02/2026"}, {"toDate", "2026-02-28"}, {"format", "html"}}
	}, seedPlayer, http.StatusBadRequest, "invalid date format; use ISO 8601 (YYYY-MM-DD)"},
	"unknown-player": {func(s *Session) [][2]string {
		return [][2]string{{"fromDate", s.Value("from")}, {"toDate", s.Value("to")}, {"format", "html"}}
	}, unknownPlayer, http.StatusNotFound, "player_not_found"},
	"unknown-session": {func(s *Session) [][2]string {
		return [][2]string{{"sessionId", s.UUID("unknownSessionId")}, {"format", "html"}}
	}, seedPlayer, http.StatusNotFound, "session_not_found"},
}

// read is a recording of one GET as the operator, answered 200.
func read(name, requirement string, path func(s *Session) string) Def {
	return Def{Name: name, Requirement: requirement, Run: func(t testing.TB, s *Session) {
		s.Want(s.Casino(strings.TrimPrefix(name, "dashboard-"), path(s), Operator), http.StatusOK)
	}}
}

// document is a recording of the dispute report answered 200 as a file of contentType.
func document(name string, query func(s *Session) [][2]string, contentType string) Def {
	return Def{Name: name, Requirement: "015/FR-016", Run: func(t testing.TB, s *Session) {
		r := s.Want(s.Casino("the dispute report", s.Dispute(s.Value("playerId"), query(s)), Operator), http.StatusOK)
		if got := r.Header.Get("Content-Type"); !strings.HasPrefix(got, contentType) {
			s.t.Errorf("%s: Content-Type %q (want %s)", name, got, contentType)
		}
		if got := r.Header.Get("Content-Disposition"); !strings.Contains(got, "attachment") {
			s.t.Errorf("%s: Content-Disposition %q (want an attachment)", name, got)
		}
	}}
}

// Scenarios is every recording the driver makes after the seed, in the contract's order.
func Scenarios() []Def {
	defs := []Def{
		read("reports-ggr", "015/FR-007", func(s *Session) string { return s.Reports() + "/ggr?" + s.Period() }),
		read("reports-ggr-default", "015/FR-006", func(s *Session) string { return s.Reports() + "/ggr" }),
		read("reports-players", "015/FR-008", func(s *Session) string {
			return s.Reports() + "/players?" + s.Period() + "&country=" + Country + "&status=all"
		}),
		read("reports-bonus", "015/FR-009", func(s *Session) string { return s.Reports() + "/bonus?" + s.Period() + "&offerId=all" }),
		read("reports-transactions", "015/FR-010", func(s *Session) string {
			return s.Reports() + "/transactions?" + s.Period() + "&page=0&pageSize=100"
		}),
		// The backoffice's export asks for every row at once (reportingApi.exportTransactions).
		read("reports-transactions-export", "015/FR-012", func(s *Session) string {
			return s.Reports() + "/transactions/export?" + s.Period() + "&page=0&pageSize=10000"
		}),
		read("reports-psp", "015/FR-011", func(s *Session) string { return s.Reports() + "/psp?" + s.Period() }),
		{Name: "reports-flag-off", Requirement: "015/FR-024", Run: func(t testing.TB, s *Session) {
			if s.cfg.FlagOffBrand == "" {
				s.Skip("no -flag-off-brand: the Reports tab is hidden for a brand the flag's strategy does not name")
				return
			}
			// The original reads the flag before the operator's roles, for the brand the path names.
			base := "/v1/casino/backoffice/" + s.cfg.FlagOffBrand + "/reports"
			for _, route := range []string{"/ggr?", "/players?", "/bonus?", "/transactions?", "/transactions/export?", "/psp?"} {
				path := base + route + s.Period()
				r := s.Eventually(func() Response { return s.Casino("a report with the Reports tab hidden", path, Operator) },
					func(r Response) bool { return r.Status == http.StatusNotFound })
				s.WantField(s.Want(r, http.StatusNotFound), "error", "not_found")
			}
		}},
		{Name: "reports-refused-role", Requirement: "015/FR-023", Run: func(t testing.TB, s *Session) {
			if s.cfg.SupportToken == "" {
				s.Skip("no -support-user or EITHEROR_SUPPORT_PASSWORD: the refusal is a support operator's")
				return
			}
			s.Want(s.Casino("the revenue report as a support operator", s.Reports()+"/ggr?"+s.Period(), Support), http.StatusForbidden)
			// The dashboard admits every operator of the brand: the same operator is answered there.
			s.Want(s.Casino("the dashboard as a support operator", s.Dashboard()+"/kpis", Support), http.StatusOK)
		}},
		read("dashboard-kpis", "015/FR-013", func(s *Session) string { return s.Dashboard() + "/kpis" }),
	}
	for _, r := range Wildcards["dashboard-trend-"] {
		r := r
		defs = append(defs, read("dashboard-trend-"+r, "015/FR-014", func(s *Session) string { return s.Dashboard() + "/trend?range=" + r }))
	}
	defs = append(defs,
		read("dashboard-kpi-history", "015/FR-014", func(s *Session) string { return s.Dashboard() + "/kpi-history" }),
		read("dashboard-geo", "015/FR-014", func(s *Session) string { return s.Dashboard() + "/geo" }),
	)
	for _, r := range Wildcards["dashboard-rankings-"] {
		r := r
		defs = append(defs, read("dashboard-rankings-"+r, "015/FR-014", func(s *Session) string { return s.Dashboard() + "/rankings?range=" + r }))
	}
	defs = append(defs,
		read("dashboard-activity", "015/FR-014", func(s *Session) string { return s.Dashboard() + "/activity" }),
		read("dashboard-bonus-summary", "015/FR-015", func(s *Session) string { return s.Dashboard() + "/bonus-summary" }),
		read("dashboard-payment-health", "015/FR-015", func(s *Session) string { return s.Dashboard() + "/payment-health" }),
		document("dispute-session-html", func(s *Session) [][2]string {
			return [][2]string{{"sessionId", s.Value("playerSessionId")}, {"format", "html"}}
		}, "text/html"),
		document("dispute-session-csv", func(s *Session) [][2]string {
			return [][2]string{{"sessionId", s.Value("playerSessionId")}, {"format", "csv"}}
		}, "text/csv"),
		document("dispute-range-html", func(s *Session) [][2]string {
			return [][2]string{{"fromDate", s.Value("from")}, {"toDate", s.Value("to")}, {"format", "html"}}
		}, "text/html"),
	)
	for _, name := range Wildcards["dispute-refusals-"] {
		name, ref := name, refusals[name]
		defs = append(defs, Def{Name: "dispute-refusals-" + name, Requirement: "015/FR-016", Run: func(t testing.TB, s *Session) {
			r := s.Casino("the dispute report", s.Dispute(ref.player(s), ref.query(s)), Operator)
			s.WantField(s.Want(r, ref.status), "error", ref.error)
		}})
	}
	return defs
}

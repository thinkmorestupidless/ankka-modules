package main

import (
	"encoding/base64"
	"encoding/json"
	"strings"
	"testing"
	"time"
)

type memory map[string][]Twin

func (m memory) List(ns string) ([]Twin, error) { return m[ns], nil }

func twin(id string, kv ...string) Twin {
	p := map[string]any{}
	for i := 0; i+1 < len(kv); i += 2 {
		p[kv[i]] = kv[i+1]
	}
	return Twin{ID: id, Props: p}
}

var now = time.Date(2026, 11, 2, 12, 0, 0, 0, time.UTC)

func original() memory {
	return memory{
		"affiliate-settings-spinvibe":  {twin("s", "adminFeePercent", "12.5", "paymentFeePercent", "", "bonusCostThreshold", "4")},
		"affiliate-programme-spinvibe": {twin("p", "payoutCycleDays", "30", "minimumPayoutAmount", "50.00")},
		"affiliate-plans-spinvibe": {twin("plan-1", "planName", "Standard", "planType", "HYBRID", "status", "ACTIVE", "cpaAmount", "25.00",
			"revSharePercent", "30", "minimumDepositThreshold", "10", "negativeCarryover", "true")},
		"affiliates-spinvibe": {
			twin("a-1", "email", "Ann@Example.com", "name", "Ann", "status", "ACTIVE", "planId", "plan-1", "overridePercent", "5",
				"revShareOverride", "35.5", "minimumPayoutThreshold", "100", "keycloakUserId", "kc-1", "tags", `["vip","de"]`,
				"createdAt", "2025-01-01T10:00:00Z"),
			twin("a-2", "email", "bob@example.com", "name", "Bob", "status", "PENDING", "parentAffiliateId", "a-1",
				"createdAt", "2025-02-01T10:00:00Z"),
		},
		"affiliate-links-spinvibe": {twin("btag-1", "affiliateId", "a-1", "campaign", "spring", "utmSource", "x", "createdAt", "2025-01-02T00:00:00Z")},
		"affiliate-bonus-codes-spinvibe": {twin("c-1", "code", "ann10", "affiliateId", "a-1", "btag", "btag-1", "status", "REVOKED",
			"createdAt", "2025-01-03T00:00:00Z")},
		"affiliate-audit-spinvibe-a-1": {twin("au-1", "eventType", "AFFILIATE_APPROVED", "actorId", "mgr", "recordedAt", "2025-01-01T11:00:00Z",
			"email", "ann@example.com", "setPasswordUrl", "https://x/?token=secret", "planId", "plan-1")},
		"affiliate-payouts-spinvibe": {twin("po-1", "affiliateId", "a-1", "period", "2025-01", "status", "PAID", "cpaCommission", "25.00",
			"revShareCommission", "10.50", "createdAt", "2025-02-01T00:00:00Z", "approvedBy", "fin", "approvedAt", "2025-02-02T00:00:00Z")},
		"affiliate-facts-spinvibe-a-1": {
			twin("tx-1", "factType", "CPA", "amountCents", "2500", "amount", "20.00", "currency", "EUR", "playerId", "player-1",
				"occurredAt", "2025-01-05T00:00:00Z", "recordedAt", "2025-01-05T00:00:01Z", "period", "2025-01", "settledByPayoutId", "po-1"),
			twin("f-2", "factType", "GGR", "amountCents", "-300", "playerId", "player-1", "occurredAt", "2025-01-06T00:00:00Z",
				"recordedAt", "2025-01-06T00:00:01Z", "period", "2025-01"),
		},
		"affiliate-registration-spinvibe": {twin("player-1", "playerId", "player-1", "affiliateId", "a-1", "btag", "btag-1", "country", "DE",
			"registeredAt", "2025-01-04T00:00:00Z")},
		"affiliate-ftd-spinvibe": {
			twin("tx-1", "transactionId", "tx-1", "playerId", "player-1", "affiliateId", "a-1", "recordedAt", "2025-01-05T00:00:00Z"),
			twin("m-1", "transactionId", "w-1", "type", "withdrawal_marker", "playerId", "player-1", "recordedAt", "2025-01-07T00:00:00Z"),
		},
		"affiliate-fraud-flags-spinvibe": {twin("fl-1", "playerId", "player-1", "affiliateId", "a-1", "period", "2025-01", "reason", "3+ chargebacks",
			"flaggedAt", "2025-01-08T00:00:00Z", "dismissed", "true", "dismissedBy", "mgr", "dismissedAt", "2025-01-09T00:00:00Z")},
	}
}

func export(t *testing.T, m memory) Bundle {
	t.Helper()
	b, err := Export(m, "spinvibe", "EUR", Pseudonyms([]byte("k")), now)
	if err != nil {
		t.Fatal(err)
	}
	return b
}

func TestMoneyIsMinorUnitsAndPercentagesBasisPoints(t *testing.T) {
	b := export(t, original())
	if b.Settings.AdminFeeBp != 1250 || b.Settings.PaymentFeeBp != 0 || *b.Settings.BonusCostThreshold != 4 {
		t.Fatalf("settings %+v", b.Settings)
	}
	if *b.Programme.MinimumPayoutAmount != 5000 || *b.Programme.PayoutCycleDays != 30 {
		t.Fatalf("programme %+v", b.Programme)
	}
	p := b.Plans[0]
	if p.PlanType != "hybrid" || p.CpaAmount != 2500 || p.RevShareBp != 3000 || p.MinimumDeposit != 1000 || !p.NegativeCarryover || p.Status != "active" {
		t.Fatalf("plan %+v", p)
	}
	a := b.Accounts[0]
	if a.OverrideBp != 500 || *a.Membership.RevShareOverrideBp != 3550 || a.MinimumPayout["EUR"] != 10000 || *a.Membership.Plan != "Standard" {
		t.Fatalf("account %+v", a)
	}
	if len(a.Tags) != 2 || a.Membership.Status != "active" || *a.KeycloakUser != "kc-1" {
		t.Fatalf("account %+v", a)
	}
	if _, err := minor("1.005"); err == nil {
		t.Fatal("three decimal places are refused, not rounded")
	}
}

func TestFactsKeepTheirPayoutUnderTheProgrammesIdAndPayoutsTheirStatus(t *testing.T) {
	b := export(t, original())
	if len(b.Facts) != 2 {
		t.Fatalf("facts %+v", b.Facts)
	}
	cpa := b.Facts[1]
	if b.Facts[0].ID == "tx-1" {
		cpa = b.Facts[0]
	}
	if cpa.Kind != "cpa" || cpa.Amount != 2500 || *cpa.SettledBy != "a-1/spinvibe/EUR/2025-01" {
		t.Fatalf("cpa %+v", cpa)
	}
	po := b.Payouts[0]
	if po.Status != "paid" || po.Cpa != 2500 || po.RevShare != 1050 || *po.PaidBy != "fin" || po.ApprovedBy != nil {
		t.Fatalf("payout %+v", po)
	}
}

func TestNoPlayerIdOrPersonalAuditKeyLeavesInClear(t *testing.T) {
	b := export(t, original())
	out, _ := json.Marshal(b)
	for _, clear := range []string{"player-1", "setPasswordUrl", "token=secret"} {
		if strings.Contains(string(out), clear) {
			t.Fatalf("%q in the bundle", clear)
		}
	}
	pl := b.Players[0]
	if pl.Pseudonym != Pseudonyms([]byte("k"))("player-1") || pl.FirstDeposit == nil || pl.FirstDeposit.Amount != 2000 || *pl.Country != "DE" {
		t.Fatalf("player %+v", pl)
	}
	if b.FraudFlags[0].Pseudonym != pl.Pseudonym || *b.FraudFlags[0].DismissedBy != "mgr" {
		t.Fatalf("flag %+v", b.FraudFlags[0])
	}
	if b.Audit[0].Details["planId"] != "plan-1" || b.Audit[0].Action != "AFFILIATE_APPROVED" {
		t.Fatalf("audit %+v", b.Audit[0])
	}
}

func TestPseudonymsAreAttributionsHmacSha256Base64Url(t *testing.T) {
	// HMAC-SHA256 of "player-1" under key "k", base64url without padding: what Pseudonyms.derive gives.
	key, _ := base64.StdEncoding.DecodeString(base64.StdEncoding.EncodeToString([]byte("k")))
	if got := Pseudonyms(key)("player-1"); got != "SjblTw7Ks1CMhxWqlncYxn1hAjqn4zOA-ow0As0sAdY" {
		t.Fatalf("pseudonym %q", got)
	}
}

func TestLinksKeepTheirBtagAsTheLegacyTagAndRevokedCodesTheExportsTime(t *testing.T) {
	b := export(t, original())
	if *b.Links[0].LegacyBtag != "btag-1" || b.Links[0].Utm["utmSource"] != "x" {
		t.Fatalf("link %+v", b.Links[0])
	}
	if b.BonusCodes[0].Code != "ANN10" || !b.BonusCodes[0].RevokedAt.Equal(now) {
		t.Fatalf("code %+v", b.BonusCodes[0])
	}
}

func TestAStatusTheProgrammeHasNotIsRefused(t *testing.T) {
	m := original()
	m["affiliates-spinvibe"] = []Twin{twin("a-9", "email", "x@y.z", "status", "TERMINATED", "createdAt", "2025-01-01T00:00:00Z")}
	if _, err := Export(m, "spinvibe", "EUR", Pseudonyms([]byte("k")), now); err == nil || !strings.Contains(err.Error(), "TERMINATED") {
		t.Fatalf("err %v", err)
	}
}

func TestRevenueIsTheMonthlyTwinsFiguresInMinorUnits(t *testing.T) {
	m := original()
	m["affiliate-revenue-a-1-spinvibe"] = []Twin{twin("r-1", "period", "2025-01", "cpaCommission", "25.00", "ngr", "-3.00", "totalCommission", "25.00")}
	r, err := Revenue(m, "spinvibe", export(t, m).Accounts)
	if err != nil {
		t.Fatal(err)
	}
	if f := r["a-1"]["2025-01"]; f["cpaCommission"] != 2500 || f["ngr"] != -300 || f["ggr"] != 0 {
		t.Fatalf("revenue %+v", r)
	}
}

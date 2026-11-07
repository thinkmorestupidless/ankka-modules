package main

import (
	"encoding/json"
	"fmt"
	"sort"
	"strings"
	"time"
)

// Bundle is the programme's import format, version 1 (its imports/Bundle.scala).
type Bundle struct {
	Version     int        `json:"version"`
	Brand       string     `json:"brand"`
	ExportedAt  time.Time  `json:"exportedAt"`
	Settings    *Settings  `json:"settings,omitempty"`
	Programme   *Programme `json:"programme,omitempty"`
	Plans       []Plan     `json:"plans"`
	Accounts    []Account  `json:"accounts"`
	Links       []Link     `json:"links"`
	BonusCodes  []Code     `json:"bonusCodes"`
	InviteLinks []Invite   `json:"inviteLinks"`
	Creatives   []Creative `json:"creatives"`
	Notes       []Note     `json:"notes"`
	Audit       []Audit    `json:"audit"`
	FraudFlags  []Flag     `json:"fraudFlags"`
	Players     []Player   `json:"players"`
	Facts       []Fact     `json:"facts"`
	Payouts     []Payout   `json:"payouts"`
}

type Settings struct {
	AdminFeeBp          int  `json:"adminFeeBp"`
	PaymentFeeBp        int  `json:"paymentFeeBp"`
	ProviderFeeBp       int  `json:"providerFeeBp"`
	BonusCostThreshold  *int `json:"bonusCostThreshold,omitempty"`
	ChargebackThreshold *int `json:"chargebackThreshold,omitempty"`
	PayoutCycleDay      *int `json:"payoutCycleDay,omitempty"`
}

type Programme struct {
	PayoutCycleDays     *int   `json:"payoutCycleDays,omitempty"`
	MinimumPayoutAmount *int64 `json:"minimumPayoutAmount,omitempty"`
}

type Plan struct {
	Name              string    `json:"name"`
	PlanType          string    `json:"planType"`
	CpaAmount         int64     `json:"cpaAmount"`
	CpaCurrency       string    `json:"cpaCurrency"`
	RevShareBp        int       `json:"revShareBp"`
	MinimumDeposit    int64     `json:"minimumDeposit"`
	NegativeCarryover bool      `json:"negativeCarryover"`
	Status            string    `json:"status"`
	CreatedAt         time.Time `json:"createdAt"`
}

type Account struct {
	ID              string           `json:"id"`
	Email           string           `json:"email"`
	Name            string           `json:"name"`
	DisplayName     *string          `json:"displayName,omitempty"`
	CompanyName     *string          `json:"companyName,omitempty"`
	Website         *string          `json:"website,omitempty"`
	TrafficSource   *string          `json:"trafficSource,omitempty"`
	PaymentMethod   *string          `json:"paymentMethod,omitempty"`
	Parent          *string          `json:"parent,omitempty"`
	OverrideBp      int              `json:"overrideBp"`
	KeycloakUser    *string          `json:"keycloakUser,omitempty"`
	PostbackURL     *string          `json:"postbackUrl,omitempty"`
	MinimumPayout   map[string]int64 `json:"minimumPayout,omitempty"`
	ManagerID       *string          `json:"managerId,omitempty"`
	Group           *string          `json:"group,omitempty"`
	Tags            []string         `json:"tags,omitempty"`
	TermsVersion    *string          `json:"termsVersion,omitempty"`
	TermsAcceptedAt *time.Time       `json:"termsAcceptedAt,omitempty"`
	CreatedAt       time.Time        `json:"createdAt"`
	Membership      Membership       `json:"membership"`
}

type Membership struct {
	Status             string     `json:"status"`
	Plan               *string    `json:"plan,omitempty"`
	RevShareOverrideBp *int       `json:"revShareOverrideBp,omitempty"`
	AppliedAt          time.Time  `json:"appliedAt"`
	Since              *time.Time `json:"since,omitempty"`
	Source             string     `json:"source"`
	RejectedReason     *string    `json:"rejectedReason,omitempty"`
}

type Link struct {
	ID          string            `json:"id"`
	Affiliate   string            `json:"affiliate"`
	Campaign    *string           `json:"campaign,omitempty"`
	LandingPath *string           `json:"landingPath,omitempty"`
	Utm         map[string]string `json:"utm,omitempty"`
	LegacyBtag  *string           `json:"legacyBtag,omitempty"`
	CreatedAt   time.Time         `json:"createdAt"`
}

type Code struct {
	Code      string     `json:"code"`
	Affiliate string     `json:"affiliate"`
	Link      string     `json:"link"`
	CreatedAt time.Time  `json:"createdAt"`
	RevokedAt *time.Time `json:"revokedAt,omitempty"`
}

type Invite struct {
	Token     string     `json:"token"`
	Link      string     `json:"link"`
	Affiliate string     `json:"affiliate"`
	CreatedAt time.Time  `json:"createdAt"`
	RevokedAt *time.Time `json:"revokedAt,omitempty"`
}

type Creative struct {
	ID           string     `json:"id"`
	Name         string     `json:"name"`
	CreativeType *string    `json:"creativeType,omitempty"`
	FileURL      *string    `json:"fileUrl,omitempty"`
	Dimensions   *string    `json:"dimensions,omitempty"`
	Description  *string    `json:"description,omitempty"`
	By           string     `json:"by"`
	CreatedAt    time.Time  `json:"createdAt"`
	DeletedAt    *time.Time `json:"deletedAt,omitempty"`
}

type Note struct {
	ID        string    `json:"id"`
	Affiliate string    `json:"affiliate"`
	Text      string    `json:"text"`
	Author    string    `json:"author"`
	CreatedAt time.Time `json:"createdAt"`
}

type Audit struct {
	ID        string            `json:"id"`
	Affiliate string            `json:"affiliate"`
	Action    string            `json:"action"`
	Actor     string            `json:"actor"`
	At        time.Time         `json:"at"`
	Details   map[string]string `json:"details,omitempty"`
}

type Flag struct {
	ID              string     `json:"id"`
	Pseudonym       string     `json:"pseudonym"`
	Affiliate       string     `json:"affiliate"`
	Reason          string     `json:"reason"`
	Period          string     `json:"period"`
	RaisedAt        time.Time  `json:"raisedAt"`
	DismissedBy     *string    `json:"dismissedBy,omitempty"`
	DismissedAt     *time.Time `json:"dismissedAt,omitempty"`
	DismissalReason *string    `json:"dismissalReason,omitempty"`
}

type Player struct {
	Pseudonym    string    `json:"pseudonym"`
	Affiliate    string    `json:"affiliate"`
	Link         string    `json:"link"`
	Campaign     *string   `json:"campaign,omitempty"`
	ClickID      *string   `json:"clickId,omitempty"`
	Country      *string   `json:"country,omitempty"`
	RegisteredAt time.Time `json:"registeredAt"`
	FirstDeposit *Deposit  `json:"firstDeposit,omitempty"`
}

type Deposit struct {
	Amount   int64     `json:"amount"`
	Currency string    `json:"currency"`
	At       time.Time `json:"at"`
}

type Fact struct {
	ID              string    `json:"id"`
	Affiliate       string    `json:"affiliate"`
	Currency        string    `json:"currency"`
	Kind            string    `json:"kind"`
	Amount          int64     `json:"amount"`
	OccurredAt      time.Time `json:"occurredAt"`
	RecordedAt      time.Time `json:"recordedAt"`
	Period          string    `json:"period"`
	SourceAffiliate *string   `json:"sourceAffiliate,omitempty"`
	SettledBy       *string   `json:"settledBy,omitempty"`
}

type Payout struct {
	Affiliate      string     `json:"affiliate"`
	Currency       string     `json:"currency"`
	Period         string     `json:"period"`
	Status         string     `json:"status"`
	Cpa            int64      `json:"cpa"`
	RevShare       int64      `json:"revShare"`
	OverrideAmount int64      `json:"overrideAmount"`
	Adjustments    int64      `json:"adjustments"`
	CreatedAt      time.Time  `json:"createdAt"`
	ApprovedBy     *string    `json:"approvedBy,omitempty"`
	ApprovedAt     *time.Time `json:"approvedAt,omitempty"`
	RejectedBy     *string    `json:"rejectedBy,omitempty"`
	RejectedReason *string    `json:"rejectedReason,omitempty"`
	PaidBy         *string    `json:"paidBy,omitempty"`
}

var factKinds = map[string]string{
	"GGR": "ggr", "BONUS_COST": "bonus-cost", "CHARGEBACK": "chargeback", "ADMIN_FEE": "admin-fee",
	"PAYMENT_FEE": "payment-fee", "PROVIDER_FEE": "provider-fee", "CPA": "cpa", "REVSHARE": "revshare",
	"OVERRIDE": "override", "ADJUSTMENT": "adjustment",
}

var statuses = map[string]string{"PENDING": "pending", "ACTIVE": "active", "SUSPENDED": "suspended", "REJECTED": "rejected"}

var payoutStatuses = map[string]string{"PENDING": "pending", "APPROVED": "approved", "REJECTED": "rejected", "PAID": "paid"}

// personal are the audit meta keys that name a person or carry a token; they stay behind.
var personal = map[string]bool{
	"email": true, "displayName": true, "companyName": true, "name": true, "setPasswordUrl": true,
	"setPasswordExpiresAt": true, "dashboardUrl": true,
}

// auditShape are an audit twin's own keys, not its details.
var auditShape = map[string]bool{
	"primaryType": true, "eventType": true, "event": true, "actorId": true, "affiliateId": true, "brandId": true, "recordedAt": true,
}

// Export reads the brand's namespaces (research R1) into a bundle.
func Export(src Source, brand, currency string, pseudonym func(string) string, now time.Time) (Bundle, error) {
	e := &exporter{src: src, brand: brand, currency: currency, pseudonym: pseudonym, now: now}
	return e.run()
}

type exporter struct {
	src       Source
	brand     string
	currency  string
	pseudonym func(string) string
	now       time.Time
	err       error
}

func (e *exporter) list(ns string) []Twin {
	if e.err != nil {
		return nil
	}
	twins, err := e.src.List(ns)
	if err != nil {
		e.err = err
	}
	return twins
}

func (e *exporter) check(what string, err error) {
	if err != nil && e.err == nil {
		e.err = fmt.Errorf("%s: %w", what, err)
	}
}

// must is a time the record cannot be without: missing, it is an error, never the export's own time.
func (e *exporter) must(what string, p props, k string) time.Time {
	t, err := p.at(k)
	e.check(what, err)
	if t == nil {
		e.check(what, fmt.Errorf("no %s", k))
		return e.now
	}
	return *t
}

func (e *exporter) at(what string, p props, k string) time.Time {
	t, err := p.at(k)
	e.check(what, err)
	if t == nil {
		return e.now
	}
	return *t
}

func (e *exporter) optAt(what string, p props, k string) *time.Time {
	t, err := p.at(k)
	e.check(what, err)
	return t
}

func (e *exporter) money(what, v, currency string) int64 {
	n, err := minorIn(v, currency)
	e.check(what, err)
	return n
}

func (e *exporter) run() (Bundle, error) {
	b := Bundle{Version: 1, Brand: e.brand, ExportedAt: e.now}
	b.Settings = e.settings()
	b.Programme = e.programme()
	plans := e.plans()
	b.Plans = plans.list
	b.Accounts = e.accounts(plans.names)
	b.Links = e.links()
	b.BonusCodes = e.codes()
	b.InviteLinks = e.invites()
	b.Creatives = e.creatives()
	payouts, ids := e.payouts()
	b.Payouts = payouts
	for _, a := range b.Accounts {
		b.Notes = append(b.Notes, e.notes(a.ID)...)
		b.Audit = append(b.Audit, e.audit(a.ID)...)
		b.Facts = append(b.Facts, e.facts(a.ID, ids)...)
	}
	b.FraudFlags = e.flags()
	b.Players = e.players()
	return b, e.err
}

func (e *exporter) settings() *Settings {
	for _, t := range e.list("affiliate-settings-" + e.brand) {
		p := props(t.Props)
		s := &Settings{}
		var err error
		if s.AdminFeeBp, err = bp(p.str("adminFeePercent")); err != nil {
			e.check("adminFeePercent", err)
		}
		if s.PaymentFeeBp, err = bp(p.str("paymentFeePercent")); err != nil {
			e.check("paymentFeePercent", err)
		}
		if s.ProviderFeeBp, err = bp(p.str("providerFeePercent")); err != nil {
			e.check("providerFeePercent", err)
		}
		s.BonusCostThreshold = e.int("bonusCostThreshold", p)
		s.ChargebackThreshold = e.int("chargebackThreshold", p)
		s.PayoutCycleDay = e.int("payoutCycleDay", p)
		return s
	}
	return nil
}

func (e *exporter) int(k string, p props) *int {
	v := p.str(k)
	if v == "" {
		return nil
	}
	var n int
	_, err := fmt.Sscanf(v, "%d", &n)
	e.check(k, err)
	return &n
}

func (e *exporter) programme() *Programme {
	for _, t := range e.list("affiliate-programme-" + e.brand) {
		p := props(t.Props)
		page := &Programme{PayoutCycleDays: e.int("payoutCycleDays", p)}
		if v := p.str("minimumPayoutAmount"); v != "" {
			n := e.money("minimumPayoutAmount", v, e.currency)
			page.MinimumPayoutAmount = &n
		}
		return page
	}
	return nil
}

type plans struct {
	list  []Plan
	names map[string]string // the original's plan id to its name
}

func (e *exporter) plans() plans {
	out := plans{names: map[string]string{}}
	for _, t := range e.list("affiliate-plans-" + e.brand) {
		p := props(t.Props)
		rs, err := bp(p.str("revSharePercent"))
		e.check("revSharePercent", err)
		status := "active"
		if !strings.EqualFold(p.str("status"), "ACTIVE") {
			status = "inactive"
		}
		kind := strings.ToLower(p.str("planType"))
		if kind == "" {
			kind = "cpa"
		}
		out.list = append(out.list, Plan{
			Name: p.str("planName"), PlanType: kind, CpaAmount: e.money("cpaAmount", p.str("cpaAmount"), e.currency), CpaCurrency: e.currency,
			RevShareBp: rs, MinimumDeposit: e.money("minimumDepositThreshold", p.str("minimumDepositThreshold"), e.currency),
			NegativeCarryover: p.str("negativeCarryover") == "true", Status: status, CreatedAt: e.at("plan", p, "createdAt"),
		})
		out.names[t.ID] = p.str("planName")
	}
	return out
}

func (e *exporter) accounts(planNames map[string]string) []Account {
	var out []Account
	for _, t := range e.list("affiliates-" + e.brand) {
		p := props(t.Props)
		status, ok := statuses[strings.ToUpper(p.str("status"))]
		if !ok {
			e.check("account "+t.ID, fmt.Errorf("status %q is not one the programme has", p.str("status")))
		}
		override, err := bp(p.str("overridePercent"))
		e.check("overridePercent", err)
		created := e.must("account "+t.ID, p, "createdAt")
		a := Account{
			ID: t.ID, Email: p.str("email"), Name: p.str("name"), DisplayName: p.opt("displayName"), CompanyName: p.opt("companyName"),
			Website: p.opt("website"), TrafficSource: p.opt("trafficSource"), PaymentMethod: p.opt("paymentMethod"),
			Parent: p.opt("parentAffiliateId"), OverrideBp: override, KeycloakUser: p.opt("keycloakUserId"),
			PostbackURL: p.opt("postbackUrl"), ManagerID: p.opt("affiliateManagerId"), Group: p.opt("group"),
			TermsVersion: p.opt("termsVersion"), TermsAcceptedAt: e.optAt("termsAcceptedAt", p, "termsAcceptedAt"), CreatedAt: created,
		}
		if v := p.str("minimumPayoutThreshold"); v != "" {
			a.MinimumPayout = map[string]int64{e.currency: e.money("minimumPayoutThreshold", v, e.currency)}
		}
		if v := p.str("tags"); v != "" {
			e.check("tags", json.Unmarshal([]byte(v), &a.Tags))
		}
		applied := created
		if r := e.optAt("reappliedAt", p, "reappliedAt"); r != nil {
			applied = *r
		}
		m := Membership{Status: status, AppliedAt: applied, Since: &applied, Source: "import", RejectedReason: p.opt("rejectionReason")}
		if id := p.str("planId"); id != "" {
			name, ok := planNames[id]
			if !ok {
				e.check("account "+t.ID, fmt.Errorf("plan %s is not among the brand's plans", id))
			}
			m.Plan = &name
		}
		if v := p.str("revShareOverride"); v != "" {
			n, err := bp(v)
			e.check("revShareOverride", err)
			m.RevShareOverrideBp = &n
		}
		a.Membership = m
		out = append(out, a)
	}
	return out
}

func (e *exporter) links() []Link {
	var out []Link
	for _, t := range e.list("affiliate-links-" + e.brand) {
		p := props(t.Props)
		utm := map[string]string{}
		for _, k := range []string{"utmSource", "utmMedium", "utmCampaign"} {
			if v := p.str(k); v != "" {
				utm[k] = v
			}
		}
		if len(utm) == 0 {
			utm = nil
		}
		btag := t.ID
		out = append(out, Link{ID: t.ID, Affiliate: p.str("affiliateId"), Campaign: p.opt("campaign"), LandingPath: p.opt("landingPath"),
			Utm: utm, LegacyBtag: &btag, CreatedAt: e.at("link "+t.ID, p, "createdAt")})
	}
	return out
}

func (e *exporter) revoked(p props) *time.Time {
	if strings.EqualFold(p.str("status"), "REVOKED") || strings.EqualFold(p.str("status"), "DELETED") {
		t := e.now
		return &t
	}
	return nil
}

func (e *exporter) codes() []Code {
	var out []Code
	for _, t := range e.list("affiliate-bonus-codes-" + e.brand) {
		p := props(t.Props)
		out = append(out, Code{Code: strings.ToUpper(p.str("code")), Affiliate: p.str("affiliateId"), Link: p.str("btag"),
			CreatedAt: e.at("code", p, "createdAt"), RevokedAt: e.revoked(p)})
	}
	return out
}

func (e *exporter) invites() []Invite {
	var out []Invite
	for _, t := range e.list("affiliate-invite-links-" + e.brand) {
		p := props(t.Props)
		if e.revoked(p) != nil {
			continue
		}
		out = append(out, Invite{Token: p.str("token"), Link: t.ID, Affiliate: p.str("affiliateId"), CreatedAt: e.at("invite", p, "createdAt")})
	}
	return out
}

func (e *exporter) creatives() []Creative {
	var out []Creative
	for _, t := range e.list("affiliate-creatives-" + e.brand) {
		p := props(t.Props)
		out = append(out, Creative{ID: t.ID, Name: p.str("name"), CreativeType: p.opt("creativeType"), FileURL: p.opt("fileUrl"),
			Dimensions: p.opt("dimensions"), Description: p.opt("description"), By: "import", CreatedAt: e.at("creative", p, "createdAt"),
			DeletedAt: e.revoked(p)})
	}
	return out
}

func or(v, fallback string) string {
	if v == "" {
		return fallback
	}
	return v
}

func (e *exporter) notes(affiliate string) []Note {
	var out []Note
	for _, t := range e.list("affiliate-notes-" + e.brand + "-" + affiliate) {
		p := props(t.Props)
		out = append(out, Note{ID: t.ID, Affiliate: affiliate, Text: p.str("text"), Author: or(p.str("authorId"), "import"),
			CreatedAt: e.at("note", p, "createdAt")})
	}
	return out
}

// audit carries both of the original's shapes: an `event` with its `detail`, and a network `eventType`
// with its meta keys, less those naming a person.
func (e *exporter) audit(affiliate string) []Audit {
	var out []Audit
	for _, t := range e.list("affiliate-audit-" + e.brand + "-" + affiliate) {
		p := props(t.Props)
		details := map[string]string{}
		for k := range p {
			if !auditShape[k] && !personal[k] {
				details[k] = p.str(k)
			}
		}
		if len(details) == 0 {
			details = nil
		}
		out = append(out, Audit{ID: t.ID, Affiliate: affiliate, Action: or(p.str("event"), p.str("eventType")),
			Actor: or(p.str("actorId"), "system"), At: e.at("audit", p, "recordedAt"), Details: details})
	}
	return out
}

func (e *exporter) payouts() ([]Payout, map[string]string) {
	var out []Payout
	ids := map[string]string{} // the original's payout id to the programme's
	for _, t := range e.list("affiliate-payouts-" + e.brand) {
		p := props(t.Props)
		status, ok := payoutStatuses[strings.ToUpper(p.str("status"))]
		if !ok {
			e.check("payout "+t.ID, fmt.Errorf("status %q", p.str("status")))
		}
		cur := or(p.str("currency"), e.currency)
		po := Payout{Affiliate: p.str("affiliateId"), Currency: cur, Period: p.str("period"), Status: status,
			Cpa: e.money("cpaCommission", p.str("cpaCommission"), cur), RevShare: e.money("revShareCommission", p.str("revShareCommission"), cur),
			OverrideAmount: e.money("overrideCommission", p.str("overrideCommission"), cur), Adjustments: e.money("adjustments", p.str("adjustments"), cur),
			CreatedAt: e.must("payout", p, "createdAt")}
		// The original writes its one actor, `approvedBy`, on every transition.
		actor := p.opt("approvedBy")
		switch status {
		case "approved":
			po.ApprovedBy, po.ApprovedAt = actor, e.optAt("approvedAt", p, "approvedAt")
		case "rejected":
			po.RejectedBy, po.RejectedReason = actor, p.opt("rejectionReason")
		case "paid":
			po.ApprovedAt, po.PaidBy = e.optAt("approvedAt", p, "approvedAt"), actor
		}
		out = append(out, po)
		ids[t.ID] = po.Affiliate + "/" + e.brand + "/" + cur + "/" + po.Period
	}
	return out, ids
}

func (e *exporter) facts(affiliate string, payouts map[string]string) []Fact {
	var out []Fact
	for _, t := range e.list("affiliate-facts-" + e.brand + "-" + affiliate) {
		p := props(t.Props)
		kind, ok := factKinds[p.str("factType")]
		if !ok {
			e.check("fact "+t.ID, fmt.Errorf("factType %q", p.str("factType")))
			continue
		}
		var amount int64
		_, err := fmt.Sscanf(or(p.str("amountCents"), "0"), "%d", &amount)
		e.check("amountCents of "+t.ID, err)
		f := Fact{ID: t.ID, Affiliate: affiliate, Currency: e.currency, Kind: kind, Amount: amount,
			OccurredAt: e.must("fact "+t.ID, p, "occurredAt"), RecordedAt: e.must("fact "+t.ID, p, "recordedAt"), Period: p.str("period"),
			SourceAffiliate: p.opt("sourceAffiliateId")}
		if kind == "cpa" {
			f.Currency = or(p.str("currency"), e.currency)
		}
		if s := p.str("settledByPayoutId"); s != "" {
			id, ok := payouts[s]
			if !ok {
				e.check("fact "+t.ID, fmt.Errorf("settled by payout %s, not among the brand's", s))
			}
			f.SettledBy = &id
		}
		out = append(out, f)
	}
	return out
}

func (e *exporter) flags() []Flag {
	var out []Flag
	for _, t := range e.list("affiliate-fraud-flags-" + e.brand) {
		p := props(t.Props)
		f := Flag{ID: t.ID, Pseudonym: e.pseudonym(p.str("playerId")), Affiliate: p.str("affiliateId"), Reason: p.str("reason"),
			Period: p.str("period"), RaisedAt: e.at("flag", p, "flaggedAt")}
		if p.str("dismissed") == "true" {
			f.DismissedBy, f.DismissedAt, f.DismissalReason = p.opt("dismissedBy"), e.optAt("dismissedAt", p, "dismissedAt"), p.opt("dismissalReason")
		}
		out = append(out, f)
	}
	return out
}

// players are the brand's registrations, each with its first deposit: the FTD record's time, and the
// amount of the CPA fact written for the same transaction when there is one.
func (e *exporter) players() []Player {
	deposits := map[string]Deposit{} // by player id
	cpa := map[string]props{}        // CPA facts by transaction id, from every affiliate's facts
	for _, t := range e.list("affiliates-" + e.brand) {
		for _, f := range e.list("affiliate-facts-" + e.brand + "-" + t.ID) {
			if p := props(f.Props); p.str("factType") == "CPA" {
				cpa[f.ID] = p
			}
		}
	}
	for _, t := range e.list("affiliate-ftd-" + e.brand) {
		p := props(t.Props)
		if p.str("transactionId") == "" || p.str("type") == "withdrawal_marker" || p.str("playerId") == "" {
			continue
		}
		d := Deposit{Currency: e.currency, At: e.must("ftd", p, "recordedAt")}
		if f, ok := cpa[p.str("transactionId")]; ok {
			d.Currency = or(f.str("currency"), e.currency)
			d.Amount = e.money("deposit "+p.str("transactionId"), f.str("amount"), d.Currency)
		}
		if prev, ok := deposits[p.str("playerId")]; !ok || d.At.Before(prev.At) {
			deposits[p.str("playerId")] = d
		}
	}
	var out []Player
	for _, t := range e.list("affiliate-registration-" + e.brand) {
		p := props(t.Props)
		player := or(p.str("playerId"), t.ID)
		pl := Player{Pseudonym: e.pseudonym(player), Affiliate: p.str("affiliateId"), Link: p.str("btag"), Campaign: p.opt("campaign"),
			ClickID: p.opt("affiliateClickId"), Country: p.opt("country"), RegisteredAt: e.must("registration", p, "registeredAt")}
		if d, ok := deposits[player]; ok {
			d := d
			pl.FirstDeposit = &d
		}
		out = append(out, pl)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Pseudonym < out[j].Pseudonym })
	return out
}

// revenueFields are the monthly revenue twin's folded figures, named as the programme's revenue names them.
var revenueFields = []string{
	"ggr", "excludedGgr", "bonusCosts", "chargebacks", "adminFees", "paymentFees", "providerFees", "ngr", "cpaCommission",
	"revShareCommission", "overrideCommission", "adjustments", "totalCommission",
}

// Revenue is the original's monthly revenue per affiliate and period, its figures in minor units.
func Revenue(src Source, brand string, accounts []Account) (map[string]map[string]map[string]int64, error) {
	e := &exporter{src: src, brand: brand}
	out := map[string]map[string]map[string]int64{}
	for _, a := range accounts {
		for _, t := range e.list("affiliate-revenue-" + a.ID + "-" + brand) {
			p := props(t.Props)
			figures := map[string]int64{}
			for _, k := range revenueFields {
				figures[k] = e.money(k+" of "+t.ID, p.str(k), e.currency)
			}
			if out[a.ID] == nil {
				out[a.ID] = map[string]map[string]int64{}
			}
			out[a.ID][p.str("period")] = figures
		}
	}
	return out, e.err
}

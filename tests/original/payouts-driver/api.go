package payoutsdriver

import (
	"encoding/json"
	"net/http"
	"strings"
	"time"
)

// The original's withdrawal API, one helper per route the scenarios use. Amounts are given in minor
// units and sent as the original takes them: a major-unit decimal string on the request
// (WithdrawalRoutes reads `amount` as a BigDecimal of major units), on the deposit and on the grant.

// The run's made-up values, the same on every run, so a replay sends them as they are.
const (
	Password = "Payouts-Driver-1!"
	// FirstName and LastName are the profile's names, and a destination saved in them passes the
	// original's name check (PayoutDestinationRoutes.normaliseName).
	FirstName = "A."
	LastName  = "Example"
	// IBAN and OtherIBAN are documentation IBANs whose check digits hold (IbanValidator).
	IBAN      = "DE89370400440532013000"
	OtherIBAN = "GB82WEST12345698765432"
	// WalletAddress is a made-up coin address: the original checks only that one is present.
	WalletAddress = "bc1qpayoutsdriver0000000000000000000000"
	Currency      = "EUR"
)

// The psp-stub Unity Finance credentials a terminal carries (tests/stubs/unityfinance/README.md);
// recorded by name, as the gateway driver records them.
const (
	stubUserID      = "stub-user"
	stubWithdrawKey = "stub-withdrawal-key"
	stubSignKey     = "unityfinance-signing-secret"
)

// key names a player's value: `email`, `playerId`, `token` for the scenario's player, and
// `otherEmail`, `otherPlayerId`, `otherToken` for another (`who` = "other").
func key(who, name string) string {
	if who == "" {
		return name
	}
	return who + strings.ToUpper(name[:1]) + name[1:]
}

func label(who string) string {
	if who == "" {
		return "the player"
	}
	return "the " + who + " player"
}

// Players is the registration and sign-in routes' prefix; PlayerPath one player's withdrawal routes'
// (WithdrawalRoutes, WithdrawalTerminalRoutes, PayoutDestinationRoutes); Queue the operator's.
func (s *Session) Players() string { return "/v1/casino/players/" + s.Brand() }
func (s *Session) PlayerPath(who string) string {
	return "/v1/casino/brands/" + s.Brand() + "/players/" + s.Value(key(who, "playerId"))
}
func (s *Session) Queue() string { return "/v1/casino/brands/" + s.Brand() + "/withdrawal-requests" }

// ok checks a setup write the original answers 200 or 201.
func (s *Session) ok(r Response) Response {
	if r.Status != http.StatusOK && r.Status != http.StatusCreated {
		s.t.Errorf("%s: a setup step was answered %d: %s", s.rec.Scenario, r.Status, string(r.Raw))
	}
	return r
}

// Active is `who` registered (with the profile's names when `named`), verified by the link the
// original would send and signed in, all as setup.
func (s *Session) Active(who string, named bool) {
	s.t.Helper()
	body := map[string]any{
		"email": s.Email(key(who, "email")), "password": Password, "currency": Currency,
		"country": "GB", "language": "en", "dateOfBirth": "1990-01-01",
	}
	if named {
		body["firstName"], body["lastName"] = FirstName, LastName
	}
	r := s.Want(s.Setup("register "+label(who), "casino", http.MethodPost, s.Players()+"/register", body, NoAuth), http.StatusCreated)
	if r.Status != http.StatusCreated {
		return
	}
	s.Grab(key(who, "playerId"), "$.playerId", r.Str("playerId"))
	link := VerificationToken(s.cfg.SigningSecret, s.Brand(), s.Value(key(who, "playerId")), s.Value(key(who, "email")),
		s.cfg.Now().Add(24*time.Hour))
	s.Remember(key(who, "link"), link)
	s.Want(s.Setup("follow "+label(who)+"'s verification link", "casino", http.MethodPost, s.Players()+"/verify-email",
		map[string]any{"token": link}, NoAuth), http.StatusOK)
	r = s.Want(s.Setup("sign "+label(who)+" in", "casino", http.MethodPost, s.Players()+"/login",
		map[string]any{"email": s.Value(key(who, "email")), "password": Password}, NoAuth), http.StatusOK)
	if r.Status == http.StatusOK {
		s.Grab(key(who, "token"), "$.token", r.Str("token"))
		s.Remember(key(who, "refreshToken"), r.Str("refreshToken"))
	}
	s.Remember(key(who, "walletTwin"), NameUUID(s.Brand()+":"+s.Value(key(who, "playerId"))+":"+Currency))
}

// KYC writes `who`'s identity status where the original's KYC rollup keeps it (`kyc_status` on the
// player twin), as scenario 251 does: `verified`, `unverified`, `pending` or `rejected`.
func (s *Session) KYC(who, status string) {
	s.ok(s.Setup(label(who)+"'s identity is "+status, "substrate", http.MethodPost,
		"/twins/casino-"+s.Brand()+"/"+s.Value(key(who, "playerId")), map[string]any{"kyc_status": status}, NoAuth))
}

// Fund deposits `amount` to `who`'s wallet through the wallet's internal deposit route, as merchant
// does on a captured payment, by the gateway and scheme given (eligibility reads them off the deposit
// record), then clears deposit turnover a brand rule may have set (scenario 251's
// s251ClearTurnoverGate, here every time, so both runs take the same steps).
func (s *Session) Fund(who string, amount int64, psp, scheme string) {
	ref := s.ID(key(who, "deposit"))
	s.ok(s.WalletSetup("deposit to "+label(who), http.MethodPost,
		"/internal/v1/wallet/"+s.Brand()+"/"+s.Value(key(who, "playerId"))+"/"+Currency+"/deposit",
		map[string]any{"paymentRef": ref, "amount": Decimal(amount), "terminalId": "payouts-driver", "pspId": psp, "scheme": scheme}))
	s.Turnover(who, 0)
}

// Turnover writes `who`'s deposit turnover pending, in cents, on the wallet twin.
func (s *Session) Turnover(who string, cents int64) {
	s.ok(s.Setup(label(who)+"'s deposit turnover pending is "+Decimal(cents), "substrate", http.MethodPost,
		"/twins/wallet/"+s.Value(key(who, "walletTwin")), map[string]any{"deposit_turnover_pending": cents}, NoAuth))
}

// Ready is `who` active, verified and funded with `amount`.
func (s *Session) Ready(who string, amount int64, named bool) {
	s.Active(who, named)
	s.KYC(who, "verified")
	s.Fund(who, amount, "payouts-driver-psp", "card")
}

// Balance reads `who`'s balance through the wallet's player route, as setup: the wallet's, not this
// service's.
func (s *Session) Balance(who string) Response {
	return s.Setup("read "+label(who)+"'s balance", "wallet", http.MethodGet,
		"/v1/wallet/"+s.Brand()+"/"+s.Value(key(who, "playerId"))+"/"+Currency+"/balance", nil, Auth(key(who, "token")))
}

// TerminalSpec is a terminal as merchant-service writes it on substrate (`eitheror-terminal`).
type TerminalSpec struct {
	Name                string
	Gateway             string // "manual" has no automated payout path in the original (WithdrawalRoutes:2277)
	Scheme              string
	Inactive            bool
	NoWithdrawals       bool
	RequiresDestination bool
	SupportedCurrencies string // a crypto terminal's coins, comma separated
	Automated           bool   // carries the sub-brand and psp-stub's credentials, so approval calls the gateway
	EmptyCredential     bool   // with Automated: an empty withdrawal key, which the gateway refuses at initiation
}

// Terminal writes a terminal under a fresh UUID `name` and appends it to the brand's terminal index,
// as merchant-service does (scenario 251's s251Terminal). The terminal is made inactive after the
// scenario, so a later scenario's listing does not offer it.
func (s *Session) Terminal(name string, t TerminalSpec) string {
	id := s.UUID(name)
	subBrand := NameUUID("seed.sub-brand." + s.Brand())
	props := map[string]any{
		"id": id, "name": t.Name, "status": "active", "gateway": t.Gateway, "scheme": t.Scheme,
		"supportsWithdrawal": !t.NoWithdrawals, "requiresPayoutDestination": t.RequiresDestination,
	}
	if t.Inactive {
		props["status"] = "inactive"
	}
	if t.SupportedCurrencies != "" {
		props["supportedCurrencies"] = t.SupportedCurrencies
	}
	if t.Automated {
		s.Remember("stubWithdrawalKey", stubWithdrawKey)
		s.Remember("stubSignKey", stubSignKey)
		creds := map[string]string{"userId": stubUserID, "withdrawalApiKey": stubWithdrawKey, "accountId": "stub-account",
			"purseId": "stub-purse", "signKey": stubSignKey}
		if t.EmptyCredential {
			creds["withdrawalApiKey"] = ""
		}
		blob, _ := json.Marshal(creds)
		props["sub_brand_id"] = subBrand
		props["properties"] = string(blob) // merchant-service writes it stringified (WithdrawalRoutes:1897-1912)
	}
	s.ok(s.Setup("the terminal "+t.Name, "substrate", http.MethodPost, "/twins/eitheror-terminal/"+id, props, NoAuth))
	index := "/twins/eitheror-terminal-index/" + NameUUID("eitheror-terminal-index."+subBrand)
	ids := s.indexed(index)
	if ids == "" {
		ids = id
	} else {
		ids += "|" + id
	}
	s.ok(s.Setup("add the terminal to the brand's index", "substrate", http.MethodPost, index, map[string]any{"ids": ids}, NoAuth))
	s.Cleanup(func() {
		s.Setup("retire the terminal "+t.Name, "substrate", http.MethodPost, "/twins/eitheror-terminal/"+id, map[string]any{"status": "inactive"}, NoAuth)
		// Out of the index too: casino-service reads every terminal the index names at once, and past 64 its
		// connection pool refuses the reads and every terminal is ineligible.
		var left []string
		for _, v := range strings.Split(s.indexed(index), "|") {
			if v != id {
				left = append(left, v)
			}
		}
		s.Setup("take the terminal out of the brand's index", "substrate", http.MethodPost, index,
			map[string]any{"ids": strings.Join(left, "|")}, NoAuth)
	})
	return id
}

// indexed is the brand's terminal index, `|`-separated, as substrate holds it.
func (s *Session) indexed(index string) string {
	r := s.Setup("read the brand's terminal index", "substrate", http.MethodGet, index, nil, NoAuth)
	ids := r.Str("ids")
	if ids == "" {
		if p, ok := r.Body["properties"].(map[string]any); ok {
			ids, _ = p["ids"].(string)
		}
	}
	return ids
}

// Manual is the scenario's terminal with no automated payout, requiring a destination when asked.
func (s *Session) Manual(requiresDestination bool) string {
	k := map[bool]string{true: "bankTerminal", false: "manualTerminal"}[requiresDestination]
	if v, ok := s.cached[k]; ok {
		return v
	}
	s.cached[k] = s.Terminal(k, TerminalSpec{Name: "payouts driver " + k, Gateway: "manual", Scheme: "eubanking",
		RequiresDestination: requiresDestination})
	return s.cached[k]
}

// Automated is the scenario's Unity Finance terminal, whose payout psp-gateway sends to psp-stub.
func (s *Session) Automated(emptyCredential bool) string {
	k := map[bool]string{true: "refusedTerminal", false: "ufTerminal"}[emptyCredential]
	if v, ok := s.cached[k]; ok {
		return v
	}
	s.cached[k] = s.Terminal(k, TerminalSpec{Name: "payouts driver " + k, Gateway: "unityfinance", Scheme: "eubanking",
		RequiresDestination: true, Automated: true, EmptyCredential: emptyCredential})
	return s.cached[k]
}

// Destination saves a bank destination for `who` in the given names and captures its id as `capture`.
func (s *Session) Destination(name, who, first, last, iban, capture string, setup bool) Response {
	body := map[string]any{"firstName": first, "lastName": last, "iban": iban, "label": "payouts driver"}
	path := s.PlayerPath(who) + "/payout-destinations"
	var r Response
	if setup {
		r = s.Setup(name, "casino", http.MethodPost, path, body, Auth(key(who, "token")))
	} else {
		r = s.Casino(name, http.MethodPost, path, body, Auth(key(who, "token")))
	}
	if r.Status == http.StatusCreated && capture != "" {
		// The original answers `payout-destination/<uuid>` and routes on the uuid alone: its
		// `/{id}/set-default` and `/{id}` take one path segment and keep only what follows the last
		// slash (PayoutDestinationAccess.entityId), so the whole id there misses the route and is
		// answered 401 by the next one. Its other routes take either form.
		id := r.Str("id")
		s.Grab(capture, "$.id", id[strings.LastIndex(id, "/")+1:])
	}
	return r
}

// Destinations lists `who`'s destinations, read again until `until` holds: the original reads them from
// substrate's projection, which shows a destination a moment after it is saved, changed or deleted.
func (s *Session) Destinations(name, who string, until func(Response) bool) Response {
	return s.Eventually(func() Response {
		return s.Casino(name, http.MethodGet, s.PlayerPath(who)+"/payout-destinations", nil, Auth(key(who, "token")))
	}, until)
}

// Saved says whether the listing holds the destination whose uuid is `id` (the original names it
// `payout-destination/<uuid>`), and whether it is the default.
func Saved(r Response, id string) (listed, isDefault bool) {
	list, _ := r.Body["destinations"].([]any)
	for _, e := range list {
		if m, ok := e.(map[string]any); ok {
			if got, _ := m["id"].(string); id != "" && strings.HasSuffix(got, id) {
				def, _ := m["isDefault"].(bool)
				return true, def
			}
		}
	}
	return false, false
}

// Ask is a request body as the player site sends it: the amount, the currency and the terminal, with
// `extra` added (destinationId, walletAddress, cryptoCurrency, confirmForfeit).
func Ask(amount int64, terminal string, extra map[string]any) map[string]any {
	b := map[string]any{"amount": Decimal(amount), "currency": Currency, "terminalId": terminal}
	for k, v := range extra {
		b[k] = v
	}
	return b
}

// Request posts `who`'s withdrawal request and captures its id as `capture` when it is created.
func (s *Session) Request(name, who string, body map[string]any, capture string, setup bool) Response {
	path := s.PlayerPath(who) + "/withdrawal-requests"
	var r Response
	if setup {
		r = s.Setup(name, "casino", http.MethodPost, path, body, Auth(key(who, "token")))
	} else {
		r = s.Casino(name, http.MethodPost, path, body, Auth(key(who, "token")))
	}
	if r.Status == http.StatusCreated && capture != "" {
		s.Grab(capture, "$.requestId", r.Str("requestId"))
	}
	return r
}

// Held is `who`'s request of `amount` to the terminal, created as setup and captured as `capture`.
func (s *Session) Held(who string, amount int64, terminal, capture string, extra map[string]any) {
	s.Want(s.Request("request a withdrawal of "+Decimal(amount), who, Ask(amount, terminal, extra), capture, true), http.StatusCreated)
}

// OnWallet waits until the request captured as `id` is among `who`'s wallet's requests: the original's
// limits count what the wallet twin's HAS_WITHDRAWAL_REQUEST edges name (WithdrawalLimitChecker), which
// substrate shows a moment after the hold is answered.
func (s *Session) OnWallet(who, id string) {
	s.Observe("the request is on "+label(who)+"'s wallet", "substrate", http.MethodGet,
		"/twins/wallet/"+s.Value(key(who, "walletTwin"))+"/edges/HAS_WITHDRAWAL_REQUEST", NoAuth,
		func(r Response) bool { return r.Status == http.StatusOK && strings.Contains(string(r.Raw), s.Value(id)) })
}

// Requests lists `who`'s requests as the player's account does.
func (s *Session) Requests(name, who string) Response {
	return s.Casino(name, http.MethodGet, s.PlayerPath(who)+"/withdrawal-requests", nil, Auth(key(who, "token")))
}

// Cancel is the player's cancel of the request captured as `id`.
func (s *Session) Cancel(name, who, id string, setup bool) Response {
	path := s.PlayerPath(who) + "/withdrawal-requests/" + s.Value(id)
	if setup {
		return s.Setup(name, "casino", http.MethodDelete, path, nil, Auth(key(who, "token")))
	}
	return s.Casino(name, http.MethodDelete, path, nil, Auth(key(who, "token")))
}

// Decide is an operator's action on the request captured as `id`: approve, reject, confirm-payout or
// mark-payout-failed, with `body` ({"reason": …} where one is given).
func (s *Session) Decide(name, action, id string, body map[string]any, setup bool) Response {
	if body == nil {
		body = map[string]any{}
	}
	path := s.Queue() + "/" + s.Value(id) + "/" + action
	if setup {
		return s.Setup(name, "casino", http.MethodPost, path, body, Operator)
	}
	return s.Casino(name, http.MethodPost, path, body, Operator)
}

// Settled waits until the operator's read of the request captured as `id` shows `status`: the
// original writes the request twin through the wallet, and its reads can trail the answer (scenario
// 251's s251WaitRequestStatus).
func (s *Session) Settled(id, status string) Response {
	return s.Observe("the request is "+status, "casino", http.MethodGet, s.Queue()+"/"+s.Value(id), Operator,
		func(r Response) bool { return r.Status == http.StatusOK && r.Str("status") == status })
}

// Approved is `who`'s request of `amount` to a terminal with no automated payout, approved as setup,
// so it awaits manual payment.
func (s *Session) Approved(who string, amount int64, id string) {
	s.Held(who, amount, s.Manual(false), id, nil)
	s.Want(s.Decide("approve", "approve", id, nil, true), http.StatusOK)
	s.Settled(id, "AWAITING_MANUAL_PAYOUT")
}

// Completed is `who`'s request of `amount` paid by hand: approved and its manual payment confirmed.
func (s *Session) Completed(who string, amount int64, id string) {
	s.Approved(who, amount, id)
	s.Want(s.Decide("confirm the manual payment", "confirm-payout", id, nil, true), http.StatusOK)
	s.Settled(id, "COMPLETED")
}

// Initiated is `who` (ready, named, with a saved destination) requesting `amount` to the scenario's
// Unity Finance terminal and an operator approving it, which psp-gateway initiates with psp-stub. The
// stub is held at `pending` first, so no webhook settles the payout before the scenario does. With
// `refused`, the terminal's credential is empty and the gateway refuses the payout at initiation.
// The approval is a step when `step` is set, setup otherwise; the provider's reference is captured as
// `pspReference` once the original has recorded it.
func (s *Session) Initiated(who string, amount int64, id string, refused, step bool) Response {
	s.Ready(who, amount, true)
	terminal := s.Automated(refused)
	dest := key(who, "destination")
	s.Want(s.Destination("save "+label(who)+"'s destination", who, FirstName, LastName, IBAN, dest, true), http.StatusCreated)
	s.Want(s.Setup("psp-stub holds Unity Finance's payouts pending", "psp-stub", http.MethodPost, "/__control",
		map[string]any{"outcome": "pending"}, NoAuth), http.StatusOK)
	s.Held(who, amount, terminal, id, map[string]any{"destinationId": s.Value(dest)})
	var r Response
	if step {
		r = s.Decide("approve", "approve", id, nil, false)
	} else {
		r = s.Decide("approve", "approve", id, nil, true)
	}
	s.Want(r, http.StatusOK)
	if !refused {
		read := s.Observe("the payout is initiated", "casino", http.MethodGet, s.Queue()+"/"+s.Value(id), Operator,
			func(r Response) bool { return r.Status == http.StatusOK && r.Str("payoutStatus") == "INITIATED" })
		if ref := read.Str("pspPayoutRef"); ref != "" {
			s.Grab(key(who, "pspReference"), "$.pspPayoutRef", ref)
		}
	}
	return r
}

// NeedsReview writes `NEEDS_REVIEW` on an initiated payout, as the original's reconciliation sweeper
// writes it once a provider's window passes with no outcome (PayoutReconciliationSweeper).
func (s *Session) NeedsReview(id string) {
	s.ok(s.Setup("the payout is marked needs review", "substrate", http.MethodPost, "/twins/withdrawal-request/"+s.Value(id),
		map[string]any{"payout_status": "NEEDS_REVIEW"}, NoAuth))
}

// ProviderSays is the outcome psp-gateway reports for the request captured as `id` (the internal
// route's body: `outcome`, `pspReference`, `failureCode`, `brandId`).
func (s *Session) ProviderSays(name, who, id, outcome, failureCode string) Response {
	body := map[string]any{"outcome": outcome, "brandId": s.Brand(), "pspReference": s.Value(key(who, "pspReference"))}
	if failureCode != "" {
		body["failureCode"] = failureCode
	}
	return s.Outcome(name, s.Value(id), body)
}

// Limits sets the brand's weekly and monthly limits in cents (nil leaves one unset) through the
// operator's config route, keeping the brand's turnover terms as they were, and restores the brand's
// limits after the scenario. The original keeps one limit per brand, read for EUR only
// (WithdrawalLimitChecker).
func (s *Session) Limits(weekly, monthly *int64) {
	path := "/v1/casino/brands/" + s.Brand() + "/withdrawal-config"
	before := s.Want(s.Setup("read the brand's withdrawal config", "casino", http.MethodGet, path, nil, Operator), http.StatusOK)
	put := func(name string, w, m any) {
		body := map[string]any{
			"withdrawalTurnoverMultiplier": before.Body["withdrawalTurnoverMultiplier"],
			"withdrawalTurnoverDefinition": before.Body["withdrawalTurnoverDefinition"],
			"weeklyWithdrawalLimitEur":     w,
			"monthlyWithdrawalLimitEur":    m,
		}
		s.Want(s.Setup(name, "casino", http.MethodPut, path, body, Operator), http.StatusOK)
	}
	major := func(v *int64) any {
		if v == nil {
			return nil
		}
		return float64(*v) / 100
	}
	put("set the brand's withdrawal limits", major(weekly), major(monthly))
	s.Cleanup(func() {
		put("restore the brand's withdrawal limits", before.Body["weeklyWithdrawalLimitEur"], before.Body["monthlyWithdrawalLimitEur"])
	})
}

// Grant awards `who` an ad hoc bonus as an operator, as scenario 251 does, which opens an active
// restricted funds record with no withdrawal policy of its own (read as funds and winnings), and waits
// for the wallet to report it active.
func (s *Session) Grant(who string) {
	s.Want(s.Setup("grant "+label(who)+" a bonus", "casino", http.MethodPost,
		"/v1/casino/bonus/grant/"+s.Brand()+"/"+s.Value(key(who, "playerId")),
		map[string]any{"bonusAmount": "10.00", "wager": "1", "currency": Currency}, Operator), http.StatusOK)
	s.Observe("the bonus is active", "wallet", http.MethodGet,
		"/v1/wallet/"+s.Brand()+"/"+s.Value(key(who, "playerId"))+"/"+Currency+"/bonus/status", Auth(key(who, "token")),
		func(r Response) bool { return r.Status == http.StatusOK && r.Str("bonusStatus") == "ACTIVE" })
}

// Terminals lists the terminals `who` may withdraw to.
func (s *Session) Terminals(name, who string) Response {
	return s.Casino(name, http.MethodGet, s.PlayerPath(who)+"/withdrawal-terminals", nil, Auth(key(who, "token")))
}

// Offered is the position of the terminal `id` in a listing, or -1.
func Offered(r Response, id string) int {
	list, _ := r.Body["terminals"].([]any)
	for i, t := range list {
		if m, ok := t.(map[string]any); ok && m["terminalId"] == id {
			return i
		}
	}
	return -1
}

// Listed is the request `id`'s status in a list of requests (the player's or the queue's), or "".
func Listed(r Response, id string) string {
	list, _ := r.Body["requests"].([]any)
	for _, e := range list {
		if m, ok := e.(map[string]any); ok && m["requestId"] == id {
			st, _ := m["status"].(string)
			return st
		}
	}
	return ""
}

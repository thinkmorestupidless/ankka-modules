package rewardsdriver

import (
	"net/http"
	"testing"
	"time"
)

// A scenario the original answers otherwise than its feature expects fails in the run's result and is
// still recorded: the recording is the original's behaviour, and the failure is a finding for the spec.

// Def is one recording: a feature scenario, or one example of an outline.
type Def struct {
	Name        string // as features/rewards/*.feature names the scenario
	Example     string // an outline's example, by its first column; empty for a scenario
	Requirement string
	Run         func(t testing.TB, s *Session)
}

// Scenario is the recording's name: the feature's, with an outline's example in brackets.
func (d Def) Scenario() string {
	if d.Example == "" {
		return d.Name
	}
	return d.Name + " [" + d.Example + "]"
}

// Reasons a feature scenario is not driven here.
const (
	suite     = "the original's Go end-to-end suite reaches it: "
	facts     = "facts are the port's casino.rewards; the original's Kafka and Customer.io fan-outs are dropped (spec, Changes from the original), so FactsSuite holds it"
	programme = "the programme's code document is new (spec 013 amendment); the original only recorded attribution from a code"
	stackSt8  = "fake-st8 serves no /api/operator/v1/bonus/offers, so the original on its kind stack cannot list spin values (it answers 502 provider_unavailable and lets every value through)"
	timers    = "a timer's deadline (days) is not waited by a run; the port's timer suites hold it"
	unreached = "needs the game provider to hold or fail provisioning, which fake-st8 never does"
)

// NotDriven are the feature scenarios this driver does not record, and why: most are recorded from the
// original's own suite (spec 011, Recordings), the rest are not shown by the original or not on its
// kind stack, and are held by the port's own suites.
var NotDriven = map[string]string{
	// facts.feature
	"a reward activating is published with the reward, the offer, the amount, the currency, the source and the code": facts,
	"a reward transition is published as the matching fact [cancelled]":                                              facts,
	"a reward transition is published as the matching fact [forfeited]":                                              facts,
	"a reward transition is published as the matching fact [revoked]":                                                facts,
	"a reward transition is published as the matching fact [converted]":                                              facts,
	"a reward transition is published as the matching fact [completed]":                                              facts,
	"a reward transition is published as the matching fact [expired]":                                                facts,
	"a reward transition is published as the matching fact [given as free rounds]":                                   facts,
	"a reward transition is published as the matching fact [finalised as free rounds]":                               facts,
	"the same transition applied twice publishes one fact":                                                           facts,
	// codes.feature
	"a brand's offer code entered in any case makes the offer visible and selects it":                                                suite + "TestScenario16_RewardGrant_TargetSelectActivateForfeit (enter-code, select); matching in any case is a change (spec, Changes from the original)",
	"a code no live offer carries and no bonus code document holds is answered invalid":                                              suite + "TestScenario21_CodeMinDepositDepositNumberWindow (wrong_code)",
	"an affiliate's bonus code from the programme's document selects the offer it names and the reward records the affiliate source": programme,
	"an operator creating a brand code already in the programme's document is refused":                                               programme,
	// eligibility.feature
	"a player flagged as a suspected duplicate is refused on any path":                                                          suite + "TestScenario21_SuspectedDuplicate_RefusedOnEveryPath, TestScenario18_MultiAccountDetection_BonusBlock",
	"a player with an active reward claiming another offer is refused":                                                          suite + "TestScenario21_Concurrency_ActiveBonusConflict",
	"an offer allowing two open rewards is claimed beside one the player holds":                                                 suite + "TestScenario21_Concurrency_ActiveBonusConflict",
	"an offer setting no maximum of open rewards is claimed beside any the player holds":                                        suite + "TestScenario21_Concurrency_ActiveBonusConflict",
	"an offer that conflicts with one the player holds open is refused":                                                         suite + "TestScenario21_ConflictsWith",
	"an offer claimed the maximum times per player is refused on the next claim":                                                suite + "TestScenario21_MaxClaimsAndExclusion",
	"an offer excluded by one the player already claimed is refused":                                                            suite + "TestScenario21_MaxClaimsAndExclusion",
	"a claim that fails one rule is refused with that rule's reason [an offer code the offer does not carry]":                   "the original reaches the evaluator's INVALID_CODE only through /claim, which is dropped (settled 2026-11-01); /enter-code answers invalid_code before evaluating, which TestScenario21_CodeMinDepositDepositNumberWindow records",
	"a claim that fails one rule is refused with that rule's reason [a deposit below the offer's minimum deposit]":              suite + "TestScenario21_CodeMinDepositDepositNumberWindow",
	"a claim that fails one rule is refused with that rule's reason [a deposit that is not the offer's deposit number]":         suite + "TestScenario21_CodeMinDepositDepositNumberWindow",
	"a claim that fails one rule is refused with that rule's reason [a claim after the claim window counted from registration]": suite + "TestScenario21_CodeMinDepositDepositNumberWindow",
	"an operator's award proceeds past every rule but the duplicate checks and the open reward maximum":                         suite + "TestScenario21_CleanGrantEveryPath (operator grant)",
	"an offer code verified when the offer was targeted is not demanded again when the deposit confirms":                        suite + "TestScenario16_RewardGrant_TargetSelectActivateForfeit",
	// free-rounds.feature
	"free rounds given on an eligible game at an offered spin value are created at the game provider and live with the offer's expiry":    suite + "TestScenarioFreeSpinsPlay_BonusBalanceDestination, _RealBalanceDestination",
	"a spin value the game provider does not offer is refused naming the values it does":                                                  stackSt8,
	"free rounds the game provider does not confirm within the time allowed are cancelled and no longer listed to the player":             unreached,
	"free rounds given while the game provider has not yet confirmed them are answered as processing":                                     unreached,
	"a round settled with a win uses one round and accumulates the win":                                                                   suite + "TestScenarioFreeSpinsPlay_BonusBalanceDestination",
	"the same round settling again changes nothing":                                                                                       suite + "TestScenarioFreeSpinsPlay_* (the adapter's round ids); RoundsSuite holds a repeat",
	"the last round settling under an offer paying to real balance credits the accumulated amount to real balance":                        suite + "TestScenarioFreeSpinsPlay_RealBalanceDestination",
	"winnings paying to real balance while a reward is active land on the active reward's restricted funds":                               suite + "TestScenarioFreeSpinsPlay_StacksOnActiveBonus",
	"the last round settling with nothing won pays nothing and completes the reward":                                                      suite + "TestScenarioFreeSpinsPlay_* with wins of 0.00",
	"the last round settling under an offer paying to restricted funds awards the accumulated amount with the offer's multiplier and cap": suite + "TestScenarioFreeSpinsPlay_BonusBalanceDestination",
	"winnings awarded as restricted funds while a reward is already active stack beside it":                                               suite + "TestScenarioFreeSpinsPlay_StacksOnActiveBonus",
	"free rounds past their expiry with something won are paid in full and completed":                                                     timers,
	"free rounds past their expiry with nothing won are expired":                                                                          timers,
	"a round taken before expiry that settles late is still honoured":                                                                     timers,
	"an operator asking a game's spin values in a currency is answered the game provider's values sorted":                                 stackSt8,
	"an operator asking spin values on a brand with no free rounds set up is answered with none, as not configured":                       "the kind stack sets ST8_API_URL, so the original never answers not_configured there",
	"a launch naming no free rounds is given the player's oldest free rounds for that game":                                               suite + "TestScenarioFreeSpinsPlay_* (fsLaunchAndBind's freeSpinsGrantRef)",
	// lifecycle.feature
	"a conversion with a cap puts the reward in converted pending cap and sets a cap timer for the cap window":                                  suite + "TestScenario19_BonusWageringCompletesToRealMoney",
	"a conversion with no maximum convertible amount still waits in converted pending cap":                                                      suite + "TestScenario19_BonusWageringCompletesToRealMoney, TestScenario244_ConversionRecordsActivationBaseline",
	"a reward whose restricted funds are lost in play is forfeited":                                                                             suite + "TestScenario_BonusBust_LosingRoundForfeitsGrant, TestScenario254_*",
	"a cap deadline passing closes the record on the wallet and the reward":                                                                     timers,
	"a reward past its claim deadline or wagering window is forfeited on the wallet and expired [claim deadline]":                               suite + "TestScenario23_RewardGrantExpiry (just test-e2e-cadence)",
	"a reward past its claim deadline or wagering window is forfeited on the wallet and expired [wagering window]":                              suite + "TestScenario23_RewardGrantExpiry (just test-e2e-cadence)",
	"a player switching a second open reward to primary reorders the records on the wallet and records the switch":                              suite + "TestScenarioFreeSpinsPlay_StacksOnActiveBonus (the switch)",
	"a player cancelling a reward with an activation baseline forfeits the record and claws back winnings since activation less deposits since": suite + "TestScenario244_CancelWithBaselineClawsBackOnlyWinnings",
	"a player cancelling a reward with no activation baseline claws back nothing":                                                               suite + "TestScenario244_CancelWithoutBaselineSucceedsWithZeroClawback",
	"a cancel naming no active reward is refused as a conflict":                                                                                 suite + "TestScenario244_CancelWithNoBonusIsConflict",
	"an operator revoking a reward as forfeit forfeits the record and leaves real balance untouched":                                            suite + "TestScenario16_RewardGrant_LegacyStatusStillTransitions (its revoke)",
	"an operator revoking a reward as reclaim forfeits the record and applies the clawback":                                                     "a clawback needs real winnings booked while the record is active, and the driver books no game rounds; TestScenario244_CancelWithBaselineClawsBackOnlyWinnings records the same computation on the player's cancel",
	"a record an operator adjusted to zero forfeits the reward":                                                                                 suite + "TestScenario245_ZeroedBonusClosesRecordAndGrant",
	"a record an operator adjusted but not to zero leaves the reward active":                                                                    suite + "TestScenario245_PartialBonusAdjustLeavesRecordActive",
	// offers.feature
	"an operator creates a deposit match offer and it is listed active with every field as given":                          suite + "TestScenario21_OfferValidation",
	"a deposit match offer without a maximum value is refused naming it as required":                                       suite + "TestScenario21_OfferValidation",
	"a pure free rounds offer is created without a maximum value":                                                          suite + "TestScenarioFreeSpinsPlay_RealBalanceDestination (fsCreateOffer)",
	"a free rounds offer paying to restricted funds without a maximum convertible amount is refused naming it as required": suite + "TestScenario21_OfferValidation",
	"selecting an active offer with no deposit required activates the reward at once with the offer's fixed amount":        suite + "TestScenario21_CleanGrantEveryPath",
	"selecting an active offer requiring a deposit leaves the reward pending":                                              suite + "TestScenario16_RewardGrant_TargetSelectActivateForfeit",
	"the next confirmed deposit activates a pending reward with the matched amount and clears the selection":               suite + "TestScenario16_RewardGrant_TargetSelectActivateForfeit",
	"a deposit below the offer's minimum leaves the reward pending and is credited as real balance":                        suite + "TestScenario21_CodeMinDepositDepositNumberWindow",
	"a deposit after the deposit window expires the pending selection and awards nothing":                                  timers,
	"the same deposit reported again under the same payment id awards nothing twice":                                       "the original's deposit notification is a TL function on a payment twin; DepositsSuite holds the repeat",
	"an offer the brand's engagement targeted at a player appears in their eligible list with the reason it is visible":    "the eligible list is dropped (settled 2026-11-01); TestScenario21_CleanGrantEveryPath records the webhook's target_offer",
	"targeting an offer at a player again changes nothing":                                                                 "the original's target made a second record (spec, Changes from the original); TargetSuite holds it",
	"a player with rewards available or pending sees only those offers as selectable":                                      suite + "TestScenario16_RewardGrant_TargetSelectActivateForfeit (state)",
	"a player with no reward sees the offers visible to them by code or targeting":                                         suite + "TestScenario16_RewardGrant_TargetSelectActivateForfeit (state)",
	"a player reading their reward state sees the active reward's balance, requirement and progress and their history":     suite + "TestScenario245_TurnoverDisplayFollowsWalletPending, TestScenario16_*",
	"targeting an offer of free rounds, or of a deposit match with free rounds, is refused":                                suite + "TestScenarioFreeSpinsPlay_CustomerIOGrant",
}

// Scenarios is every recording the driver makes, in the features' order.
func Scenarios() []Def {
	var defs []Def
	defs = append(defs, codes()...)
	defs = append(defs, eligibility()...)
	defs = append(defs, freeRounds()...)
	defs = append(defs, lifecycle()...)
	defs = append(defs, offers()...)
	return defs
}

// codes.feature
func codes() []Def {
	return []Def{
		{Name: "a code entered as a preview tells the player the offer's terms and selects nothing", Requirement: "011/FR-020",
			Run: func(t testing.TB, s *Session) {
				s.Active()
				s.Offer("offerId", "code", nil)
				r := s.Eventually(func() Response {
					return s.Casino("preview the code", http.MethodPost, s.Bonus()+"/promo-code/"+s.Brand(),
						map[string]any{"code": s.Value("code"), "action": "preview"}, Player)
				}, func(r Response) bool { return r.Str("error") != "code_not_found" })
				s.WantField(s.Want(r, http.StatusOK), "valid", "true")
				st := s.Want(s.Casino("the player reads their reward state", http.MethodGet, s.Bonus()+"/"+s.Brand()+"/state", nil, Player), http.StatusOK)
				if sel := st.Str("selected"); sel != "" && sel != "null" {
					s.t.Errorf("a preview selected %s", sel)
				}
			}},
	}
}

// eligibility.feature: the #730 gates (FR-005, FR-034, FR-035), whose only oracle in the original is its
// unit specs. Each enters a code for an offer, the claim path every player has (`/enter-code` evaluates
// in Mode.Standard).
func eligibility() []Def {
	gate := func(name, what string, pending, blocked bool, since time.Duration, overrides map[string]string, status int, reason string) Def {
		return Def{Name: name, Requirement: "011/FR-005",
			Run: func(t testing.TB, s *Session) {
				s.Active()
				s.Flags(what, pending, blocked, false, since)
				s.Offer("offerId", "code", overrides)
				r := s.Want(s.EnterCode("claim the offer by its code", "code"), status)
				if reason != "" {
					s.WantField(r, "reason", reason)
				}
			}}
	}
	// rule is an example of the outline whose rule the original's suite does not reach: the player's
	// property written where casino-service keeps it, the offer's set on the offer.
	rule := func(example, what string, player map[string]any, offer map[string]string, reason string) Def {
		return Def{Name: "a claim that fails one rule is refused with that rule's reason", Example: example, Requirement: "011/FR-005",
			Run: func(t testing.TB, s *Session) {
				s.Active()
				s.ok(s.Setup(what, "substrate", http.MethodPost, s.PlayerTwin(), player, NoAuth))
				s.Offer("offerId", "code", offer)
				s.WantField(s.Want(s.EnterCode("claim the offer by its code", "code"), http.StatusConflict), "reason", reason)
			}}
	}
	return []Def{
		{Name: "the duplicate rule is skipped on a brand that turned duplicate detection off", Requirement: "011/FR-005",
			Run: func(t testing.TB, s *Session) {
				s.DetectionOff()
				if s.skipped {
					return
				}
				s.Active()
				s.Flags("the player is flagged as a suspected duplicate", false, false, true, 0)
				s.Offer("offerId", "code", nil)
				// casino-service's Unleash client reads the switch on its own schedule: the claim is made
				// again until the duplicate rule no longer refuses it.
				r := s.Eventually(func() Response { return s.EnterCode("claim the offer by its code", "code") },
					func(r Response) bool { return r.Str("reason") != "SUSPECTED_DUPLICATE" })
				s.Want(r, http.StatusOK)
			}},
		gate("a no-deposit offer claimed while the player's duplicate check is in progress is refused for now",
			"the player registered five minutes ago and their duplicate check is in progress",
			true, false, 5*time.Minute, NoDeposit, http.StatusConflict, "ELIGIBILITY_CHECK_UNAVAILABLE"),
		gate("a duplicate check still in progress ten minutes after registration no longer refuses",
			"the player registered eleven minutes ago and their duplicate check never ended",
			true, false, 11*time.Minute, NoDeposit, http.StatusOK, ""),
		gate("a no-deposit offer claimed by a player with a weak duplicate signal is refused",
			"the player's no-deposit offers are blocked by a weak duplicate signal",
			false, true, 0, NoDeposit, http.StatusConflict, "NO_DEPOSIT_OFFER_BLOCKED"),
		gate("a deposit offer is not refused for a duplicate check in progress or a weak duplicate signal",
			"the player's duplicate check is in progress and their no-deposit offers are blocked",
			true, true, 5*time.Minute, nil, http.StatusOK, ""),
		rule("a player outside the offer's segment", "the player is in the casual segment", map[string]any{"segments": "casual"},
			map[string]string{"segment_predicate": "vip"}, "SEGMENT_MISMATCH"),
		rule("a player below the offer's tier floor", "the player's tier is 1", map[string]any{"vip_tier_rank": "1"},
			map[string]string{"vip_tier_floor": "3"}, "VIP_TIER_TOO_LOW"),
		{Name: "an award by the brand's engagement during the duplicate check is answered to try again later", Requirement: "011/FR-005",
			Run: func(t testing.TB, s *Session) {
				s.Active()
				s.Flags("the player registered five minutes ago and their duplicate check is in progress", true, false, false, 5*time.Minute)
				s.Offer("offerId", "", NoDeposit)
				s.CRMKey()
				r := s.Webhook("the brand's engagement awards the offer", WebhookKey,
					map[string]any{"action": "grant_bonus", "player_id": s.Value("playerId"), "offer_id": s.Value("offerId")})
				s.WantField(s.Want(r, http.StatusServiceUnavailable), "error", "ELIGIBILITY_CHECK_UNAVAILABLE")
			}},
	}
}

// free-rounds.feature
func freeRounds() []Def {
	return []Def{
		{Name: "a game that is not eligible is refused", Requirement: "011/FR-023",
			Run: func(t testing.TB, s *Session) {
				s.Active()
				r := s.FreeRounds("give free rounds on a game that is not eligible", NoGame, 10, false)
				s.WantField(s.Want(r, http.StatusUnprocessableEntity), "error", "game_not_eligible")
			}},
		{Name: "free rounds given when the game provider cannot say which spin values it offers go ahead", Requirement: "011/FR-023",
			Run: func(t testing.TB, s *Session) {
				s.Active()
				v := s.Setup("the game provider cannot list its spin values", "casino", http.MethodGet,
					s.Bonus()+"/free-spins/spin-values/"+s.Brand()+"?gameId="+Game+"&currency="+Currency, nil, Operator)
				if v.Status == http.StatusOK && v.Str("source") == "st8" {
					s.t.Errorf("the game provider listed its spin values, so this scenario shows nothing: %s", v.Raw)
				}
				r := s.FreeRounds("give free rounds", Game, 10, false)
				s.WantField(s.Want(r, http.StatusOK), "type", "FREE_SPINS")
				s.Provisioned("the game provider holds the rounds", func(r Response) bool {
					_, n := s.Instance(r)
					return r.Status == http.StatusOK && n == 10
				})
			}},
	}
}

// lifecycle.feature
func lifecycle() []Def {
	return []Def{
		{Name: "a cancel naming a reward the player does not hold is answered reward not found", Requirement: "011/FR-016",
			Run: func(t testing.TB, s *Session) {
				s.Active()
				s.Want(s.FreeBonus("award the player a free bonus", 2000, "5", true), http.StatusOK)
				s.Granted()
				r := s.Casino("cancel a reward the player does not hold", http.MethodDelete, s.Bonus()+"/"+s.Brand()+"/cancel",
					map[string]any{"grantRef": "bonus-claims/" + s.UUID("otherReward")}, Player)
				s.WantField(s.Want(r, http.StatusNotFound), "error", "grant_not_found")
			}},
		{Name: "an operator revoking free rounds not yet played cancels them at the game provider", Requirement: "011/FR-017",
			Run: func(t testing.TB, s *Session) {
				s.Active()
				s.Want(s.FreeRounds("give the player free rounds", Game, 10, true), http.StatusOK)
				s.ClaimOf()
				r := s.Revoke("revoke the free rounds as forfeit", map[string]any{"mode": "forfeit"})
				s.WantField(s.Want(r, http.StatusOK), "status", "revoked")
				s.Provisioned("the game provider has cancelled them", func(r Response) bool {
					st, _ := s.Instance(r)
					return r.Status == http.StatusOK && st == "canceled"
				})
				s.Observe("the reward is revoked", "substrate", http.MethodGet, "/twins/bonus-claims/"+s.Value("claimId"), NoAuth,
					func(r Response) bool { return r.Status == http.StatusOK && r.Prop("status") == "REVOKED" })
			}},
		{Name: "an operator revoking free rounds already played out is refused", Requirement: "011/FR-017",
			Run: func(t testing.TB, s *Session) {
				s.Active()
				s.Want(s.FreeRounds("give the player free rounds", Game, 10, true), http.StatusOK)
				s.ClaimOf()
				// Played out, as settle-free-spins-round marks the grant once its last round settles.
				s.ok(s.Setup("the free rounds are played out", "substrate", http.MethodPost, "/twins/free-spins-grants/"+s.Value("grantId"),
					map[string]any{"status": "used", "spins_used": "10"}, NoAuth))
				r := s.Revoke("revoke the free rounds as forfeit", map[string]any{"mode": "forfeit"})
				s.WantField(s.Want(r, http.StatusConflict), "error", "FREE_SPINS_FINISHED")
			}},
		{Name: "an operator revoking as reclaim a reward whose record has no baseline is refused", Requirement: "011/FR-017",
			Run: func(t testing.TB, s *Session) {
				s.Active()
				s.Want(s.FreeBonus("award the player a free bonus", 2000, "5", true), http.StatusOK)
				s.Granted()
				// No baseline, as TestScenario244_CancelWithoutBaselineSucceedsWithZeroClawback strips it: on the
				// reward and on the player.
				s.ok(s.Setup("the reward has no baseline", "substrate", http.MethodPost, "/twins/bonus-claims/"+s.Value("claimId"),
					map[string]any{"activation_real_balance": nil, "activation_total_deposits": nil}, NoAuth))
				s.ok(s.Setup("the player has no baseline", "substrate", http.MethodPost, s.PlayerTwin(),
					map[string]any{"bonus_activation_real_balance": "", "bonus_activation_total_deposits": ""}, NoAuth))
				r := s.Revoke("revoke the reward as reclaim", map[string]any{"mode": "reclaim"})
				s.WantField(s.Want(r, http.StatusConflict), "error", "MISSING_BASELINE")
			}},
		{Name: "an operator revoking a reward with no mode is refused", Requirement: "011/FR-017",
			Run: func(t testing.TB, s *Session) {
				s.Active()
				s.Want(s.FreeBonus("award the player a free bonus", 2000, "5", true), http.StatusOK)
				s.Granted()
				s.WantField(s.Want(s.Revoke("revoke the reward naming no mode", map[string]any{}), http.StatusBadRequest), "error", "INVALID_MODE")
			}},
	}
}

// offers.feature
func offers() []Def {
	return []Def{
		{Name: "offers reordered by an operator are seen by players in that order", Requirement: "011/FR-004",
			Run: func(t testing.TB, s *Session) {
				first := s.Offer("firstOfferId", "", nil)
				second := s.Offer("secondOfferId", "", nil)
				r := s.Casino("reorder the offers", http.MethodPut, s.Bonus()+"/offers/"+s.Brand()+"/reorder",
					map[string]any{"orderedOfferIds": []string{second, first}}, Operator)
				s.WantField(s.Want(r, http.StatusOK), "updated", "2")
				// The original shows a player the order only through the eligible list, which is dropped
				// (settled 2026-11-01); the operator's reads are where it shows the order.
				a := s.Want(s.Casino("read the first offer", http.MethodGet, s.Bonus()+"/offers/"+s.Brand()+"/"+first, nil, Operator), http.StatusOK)
				b := s.Want(s.Casino("read the second offer", http.MethodGet, s.Bonus()+"/offers/"+s.Brand()+"/"+second, nil, Operator), http.StatusOK)
				s.WantField(a, "priorityOrder", "2")
				s.WantField(b, "priorityOrder", "1")
			}},
		{Name: "an operator reading a player's rewards sees every reward with its state, face value, source and clocks", Requirement: "011/FR-030",
			Run: func(t testing.TB, s *Session) {
				s.Active()
				s.Want(s.FreeBonus("award the player a free bonus", 2000, "5", true), http.StatusOK)
				r := s.Want(s.Rewards("read the player's rewards", false, func(r Response) bool { return Row(r.List, "ACTIVE") != nil }), http.StatusOK)
				row := Row(r.List, "ACTIVE")
				if row == nil || row["faceValue"] != "20.00" || row["grantedAt"] == "" {
					s.t.Errorf("the player's rewards: %s", r.Raw)
				}
			}},
		{Name: "an operator reading the brand's rewards sees them across players, filterable by state", Requirement: "011/FR-030",
			Run: func(t testing.TB, s *Session) {
				s.Active()
				s.Want(s.FreeBonus("award the player a free bonus", 2000, "5", true), http.StatusOK)
				r := s.Eventually(func() Response {
					return s.Casino("read the brand's active rewards", http.MethodGet,
						s.Backoffice()+"/bonuses?status=ACTIVE&search="+s.Value("playerId"), nil, Operator)
				}, func(r Response) bool { return r.Str("total") == "1" })
				s.WantField(s.Want(r, http.StatusOK), "total", "1")
				none := s.Casino("read the brand's expired rewards", http.MethodGet,
					s.Backoffice()+"/bonuses?status=EXPIRED&search="+s.Value("playerId"), nil, Operator)
				s.WantField(s.Want(none, http.StatusOK), "total", "0")
			}},
		{Name: "an operator deleting an offer deactivates it and keeps the rewards already given", Requirement: "011/FR-001",
			Run: func(t testing.TB, s *Session) {
				s.Active()
				offer := s.Offer("offerId", "", nil)
				s.Want(s.Setup("award the player the offer by hand", "casino", http.MethodPost,
					s.Bonus()+"/grant/"+s.Brand()+"/"+s.Value("playerId"), map[string]any{"offerId": offer, "depositAmount": "50.00"}, Operator), http.StatusOK)
				s.Rewards("read the player's rewards", true, func(r Response) bool { return Row(r.List, "ACTIVE") != nil })
				r := s.Casino("delete the offer", http.MethodDelete, s.Bonus()+"/offers/"+s.Brand()+"/"+offer, nil, Operator)
				s.WantField(s.Want(r, http.StatusOK), "status", "deactivated")
				read := s.Want(s.Casino("read the offer", http.MethodGet, s.Bonus()+"/offers/"+s.Brand()+"/"+offer, nil, Operator), http.StatusOK)
				s.WantField(read, "isActive", "false")
				after := s.Want(s.Casino("read the player's rewards", http.MethodGet, s.Backoffice()+"/players/"+s.Value("playerId")+"/bonuses", nil, Operator), http.StatusOK)
				if Row(after.List, "ACTIVE") == nil {
					s.t.Errorf("the reward given is no longer active: %s", after.Raw)
				}
			}},
		{Name: "an operator giving a free bonus with no offer awards restricted funds with the amount and wagering they set", Requirement: "011/FR-017",
			Run: func(t testing.TB, s *Session) {
				s.Active()
				r := s.Want(s.FreeBonus("give the player a free bonus", 2000, "5", false), http.StatusOK)
				s.WantField(r, "status", "granted")
				s.WantField(r, "wageringRequired", "100.00")
				s.Granted()
				s.Observe("the reward is active with its wagering window", "substrate", http.MethodGet, "/twins/bonus-claims/"+s.Value("claimId"), NoAuth,
					func(r Response) bool { return r.Status == http.StatusOK && r.Prop("status") == "ACTIVE" })
			}},
		{Name: "an operator giving free rounds with no offer creates them at the game provider", Requirement: "011/FR-017",
			Run: func(t testing.TB, s *Session) {
				s.Active()
				r := s.FreeRounds("give the player free rounds", Game, 10, false)
				s.WantField(s.Want(r, http.StatusOK), "type", "FREE_SPINS")
				s.Provisioned("the game provider holds 10 rounds under the reward", func(r Response) bool {
					_, n := s.Instance(r)
					return r.Status == http.StatusOK && n == 10
				})
			}},
	}
}

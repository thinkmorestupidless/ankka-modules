import { describe, expect, test } from "vitest";
import { has, render, t } from "../../src/shared/messages/index.ts";
import { AllowedByCodes, explain } from "../../src/shared/refusals.ts";
import { Actions, offeredIn } from "../../src/web/actions/index.ts";
import { bodyOf as awardOf } from "../../src/web/areas/rewards/grant.tsx";
import { bodyOf, changesOf, formOf } from "../../src/web/areas/rewards/offers.tsx";
import { dependantsOf, progressOf, type Offer } from "../../src/web/areas/rewards/read.ts";

describe("a refused award", () => {
  test("rewards' ineligible answer is read by its reason, with what would allow it in the catalogue's words", () => {
    const r = explain(409, JSON.stringify({ status: "ineligible", reason: "SUSPECTED_DUPLICATE", allowedBy: ["fraud_flag_cleared", "brand_detection_off"] }));
    expect(r.code).toBe("suspected_duplicate");
    expect(render(r.message)).toBe(t("refusal.suspected_duplicate"));
    expect(render(r.next!)).toBe(
      "It would be allowed by the player's suspected duplicate flag being cleared; the brand turning duplicate detection off.",
    );
  });

  test("an empty allowedBy leaves the reason's own next step", () => {
    const r = explain(409, JSON.stringify({ status: "ineligible", reason: "SUSPECTED_DUPLICATE", allowedBy: [] }));
    expect(render(r.next!)).toBe(t("refusal.suspected_duplicate.next"));
  });

  test("a reason the catalogue lacks is shown as given, and an unknown code as itself", () => {
    const r = explain(409, JSON.stringify({ status: "ineligible", reason: "NEW_RULE", allowedBy: ["something_new"] }));
    expect(render(r.message)).toBe("new_rule: NEW_RULE");
    expect(render(r.next!)).toBe("It would be allowed by something_new.");
  });

  test("every code rewards names has its words, and every reason rewards answers has its message", () => {
    for (const c of AllowedByCodes) expect(has(`refusal.allowedBy.${c}`), c).toBe(true);
    const reasons = ["ELIGIBILITY_CHECK_UNAVAILABLE", "NO_DEPOSIT_OFFER_BLOCKED", "SUSPECTED_DUPLICATE", "ACTIVE_BONUS_CONFLICT", "CONFLICTING_GRANT", "MAX_CLAIMS_REACHED", "EXCLUDED_BY_PRIOR_OFFER", "INVALID_CODE", "BELOW_MIN_DEPOSIT", "DEPOSIT_NUMBER_MISMATCH", "CLAIM_WINDOW_EXPIRED", "SEGMENT_MISMATCH", "VIP_TIER_TOO_LOW", "ALREADY_CLAIMED", "BONUS_ALREADY_ACTIVE", "WALLET_NOT_FOUND"];
    for (const reason of reasons) expect(has(`refusal.${reason.toLowerCase()}`), reason).toBe(true);
  });

  test("an error code still wins over a reason", () => {
    expect(explain(409, JSON.stringify({ error: "NO_ACTIVE_BONUS", reason: "x" })).code).toBe("no_active_bonus");
  });
});

describe("a reward's actions", () => {
  test.each([
    ["active", ["reward.forfeit", "reward.reclaim"]],
    ["converted_pending_cap", []],
    ["forfeited", []],
    ["closed", []],
    ["revoked", []],
  ])("%s offers %j, as rewards' revoke accepts", (state, wanted) => {
    expect((["reward.forfeit", "reward.reclaim"] as const).filter((a) => offeredIn(Actions[a], state))).toEqual(wanted);
  });

  test("wagering progress is a floored per cent, at most 100, none when nothing is to be wagered", () => {
    expect(progressOf(120000, 300000)).toBe(40);
    expect(progressOf(299999, 300000)).toBe(99);
    expect(progressOf(400000, 300000)).toBe(100);
    expect(progressOf(10, 0)).toBeUndefined();
  });
});

describe("an award's body", () => {
  const form = { how: "offer" as const, offerId: "", deposit: "", currency: "", amount: "", wager: "", maxConvertible: "", roundsMaxConvertible: "", gameId: "", provider: "", spinCount: "", spinValue: "", destination: "real_balance" as const, expiryDays: "" };

  test("an offer, with a deposit in the offer's currency when given", () => {
    expect(awardOf({ ...form, offerId: "o-1" }, "EUR")).toEqual({ offerId: "o-1" });
    expect(awardOf({ ...form, offerId: "o-1", deposit: "50.00" }, "EUR")).toEqual({ offerId: "o-1", depositAmount: 5000 });
    expect(awardOf({ ...form, offerId: "" }, "EUR")).toBeUndefined();
  });

  test("a bonus by hand in minor units of its currency", () => {
    expect(awardOf({ ...form, how: "bonus", currency: "kwd", amount: "1.5", wager: "10" }, undefined)).toEqual({ bonusAmount: 1500, wager: 10, currency: "KWD" });
    expect(awardOf({ ...form, how: "bonus", currency: "EUR", amount: "", wager: "10" }, undefined)).toBeUndefined();
  });

  test("free rounds by hand; winnings to restricted funds need the most they may convert", () => {
    const rounds = { ...form, how: "freeRounds" as const, gameId: "vs20doghouse", spinCount: "20", spinValue: "20" };
    expect(awardOf(rounds, undefined)).toEqual({ type: "FREE_SPINS", gameId: "vs20doghouse", spinCount: 20, spinValue: 20, winningsDestination: "real_balance" });
    expect(awardOf({ ...rounds, destination: "bonus_balance" }, undefined)).toBeUndefined();
    expect(awardOf({ ...rounds, destination: "bonus_balance", roundsMaxConvertible: "5000", wager: "5" }, undefined)).toMatchObject({ maxConvertibleAmount: 5000, wageringMultiplier: 5 });
  });

  test("free rounds' amounts are typed in the player's currency when it is known", () => {
    const rounds = { ...form, how: "freeRounds" as const, gameId: "vs20doghouse", spinCount: "20", spinValue: "0.20", destination: "bonus_balance" as const, roundsMaxConvertible: "50" };
    expect(awardOf(rounds, undefined, "EUR")).toMatchObject({ spinValue: 20, maxConvertibleAmount: 5000 });
    expect(awardOf({ ...rounds, spinValue: "20", roundsMaxConvertible: "50" }, undefined, "JPY")).toMatchObject({ spinValue: 20, maxConvertibleAmount: 50 });
  });

  test("the most a bonus may convert never carries over to free rounds", () => {
    const typed = { ...form, how: "bonus" as const, currency: "EUR", amount: "10", wager: "1", maxConvertible: "50" };
    expect(awardOf(typed, undefined)).toMatchObject({ maxConvertibleAmount: 5000 });
    const switched = { ...typed, how: "freeRounds" as const, gameId: "g", spinCount: "1", spinValue: "20", destination: "bonus_balance" as const };
    expect(awardOf(switched, undefined)).toBeUndefined();
  });
});

describe("an offer", () => {
  const offer: Offer = {
    id: "o-1",
    name: "Welcome match",
    type: "DEPOSIT_MATCH",
    active: true,
    bonusPercentage: 10000,
    maxBonusValue: 10000,
    minDepositAmount: 2000,
    wageringMultiplier: 30,
    currency: "EUR",
    excludedOfferIds: [],
    conflictsWith: [],
    redemptionCodes: [{ code: "WELCOME", isAffiliate: true }],
  };

  test("its form read back is its body, amounts in minor units and the match in basis points", () => {
    const read = bodyOf(formOf(offer, "UTC"), "UTC", offer.redemptionCodes);
    expect("body" in read && read.body).toMatchObject({ bonusPercentage: 10000, maxBonusValue: 10000, minDepositAmount: 2000, currency: "EUR", depositNumberTarget: "ANY", redemptionCodes: [{ code: "WELCOME", isAffiliate: true }] });
  });

  test("an edit sends only what changed, a field cleared as null", () => {
    const before = bodyOf(formOf(offer, "UTC"), "UTC", offer.redemptionCodes);
    const after = bodyOf({ ...formOf(offer, "UTC"), maxBonusValue: "150.00", minDepositAmount: "" }, "UTC", offer.redemptionCodes);
    if (!("body" in before) || !("body" in after)) throw new Error("unread");
    expect(changesOf(before.body, after.body)).toEqual({ maxBonusValue: 15000, minDepositAmount: null });
  });

  test("a field that cannot be read is named", () => {
    expect(bodyOf({ ...formOf(offer, "UTC"), wageringMultiplier: "x" }, "UTC")).toEqual({ invalid: "offers.field.wageringMultiplier" });
    expect(bodyOf({ ...formOf(offer, "UTC"), validFrom: "2026-02-30T10:00" }, "UTC")).toEqual({ invalid: "offers.field.validFrom" });
  });

  test("its times are the brand's zone's wall time, whatever the browser's", () => {
    const timed = { ...offer, validFrom: "2026-12-01T03:00:00Z", validUntil: null };
    const form = formOf(timed, "America/Sao_Paulo");
    expect(form.validFrom).toBe("2026-12-01T00:00");
    const read = bodyOf({ ...form, validUntil: "2026-12-31T23:59" }, "America/Sao_Paulo");
    expect("body" in read && read.body).toMatchObject({ validFrom: "2026-12-01T03:00:00.000Z", validUntil: "2027-01-01T02:59:00.000Z" });
  });

  test("the offers that exclude or conflict with one are its dependants, in the brand's order", () => {
    const offers: Offer[] = [offer, { ...offer, id: "o-2", name: "Reload", excludedOfferIds: ["o-1"] }, { ...offer, id: "o-3", name: "Other" }, { ...offer, id: "o-4", name: "Roller", conflictsWith: ["o-1"] }];
    expect(dependantsOf("o-1", offers).map((d) => [d.offer.id, d.how])).toEqual([
      ["o-2", "excludes"],
      ["o-4", "conflicts"],
    ]);
  });
});

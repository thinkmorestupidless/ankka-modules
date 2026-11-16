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
  const form = { how: "offer" as const, offerId: "", deposit: "", currency: "", amount: "", wager: "", maxConvertible: "", gameId: "", provider: "", spinCount: "", spinValue: "", destination: "real_balance" as const, expiryDays: "" };

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
    expect(awardOf({ ...rounds, destination: "bonus_balance", maxConvertible: "5000", wager: "5" }, undefined)).toMatchObject({ maxConvertibleAmount: 5000, wageringMultiplier: 5 });
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
    const read = bodyOf(formOf(offer), offer.redemptionCodes);
    expect("body" in read && read.body).toMatchObject({ bonusPercentage: 10000, maxBonusValue: 10000, minDepositAmount: 2000, currency: "EUR", depositNumberTarget: "ANY", redemptionCodes: [{ code: "WELCOME", isAffiliate: true }] });
  });

  test("an edit sends only what changed, a field cleared as null", () => {
    const before = bodyOf(formOf(offer), offer.redemptionCodes);
    const after = bodyOf({ ...formOf(offer), maxBonusValue: "150.00", minDepositAmount: "" }, offer.redemptionCodes);
    if (!("body" in before) || !("body" in after)) throw new Error("unread");
    expect(changesOf(before.body, after.body)).toEqual({ maxBonusValue: 15000, minDepositAmount: null });
  });

  test("a field that cannot be read is named", () => {
    expect(bodyOf({ ...formOf(offer), wageringMultiplier: "x" })).toEqual({ invalid: "offers.field.wageringMultiplier" });
  });

  test("the offers that exclude or conflict with one are its dependants, in the brand's order", () => {
    const offers: Offer[] = [offer, { ...offer, id: "o-2", name: "Reload", excludedOfferIds: ["o-1"] }, { ...offer, id: "o-3", name: "Other" }, { ...offer, id: "o-4", name: "Roller", conflictsWith: ["o-1"] }];
    expect(dependantsOf("o-1", offers).map((d) => [d.offer.id, d.how])).toEqual([
      ["o-2", "excludes"],
      ["o-4", "conflicts"],
    ]);
  });
});

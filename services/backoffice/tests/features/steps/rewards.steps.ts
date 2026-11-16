// features/backoffice/rewards.feature (T088; US6, FR-005, FR-006, FR-025, FR-026): a player's rewards
// show each restricted funds record's state in the glossary's words, its wagering progress and its open
// round count, and offer only what its state allows; a refused award shows the reason and what would
// allow it; withdrawing an offer names the offers that depend on it first. rewards is the double, with
// the wallet's restricted funds beside it; every answer is in the shape rewards and the wallet give
// (RewardLists.scala, OperatorAwards.scala, Bodies.render, WalletEntity.restrictedFundsStatus).
import type { Locator } from "@playwright/test";
import { t, type MessageKey } from "../../../src/shared/messages/index.ts";
import { formatMoney } from "../../../src/shared/money.ts";
import { offerIn } from "../offered.ts";
import { expect, Given, Then, When, type World } from "../world.ts";

const B = "/v1/casino/brands/{brand}";
const P = "/v1/casino/backoffice/{brand}";

const RewardId = "5c0b7e9a-2d41-4f3e-9b6a-8e1f0c2d3a4b";
const OfferId = "1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d";
const ReloadId = "2b3c4d5e-6f7a-4b8c-9d0e-1f2a3b4c5d6e";
const HighRollerId = "3c4d5e6f-7a8b-4c9d-8e1f-2a3b4c5d6e7f";
const WeekendId = "4d5e6f7a-8b9c-4d0e-9f2a-3b4c5d6e7f8a";

interface Scenario {
  player?: string;
  /** What the record's state is expected to show. */
  shows?: { status: string; open: boolean };
}
const scenarios = new WeakMap<World, Scenario>();
const of = (w: World): Scenario => scenarios.get(w) ?? (scenarios.set(w, {}), scenarios.get(w)!);

const main = (w: World): Locator => w.page.getByRole("main");
const lena = (w: World) => w.brand.players.find((p) => p.currency === "EUR")!.playerId;
const eur = (minor: number) => formatMoney({ minor, currency: "EUR" });

/** An offer as rewards answers it (Bodies.render), the fields the page reads. */
function offer(id: string, name: string, over: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    id,
    name,
    description: "",
    type: "DEPOSIT_MATCH",
    payloadType: "bonus_funds",
    active: true,
    bonusPercentage: 10000,
    maxBonusValue: 10000,
    minDepositAmount: 2000,
    maxConvertibleAmount: null,
    depositNumberTarget: "ANY",
    claimWindowDays: null,
    wageringWindowDays: 30,
    wageringMultiplier: 30,
    maxClaimsPerPlayer: 1,
    maxConcurrentGrants: null,
    excludedOfferIds: [],
    conflictsWith: [],
    redemptionCodes: [],
    noDepositRequired: false,
    currency: "EUR",
    spinConfigurations: [],
    createdAt: "2026-10-01T09:00:00Z",
    updatedAt: "2026-10-01T09:00:00Z",
    ...over,
  };
}

/** A reward of the player's list (RewardLists.ofPlayer). */
function reward(player: string, over: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    rewardId: RewardId,
    playerId: player,
    offerId: OfferId,
    offerName: "Welcome match",
    type: "DEPOSIT_MATCH",
    status: "ACTIVE",
    faceValue: 10000,
    freeSpins: null,
    currency: "EUR",
    wageringRequirement: 300000,
    wageredAmount: null,
    grantedAt: "2026-11-10T12:00:00Z",
    expiresAt: "2026-12-10T12:00:00Z",
    source: "player_code",
    isPrimary: null,
    ...over,
  };
}

/** The wallet's open record of the reward (RecordStatus). */
function record(status: string, releaseProgress: number, openRoundCount: number): Record<string, unknown> {
  return {
    recordId: "rf-1",
    grantRef: RewardId,
    bonusRef: OfferId,
    status,
    priority: 1,
    grantedAt: "2026-11-10T12:00:00Z",
    expiresAt: "2026-12-10T12:00:00Z",
    balance: status === "ACTIVE" ? 6500 : 0,
    wageringRequirement: 300000,
    releaseProgress,
    maxConvertible: 50000,
    remainingAllowance: 50000,
    depositsSinceConversion: 0,
    openRoundCount,
    forfeitOnWithdrawal: "funds_and_winnings",
  };
}

async function given(world: World, rewards: Record<string, unknown>[], records: Record<string, unknown>[], offers: Record<string, unknown>[] = []) {
  await world.doubles.fixtures("rewards", {
    [`GET ${B}/reward-grants`]: { body: { items: rewards, total: rewards.length, page: 1, pageSize: rewards.length, unmatchedRounds: [] } },
    [`GET ${B}/offers`]: { body: offers },
  });
  await world.doubles.fixtures("wallet", {
    [`GET ${P}/wallets/{player}/{currency}/restricted-funds`]: { body: { records, headline: records[0] ?? null, restrictedBalance: 0 } },
  });
}

const card = (w: World) => main(w).locator(`[data-reward="${RewardId}"]`);

// --- a record's state, wagering and open rounds, and only its state's actions ------------------------

/** The feature's state, as rewards answers it, and the wallet's open record when it is still open. */
const States: Record<string, { status: string; record?: [string, number, number] }> = {
  active: { status: "ACTIVE", record: ["ACTIVE", 120000, 2] },
  "awaiting its cap": { status: "CONVERTED_PENDING_CAP", record: ["CONVERTED_PENDING_CAP", 300000, 1] },
  forfeited: { status: "FORFEITED" },
  closed: { status: "CLOSED" },
};

Given("a restricted funds record that is {string}", async ({ world }, state: string) => {
  const s = States[state];
  if (!s) throw new Error(`no such state: ${state}`);
  const player = lena(world);
  of(world).player = player;
  of(world).shows = { status: s.status, open: s.record !== undefined };
  const open = s.record ? record(...s.record) : undefined;
  await given(world, [reward(player, { status: s.status, wageredAmount: open ? open.releaseProgress : null, isPrimary: s.status === "ACTIVE" ? true : null })], open ? [open] : []);
  offerIn(world, {
    region: (w) => card(w).getByRole("region", { name: t("rewards.player.actions") }),
    named: { forfeit: "reward.forfeit", reclaim: "reward.reclaim" },
    none: "rewards.player.noActions",
  });
});

When("an operator opens the player's rewards", async ({ world }) => {
  await world.signInAs("brand_finance", { to: `/players/${of(world).player}/rewards` });
  await expect(main(world).getByRole("heading", { level: 1, name: t("rewards.player.title", { player: of(world).player! }) })).toBeVisible();
});

Then("the record shows its state, its wagering progress and its open round count", async ({ world }) => {
  const { status, open } = of(world).shows!;
  const c = card(world);
  await expect(c.locator('[data-field="state"]')).toContainText(t(`status.${status.toLowerCase()}` as MessageKey));
  const wagering = c.locator('[data-field="wagering"]');
  const rounds = c.locator('[data-field="openRounds"]');
  if (status === "ACTIVE") {
    await expect(wagering).toContainText(t("rewards.wagering.progress", { progress: 40, amount: eur(120000), to: eur(300000) }));
    await expect(rounds).toContainText(t("rewards.openRounds.count", { count: 2 }));
  } else if (open) {
    await expect(wagering).toContainText(t("rewards.wagering.progress", { progress: 100, amount: eur(300000), to: eur(300000) }));
    await expect(rounds).toContainText(t("rewards.openRounds.count", { count: 1 }));
  } else {
    await expect(wagering).toContainText(t("rewards.wagering.closed"));
    await expect(rounds).toContainText(t("rewards.openRounds.closed"));
  }
});

// --- a refused award: its reason and what would allow it ---------------------------------------------
//
// An operator's award is held to the brand's duplicate and open-reward rules only (Evaluator: rules 1 to
// 4, FR-006). The double answers what rewards answers an award to a player flagged as a suspected
// duplicate (rule 3), with what would allow it (AllowedBy.of(SuspectedDuplicate)): the flag cleared, or
// the brand's duplicate detection turned off (research, *Rewards*, the decision on this scenario).

Given("a player flagged as a suspected duplicate", async ({ world }) => {
  const player = lena(world);
  of(world).player = player;
  await given(world, [reward(player, { status: "CLOSED", source: "player_code" })], [], [offer(OfferId, "Welcome match")]);
  await world.doubles.fixtures("rewards", {
    // OperatorAwards.claim: a decline is Answers.ineligible, 409 {status, reason, allowedBy}
    [`POST ${B}/reward-grants/{playerId}`]: {
      status: 409,
      body: { status: "ineligible", reason: "SUSPECTED_DUPLICATE", allowedBy: ["fraud_flag_cleared", "brand_detection_off"] },
    },
  });
});

When("an operator awards the player an offer", async ({ world }) => {
  await world.signInAs("brand_admin", { to: `/players/${of(world).player}/rewards` });
  await main(world).getByRole("button", { name: t("action.reward.grant.label"), exact: true }).click();
  const dialog = world.page.getByRole("dialog");
  await expect(dialog.locator("[data-effect]")).toHaveText(t("action.reward.grant.effect"));
  await dialog.getByRole("radio", { name: t("rewards.grant.fromOffer") }).check();
  await dialog.getByRole("combobox", { name: t("rewards.grant.offer") }).selectOption({ label: "Welcome match" });
  await dialog.getByRole("button", { name: t("action.confirm"), exact: true }).click();
});

const refusal = (w: World) => w.page.getByRole("dialog").getByRole("alert");

Then("the award is refused because the player is a suspected duplicate", async ({ world }) => {
  await expect(refusal(world)).toContainText(t("refusal.suspected_duplicate"));
  const asked = await world.doubles.calls("rewards", { method: "POST", path: `/v1/casino/brands/${world.brand.slug}/reward-grants/${of(world).player}` });
  expect(asked).toHaveLength(1);
  expect(JSON.parse(asked[0]!.body)).toEqual({ offerId: OfferId });
});

Then("the operator is shown that clearing the flag, or the brand turning duplicate detection off, would allow it", async ({ world }) => {
  // what would allow it, as rewards named it
  const ways = [t("refusal.allowedBy.fraud_flag_cleared"), t("refusal.allowedBy.brand_detection_off")].join("; ");
  await expect(refusal(world)).toContainText(t("refusal.allowedBy", { ways }));
});

// --- the offers that depend on one, named before it changes ------------------------------------------

Given("an offer that two other offers exclude", async ({ world }) => {
  await given(world, [], [], [
    offer(OfferId, "Welcome match"),
    offer(ReloadId, "Reload bonus", { excludedOfferIds: [OfferId] }),
    offer(HighRollerId, "High roller", { excludedOfferIds: [OfferId] }),
    offer(WeekendId, "Weekend spins", { type: "FREE_SPINS", payloadType: "free_spins", gameId: "pragmatic:vs20doghouse", spinCount: 20, spinValue: 20 }),
  ]);
  await world.doubles.fixtures("rewards", { [`DELETE ${B}/offers/{offerId}`]: { body: { status: "deactivated" } } });
});

When("an operator withdraws the offer", async ({ world }) => {
  await world.signInAs("brand_admin", { to: "/offers" });
  const row = main(world).locator(`[data-offer="${OfferId}"]`);
  await row.getByRole("button", { name: t("action.offer.deactivate.label"), exact: true }).click();
  await expect(world.page.getByRole("dialog").locator("[data-effect]")).toHaveText(t("action.offer.deactivate.effect"));
});

Then("the operator is shown the two offers before it is withdrawn", async ({ world }) => {
  const named = world.page.getByRole("dialog").locator("[data-subject] [data-dependant]");
  await expect(named).toHaveCount(2);
  expect(await named.allTextContents()).toEqual([t("offers.dependants.excludes", { name: "Reload bonus" }), t("offers.dependants.excludes", { name: "High roller" })]);
  const path = `/v1/casino/brands/${world.brand.slug}/offers/${OfferId}`;
  expect(await world.doubles.calls("rewards", { method: "DELETE", path }), "nothing is withdrawn before it is confirmed").toHaveLength(0);
  await world.page.getByRole("dialog").getByRole("button", { name: t("action.confirm"), exact: true }).click();
  await expect(world.page.getByRole("dialog")).toHaveCount(0);
  expect(await world.doubles.calls("rewards", { method: "DELETE", path })).toHaveLength(1);
});

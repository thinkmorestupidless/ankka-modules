// features/backoffice/investigation.feature (T065; US3, FR-019 to FR-023): an operator finds a player from
// whatever they were given, reads the player's standing at once on a 1440 by 900 screen, and follows one
// timeline of everything that happened to the player, each line with who caused it, a source that
// cannot be read named with its period. The owners are the doubles, answering as their code does
// (players' profile `{player, wallet, activeSessions, pinnedNotes}`, activity by category, game-provider's
// round state with its transactions' movements). The timeline reads newest first, as the original did:
// "in the order it happened" is checked as the lines' times in order and each event in its place.
import type { Locator, Page } from "@playwright/test";
import { t } from "../../../src/shared/messages/index.ts";
import { formatMoney } from "../../../src/shared/money.ts";
import type { Entry } from "../../doubles/owner.ts";
import { expect, Given, Then, When, type World } from "../world.ts";

const P = "/v1/casino/backoffice/{brand}";
const B = "/v1/casino/brands/{brand}";

const PlayerId = "3f6c2a1e-8b4d-4c7a-9e2f-5a1b6c7d8e90";
const Email = "marta.lima@example.test";
const RequestId = "8c2e4f6a-1b3d-4e5f-9a7b-2c4d6e8f0a1b";
const RewardId = "b1d3f5a7-2c4e-4f6a-8b9c-0d1e2f3a4b5c";
const PaymentId = "c4e6a8b0-3d5f-4a7b-9c1d-2e3f4a5b6c7d";
const PaymentReference = "PSP_7781234";
const RoundId = "8a7cf728-9b44-431e-b15a-f9de866aceda";
const TransactionId = "00a872de-4513-460a-abbc-aa5713826811";
const OtherBrandId = "d7e9f1a3-4b5c-4d6e-8f7a-9b0c1d2e3f4a";
const UnsettledRoundId = "ba32dff9-d12f-4081-ace3-5e2e22ad7c93";

interface Scenario {
  /** The timeline's period the scenario reads, when it names one. */
  period?: string;
  /** Each line's source and actor text, oldest first, as the owners were given them. */
  expected?: { source: string; actor: string }[];
  rounds?: Page[];
}
const scenarios = new WeakMap<World, Scenario>();
const of = (w: World): Scenario => scenarios.get(w) ?? (scenarios.set(w, {}), scenarios.get(w)!);

const main = (page: Page): Locator => page.getByRole("main");
const ago = (hours: number) => new Date(Date.now() - hours * 3_600_000).toISOString();
const notFound = (code: string) => ({ status: 404, body: { error: code } });

/** players' profile as OperatorProfileRoutes answers it. */
function profile(over: Record<string, unknown> = {}, extra: Record<string, unknown> = {}) {
  return {
    player: {
      playerId: PlayerId, status: "active", email: Email, emailVerified: true, currency: "EUR", registeredAt: "2026-08-01T10:00:00Z", vipTier: "",
      profileComplete: true, missingProfileFields: [], firstName: "Marta", lastName: "Lima", dateOfBirth: "1992-03-04", phone: "", addressLine1: "", city: "",
      stateProv: "", postalCode: "", country: "PT", lastLoginAt: ago(2), registrationDate: "2026-08-01T10:00:00Z", kycStatus: "verified", potVip: false, isTestAccount: false,
      ...over,
    },
    wallet: { realBalance: "250.00", bonusBalance: "20.00", currency: "EUR" },
    activeSessions: [],
    pinnedNotes: [],
    ...extra,
  };
}

const row = { playerId: PlayerId, twinId: `casino-goldvibe/${PlayerId}`, username: "marta.l", email: Email, accountStatus: "active", country: "PT", currency: "EUR", registeredAt: "2026-08-01T10:00:00Z", vipTier: null, potVip: false, suspectedDuplicate: false };

/** game-provider's RoundState: each transaction with its movements. */
function round(id: string, plays: { tx: string; action: "bet" | "win"; amount: number }[], status: string) {
  return {
    provider: "st8", roundId: id, playerId: PlayerId, currency: "EUR", gameCode: "sweet-bonanza", gameName: "Sweet Bonanza", sessionId: "gs-1",
    transactions: plays.map((p) => ({ transactionId: p.tx, kind: p.action, amount: p.amount, references: {}, movements: [{ action: p.action, walletKey: "k", walletRoundId: id, amount: p.amount, walletTransactionId: `w-${p.tx}`, real: p.amount }], answer: { status: "OK", balance: 0, currency: "EUR" }, receivedAt: ago(5) })),
    conflictingRepeats: [], openBets: status === "settled" ? [] : plays.filter((p) => p.action === "bet").map((p) => ({ walletKey: "k", walletRoundId: id, amount: p.amount, transactionId: p.tx })),
    totalStake: plays.filter((p) => p.action === "bet").reduce((s, p) => s + p.amount, 0), win: plays.filter((p) => p.action === "win").reduce((s, p) => s + p.amount, 0),
    freeRoundsGrant: null, unacknowledged: [], status, openedAt: ago(5), settledAt: status === "settled" ? ago(4) : null, gameType: "slots",
  };
}

/** The reads every player page makes, answered empty unless a scenario says otherwise. */
async function quietPlayer(world: World, players: Record<string, Entry> = {}) {
  await world.doubles.fixtures("players", {
    [`GET ${P}/players/{playerId}/profile`]: { by: "playerId", answers: { [PlayerId]: { body: profile() } }, otherwise: notFound("player_not_found") },
    [`GET ${P}/players/{playerId}/activity`]: { body: { events: [], page: 0, pageSize: 50, hasMore: false, total: 0, next: null } },
    [`GET ${P}/players/{playerId}/notes`]: { body: { notes: [], total: 0 } },
    [`GET ${P}/players/{playerId}/messages`]: { body: { items: [], total: 0, page: 1, pageSize: 100 } },
    ...players,
  });
  await world.doubles.fixtures("kyc", {
    [`GET ${P}/kyc/{playerId}`]: { body: { status: "verified", mode: "automatic", categories: [], other: [], missingProfileFields: [], playerId: PlayerId, readyAt: null, sof: "unverified", requiredOfPlayer: [], deadline: null, depositsByCurrency: {}, flags: [] } },
    [`GET ${P}/kyc/{playerId}/decisions`]: { body: { decisions: [] } },
  });
  await world.doubles.fixtures("responsible-gambling", {
    [`GET ${P}/rg/{player}`]: { body: { limits: [], pendingChanges: [], usedAmounts: [], isCoolingOff: false, activeRestrictions: [], coolingOffUntil: null, realityCheckIntervalMinutes: null, exclusion: null, returnRequest: null, cases: [], history: [] } },
    [`GET ${P}/rg/{player}/history`]: { body: [] },
  });
  await world.doubles.fixtures("wallet", {
    [`GET ${P}/wallets/{player}/{currency}/balance`]: { body: { realBalance: "250.00", bonusBalance: "20.00", currency: "EUR", primary: null, depositPoolBalance: "250.00", convertedPoolBalance: "0.00", reserved: "0.00", available: "250.00" } },
    [`GET ${P}/wallets/{player}/{currency}/restricted-funds`]: { body: { records: [], headline: null, restrictedBalance: 0 } },
    [`GET ${P}/wallets/{player}/{currency}/lifetime-totals`]: { body: { totalDeposited: "1200.00", totalWagered: "5400.00", totalWon: "5100.00" } },
    [`GET ${P}/wallets/{player}/{currency}/transactions`]: { body: { transactions: [], page: 0, pageSize: 200, total: 0 } },
  });
  await world.doubles.fixtures("payouts", {
    [`GET ${B}/players/{playerId}/withdrawal-history`]: { body: { requests: [], total: 0, page: 1, pageSize: 200 } },
  });
  await world.doubles.fixtures("merchant.payments", {
    "GET /v1/operators": { body: { operators: [{ operatorId: "op-fin-1", firstName: "Fiona", lastName: "Finance" }, { operatorId: "op-sup-1", firstName: "Sam", lastName: "Support" }] } },
  });
}

// --- the one search ----------------------------------------------------------------------------------

Given("a player of the brand with a deposit, a withdrawal request, a reward and a settled round", async ({ world }) => {
  await quietPlayer(world, {
    [`GET ${P}/players`]: { by: "?search", answers: { [Email]: { body: { items: [row], total: 1, page: 1, pageSize: 25 } } }, otherwise: { body: { items: [], total: 0, page: 1, pageSize: 25 } } },
  });
  await world.doubles.fixtures("payouts", {
    [`GET ${B}/withdrawal-requests/{requestId}`]: {
      by: "requestId",
      answers: { [RequestId]: { body: { requestId: RequestId, playerId: PlayerId, amount: 5000, currency: "EUR", status: "PENDING_REVIEW", payoutStatus: "NONE", createdAt: ago(3) } } },
      otherwise: notFound("not_found"),
    },
  });
  await world.doubles.fixtures("rewards", {
    [`GET ${B}/reward-grants/{rewardId}`]: { by: "rewardId", answers: { [RewardId]: { body: { rewardId: RewardId, playerId: PlayerId, status: "active", offerName: "Welcome match", kind: "DEPOSIT_MATCH" } } }, otherwise: notFound("not_found") },
  });
  const payment = { paymentId: PaymentId, customerRef: PlayerId, status: "SUCCEEDED", amountCents: 2000, currency: "EUR", psp: "paysafe", reference: PaymentReference };
  await world.doubles.fixtures("merchant.payments", {
    "GET /v1/payments/cashier": { by: "?reference", answers: { [PaymentReference]: { body: { payments: [payment], total: 1 } } }, otherwise: { body: { payments: [], total: 0 } } },
    "GET /v1/payments/{paymentId}": { by: "paymentId", answers: { [PaymentId]: { body: payment } }, otherwise: notFound("PAYMENT_NOT_FOUND") },
  });
  const settled = { body: round(RoundId, [{ tx: TransactionId, action: "bet", amount: 1000 }, { tx: "tx-win-1", action: "win", amount: 2500 }], "settled") };
  await world.doubles.fixtures("game-provider", {
    [`GET ${P}/game-provider/rounds/by-round/{roundId}`]: { by: "roundId", answers: { [RoundId]: settled }, otherwise: notFound("round_not_found") },
    [`GET ${P}/game-provider/rounds/by-transaction/{transactionId}`]: { by: "transactionId", answers: { [TransactionId]: settled }, otherwise: notFound("round_not_found") },
  });
});

const References: Record<string, string> = {
  "player id": PlayerId,
  email: Email,
  "provider round id": RoundId,
  "provider transaction id": TransactionId,
  "payment reference": PaymentReference,
  "withdrawal request id": RequestId,
  "reward id": RewardId,
  "id of another brand": OtherBrandId,
};

When("an operator searches for the player's {string}", async ({ world }, reference: string) => {
  const value = References[reference];
  if (!value) throw new Error(`no such reference: ${reference}`);
  await world.signInAs("brand_support");
  const box = world.page.getByRole("search").getByRole("searchbox", { name: t("frame.search") });
  await box.fill(value);
  await box.press("Enter");
  await expect(world.page).toHaveURL(/\/search\?q=/);
});

const Found: Record<string, { kind: string; id: string } | "nothing"> = {
  "the player": { kind: "player", id: PlayerId },
  "the round": { kind: "round", id: RoundId },
  "the deposit": { kind: "payment", id: PaymentId },
  "the withdrawal request": { kind: "withdrawal", id: RequestId },
  "the reward": { kind: "reward", id: RewardId },
  "nothing in this brand": "nothing",
};

Then("the operator is shown {string}", async ({ world }, found: string) => {
  const f = Found[found];
  if (!f) throw new Error(`no such result: ${found}`);
  const m = main(world.page);
  if (f === "nothing") {
    await expect(m.locator("[data-nothing]")).toHaveText(t("search.nothing", { value: OtherBrandId }));
    await expect(m.locator("[data-match]")).toHaveCount(0);
    return;
  }
  const match = m.locator(`[data-match="${f.kind}"][data-id="${f.id}"]`);
  await expect(match).toBeVisible();
  // every match listed is this one: none other is chosen or added
  await expect(m.locator("[data-match]")).toHaveCount(1);
  if (f.kind !== "player") await expect(match.getByRole("link", { name: PlayerId })).toBeVisible();
});

// --- the standing at once ------------------------------------------------------------------------------

Given("a player with a limit, restricted funds, a pinned note and an open session", async ({ world }) => {
  await quietPlayer(world, {
    [`GET ${P}/players/{playerId}/profile`]: {
      by: "playerId",
      answers: {
        [PlayerId]: {
          body: profile(
            { suspectedDuplicate: true, linkedPlayerIds: ["p-other"], duplicateSignals: ["device"], duplicateDetectedAt: ago(48) },
            { activeSessions: [{ sessionId: "s-open", gameName: "Sweet Bonanza", provider: "", startedAt: ago(1) }], pinnedNotes: [{ noteId: "n-pin", text: "Prefers calls after 18:00", author: "op-sup-1", createdAt: ago(72) }] },
          ),
        },
      },
      otherwise: notFound("player_not_found"),
    },
  });
  await world.doubles.fixtures("responsible-gambling", {
    [`GET ${P}/rg/{player}`]: { body: { limits: [{ limitType: "DEPOSIT_DAILY", value: 10000, currency: "EUR" }], pendingChanges: [], usedAmounts: [], isCoolingOff: false, activeRestrictions: [], coolingOffUntil: null, realityCheckIntervalMinutes: null, exclusion: null, returnRequest: null, cases: [], history: [] } },
  });
  await world.doubles.fixtures("wallet", {
    [`GET ${P}/wallets/{player}/{currency}/restricted-funds`]: { body: { records: [{ recordId: "rf-1", status: "active", balance: 3500 }], headline: null, restrictedBalance: 3500 } },
  });
});

When("an operator opens the player", async ({ world }) => {
  await world.page.setViewportSize({ width: 1440, height: 900 });
  await world.signInAs("brand_support", { to: `/players/${PlayerId}` });
  await expect(main(world.page).locator('[data-part="presence"]')).toBeVisible();
});

const eur = (minor: number) => formatMoney({ minor, currency: "EUR" });

Then("the operator sees the player's identity, player status, identity status, limits, balances, restricted funds, lifetime figures, pinned note, flags and presence at once", async ({ world }) => {
  const m = main(world.page);
  const part = (id: string) => m.locator(`[data-part="${id}"]`);
  const shown: [Locator, string][] = [
    [part("identity"), "Marta Lima"],
    [part("account"), t("status.active")],
    [part("identity-status"), t("status.verified")],
    [part("protection").locator('[data-limit="DEPOSIT_DAILY"]'), eur(10000)],
    [part("balances"), eur(25000)],
    [part("balances").locator("[data-restricted]"), eur(3500)],
    [part("lifetime"), eur(120000)],
    [part("notes"), "Prefers calls after 18:00"],
    [part("flags").locator('[data-flag="suspected_duplicate"]'), t("players.flags.duplicateShort")],
    [part("presence"), t("status.online")],
  ];
  for (const [where, text] of shown) {
    await expect(where).toContainText(text);
    // at once: inside the first screen, nothing scrolled
    await expect(where).toBeInViewport({ ratio: 1 });
  }
  expect(await world.page.evaluate(() => window.scrollY)).toBe(0);
});

// --- the timeline ----------------------------------------------------------------------------------------

Given("a player with a deposit, a round, a reward, an adjustment, a limit, a note and a withdrawal request", async ({ world }) => {
  const event = (id: string, category: string, eventType: string, hours: number, actor: string, amount: number | null) => ({
    eventId: id, category, eventType, timestamp: ago(hours), actor, amountMinorUnits: amount, currency: amount === null ? null : "EUR", description: eventType, expandable: true,
  });
  const page = (events: unknown[]) => ({ body: { events, page: 0, pageSize: 50, hasMore: false, total: events.length, next: null } });
  await quietPlayer(world, {
    [`GET ${P}/players/{playerId}/activity`]: {
      by: "?category",
      answers: {
        transactions: page([event("e-dep", "Transactions", "deposit", 70, "player", 5000), event("e-bet", "Transactions", "bet", 60, "player", 1000)]),
        games: page([event("e-round", "Games", "round_settled", 60, "player", 1500)]),
        bonus: page([event("e-reward", "Bonus", "bonus_granted", 50, "system", 2000)]),
        account: page([]),
      },
      otherwise: page([]),
    },
    [`GET ${P}/players/{playerId}/notes`]: { body: { notes: [{ noteId: "n-1", text: "Asked about the limit", author: "op-sup-1", pinned: false, createdAt: ago(20), updatedAt: ago(20) }], total: 1 } },
  });
  await world.doubles.fixtures("wallet", {
    [`GET ${P}/wallets/{player}/{currency}/transactions`]: {
      body: { transactions: [{ transactionId: "t-adj", type: "admin_adjust", walletType: "real", amount: "15.00", createdAt: ago(40), reason: "Failed deposit", category: "compensation", cause: { kind: "payment", ref: PaymentId }, actorId: "op-fin-1" }], page: 0, pageSize: 200, total: 1 },
    },
  });
  await world.doubles.fixtures("responsible-gambling", {
    [`GET ${P}/rg/{player}/history`]: { body: [{ eventType: "LIMIT_SET", actor: "player", occurredAt: ago(30), description: "Daily deposit limit set", limitType: "DEPOSIT_DAILY", previousValue: null, newValue: "10000" }] },
  });
  await world.doubles.fixtures("payouts", {
    [`GET ${B}/players/{playerId}/withdrawal-history`]: { body: { requests: [{ requestId: RequestId, playerId: PlayerId, kycStatus: "verified", amount: 5000, currency: "EUR", terminalName: "SEPA out", method: "bank_transfer", destinationLastFour: "7710", createdAt: ago(10), status: "PENDING_REVIEW", payoutStatus: "NONE" }], total: 1, page: 1, pageSize: 200 } },
  });
  of(world).expected = [
    { source: "payments", actor: t("timeline.actor.player") },
    { source: "rounds", actor: t("timeline.actor.player") },
    { source: "rewards", actor: t("timeline.actor.system") },
    { source: "adjustments", actor: t("timeline.actor.operatorNamed", { operator: "Fiona Finance" }) },
    { source: "protection", actor: t("timeline.actor.player") },
    { source: "notes", actor: t("timeline.actor.operatorNamed", { operator: "Sam Support" }) },
    { source: "withdrawals", actor: t("timeline.actor.player") },
  ];
});

Given("a player whose rounds cannot be read for the last day", async ({ world }) => {
  await quietPlayer(world, {
    [`GET ${P}/players/{playerId}/activity`]: {
      by: "?category",
      answers: { games: { status: 503, body: { error: "service_unavailable" } } },
      otherwise: { body: { events: [], page: 0, pageSize: 50, hasMore: false, total: 0, next: null } },
    },
  });
  of(world).period = "1d";
});

const lines = (w: World) => main(w.page).locator("[data-line]");

When("an operator reads the player's timeline", async ({ world }) => {
  const period = of(world).period;
  await world.signInAs("brand_support", { to: `/players/${PlayerId}/timeline${period ? `?period=${period}` : ""}` });
  await expect(main(world.page).getByRole("heading", { name: t("players.tab.timeline"), level: 2 })).toBeVisible();
  await expect(main(world.page).getByRole("status").filter({ hasText: t("list.loading") })).toHaveCount(0);
});

Then("every one of them is shown in the order it happened", async ({ world }) => {
  const expected = of(world).expected!;
  await expect(lines(world)).toHaveCount(expected.length);
  const shown = await lines(world).evaluateAll((ls) => ls.map((l) => ({ source: l.getAttribute("data-kind"), at: l.getAttribute("data-at") })));
  // newest first: the reverse of the order they happened in, each in its place
  expect(shown.map((s) => s.source)).toEqual(expected.map((e) => e.source).reverse());
  const times = shown.map((s) => Date.parse(s.at!));
  expect(times).toEqual([...times].sort((a, b) => b - a));
  await expect(main(world.page).locator("[data-complete]")).toBeVisible();
});

Then("each shows whether the player, an operator by name, the system or a provider caused it", async ({ world }) => {
  const expected = [...of(world).expected!].reverse();
  for (const [i, e] of expected.entries()) await expect(lines(world).nth(i).locator("[data-actor-text]")).toHaveText(e.actor);
});

Then("the timeline says rounds are missing for the last day", async ({ world }) => {
  const gap = main(world.page).locator('[data-gap="rounds"]');
  await expect(gap).toContainText(t("timeline.gap", { source: t("timeline.source.rounds") }));
  const [from, to] = await gap.locator("time").evaluateAll((ts) => ts.map((x) => Date.parse(x.getAttribute("datetime") ?? "")));
  const now = Date.now();
  expect(Math.abs(now - 86_400_000 - from!), "the gap starts a day ago").toBeLessThan(10 * 60_000);
  expect(Math.abs(now - to!), "the gap runs to now").toBeLessThan(10 * 60_000);
  await expect(main(world.page).locator("[data-gap]")).toHaveCount(1);
});

Then("the timeline is not presented as complete", async ({ world }) => {
  await expect(main(world.page).getByRole("alert").filter({ hasText: t("timeline.incomplete") })).toBeVisible();
  await expect(main(world.page).locator("[data-complete]")).toHaveCount(0);
});

// --- a round's bets and wins -------------------------------------------------------------------------------

Given("a round with two bets and one win, and a round with a bet and no settlement", async ({ world }) => {
  await quietPlayer(world);
  const settled = round(RoundId, [{ tx: "tx-bet-1", action: "bet", amount: 1000 }, { tx: "tx-bet-2", action: "bet", amount: 500 }, { tx: "tx-win-1", action: "win", amount: 4000 }], "settled");
  const open = round(UnsettledRoundId, [{ tx: "tx-bet-3", action: "bet", amount: 700 }], "open");
  await world.doubles.fixtures("game-provider", {
    [`GET ${P}/game-provider/rounds`]: { body: { rounds: [settled, open].map(({ transactions: _t, openBets: _o, conflictingRepeats: _c, unacknowledged: _u, ...r }) => r), total: 2, page: 1, pageSize: 50 } },
    [`GET ${P}/game-provider/rounds/by-round/{roundId}`]: { by: "roundId", answers: { [RoundId]: { body: settled }, [UnsettledRoundId]: { body: open } }, otherwise: notFound("round_not_found") },
  });
});

When("an operator opens each round", async ({ world }) => {
  await world.signInAs("brand_support", { to: `/players/${PlayerId}/games?round=${RoundId}` });
  const second = await world.page.context().newPage();
  await second.goto(`${world.url}/players/${PlayerId}/games?round=${UnsettledRoundId}`);
  of(world).rounds = [world.page, second];
  for (const [i, id] of [RoundId, UnsettledRoundId].entries()) await expect(main(of(world).rounds![i]!).locator(`[data-round="${id}"]`)).toBeVisible();
});

Then("the first shows its bets, its win, the total staked, the total won and the net", async ({ world }) => {
  const r = main(of(world).rounds![0]!).locator(`[data-round="${RoundId}"]`);
  await expect(r.locator('[data-play="bet"]')).toHaveCount(2);
  await expect(r.locator('[data-play="win"]')).toHaveCount(1);
  await expect(r.locator('[data-total="staked"]')).toContainText(eur(1500));
  await expect(r.locator('[data-total="won"]')).toContainText(eur(4000));
  await expect(r.locator('[data-total="net"]')).toContainText(eur(2500));
  await expect(r).toHaveAttribute("data-settled", "true");
  await expect(r.locator("[data-unsettled]")).toHaveCount(0);
});

Then("the second is marked unsettled", async ({ world }) => {
  const r = main(of(world).rounds![1]!).locator(`[data-round="${UnsettledRoundId}"]`);
  await expect(r).toHaveAttribute("data-settled", "false");
  await expect(r.getByRole("heading")).toContainText(t("status.unsettled"));
  await expect(r.locator("[data-unsettled]")).toHaveText(t("rounds.unsettled"));
});

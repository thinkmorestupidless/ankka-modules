// features/backoffice/corrections.feature (T083): an adjustment shows the balance before and after and
// records its reason, cause and operator; a debit past the balance is refused, not reduced; an adjustment
// batch lists each row as made, refused or already made, and a repeat makes none twice. The wallet
// decides each adjustment (its double keeps the balances and the keys, `tests/doubles/wallet.ts`); the
// wallet's own features hold its rules. Here the backoffice is what is shown and asked.
import { randomUUID } from "node:crypto";
import { t } from "../../../src/shared/messages/index.ts";
import { formatMoney, parseMoney } from "../../../src/shared/money.ts";
import { DoublesUrl } from "../../doubles/env.ts";
import { expect, Given, Then, When, type World } from "../world.ts";

interface Kept {
  player?: string;
  round?: string;
  reason?: string;
  start?: { minor: bigint; currency: string };
  players?: string[];
  /** The batch's one debit row: its player's real balance is less than the debit. */
  short?: string;
}
const kept = new WeakMap<World, Kept>();
const state = (w: World) => kept.get(w) ?? (kept.set(w, {}), kept.get(w)!);

const main = (w: World) => w.page.getByRole("main");

/** "20.00 EUR" as minor units and its currency. */
function amount(text: string): { minor: bigint; currency: string } {
  const [figure = "", currency = ""] = text.trim().split(/\s+/);
  const minor = parseMoney(figure, currency);
  if (minor === undefined) throw new Error(`${text} is not an amount`);
  return { minor, currency };
}

/** What the wallet double holds for a wallet now, read as the wallet answers its operators. */
async function walletRead(w: World, player: string, currency: string, what: "balance" | "transactions") {
  const r = await fetch(`${DoublesUrl}/wallet/v1/casino/backoffice/${w.brand.slug}/wallets/${player}/${currency}/${what}`, {
    headers: { Authorization: "Bearer e30.e30.x" },
  });
  if (!r.ok) throw new Error(`the wallet double answered ${r.status} for ${player}'s ${what}`);
  return (await r.json()) as Record<string, unknown> & { transactions?: Record<string, unknown>[] };
}

/** A player of the brand the scenario makes: a profile at players and a wallet at the wallet. */
async function aPlayer(w: World, balance: string, opts: { round?: string } = {}) {
  const player = randomUUID();
  const start = amount(balance);
  Object.assign(state(w), { player, start });
  await w.doubles.fixtures("players", {
    "GET /v1/casino/backoffice/{brand}/players/{playerId}/profile": {
      by: "playerId",
      answers: { [player]: { body: { playerId: player, status: "active", email: "rui@example.test", emailVerified: true, currency: start.currency, registeredAt: "2026-10-01T10:00:00Z", vipTier: null, profileComplete: true, missingProfileFields: [], firstName: "Rui", lastName: "Costa", dateOfBirth: "1988-02-03", country: "PT" } } },
    },
  });
  await w.doubles.wallets({ wallets: [{ player, currency: start.currency, real: Number(start.minor) }] });
  const rounds = opts.round
    ? [{ provider: "st8", roundId: opts.round, playerId: player, sessionId: "s-1", gameCode: "sweet_bonanza", currency: start.currency, totalStake: 500, win: 0, outcome: "", status: "open", openedAt: "2026-11-15T21:04:00Z", settledAt: null }]
    : [];
  await w.doubles.fixtures("game-provider", {
    "GET /v1/casino/backoffice/{brand}/game-provider/rounds": { body: { rounds, total: rounds.length, page: 1, pageSize: 20 } },
  });
}

/** The single adjustment, filled in and confirmed, as a finance operator. */
async function adjust(w: World, direction: "credit" | "debit", text: string, opts: { category?: string; cause?: "round" } = {}) {
  const s = state(w);
  await w.signInAs("brand_finance", { to: `/players/${s.player}/wallets` });
  const m = main(w);
  await expect(m.getByRole("heading", { name: t("corrections.adjust.title") })).toBeVisible();
  await expect(m.locator('[data-balance="real"]')).toContainText(formatMoney(s.start!));
  await m.getByRole("radio", { name: t(direction === "credit" ? "corrections.direction.credit" : "corrections.direction.debit") }).check();
  await m.getByRole("radio", { name: t("corrections.target.real") }).check();
  await m.getByLabel(t("corrections.amount")).fill(text.trim().split(/\s+/)[0]!);
  if (opts.category) await m.getByLabel(t("corrections.category"), { exact: true }).selectOption({ label: t(`corrections.category.${opts.category}` as never) });
  if (opts.cause === "round") {
    await m.getByLabel(t("corrections.cause.kind")).selectOption({ label: t("corrections.cause.round") });
    await m.getByLabel(t("corrections.cause.rounds")).selectOption(s.round!);
    await expect(m.getByLabel(t("corrections.cause.ref"))).toHaveValue(s.round!);
  }
  await m.getByRole("button", { name: t("action.wallet.adjust.label") }).click();
  const dialog = w.page.getByRole("dialog");
  await expect(dialog).toContainText(t("action.wallet.adjust.effect"));
  s.reason = "Round left open by the provider's outage on 15 November";
  await dialog.getByLabel(t("action.reason")).fill(s.reason);
  await dialog.getByRole("button", { name: t("action.confirm") }).click();
}

// --- one adjustment ---------------------------------------------------------------------------------

Given("a player with a real balance of {string} and an unsettled round from a provider's failure", async ({ world }, balance: string) => {
  state(world).round = randomUUID();
  await aPlayer(world, balance, { round: state(world).round });
});

When("a finance operator credits {string} with the reason {string} and the round as its cause", async ({ world }, text: string, category: string) => {
  await adjust(world, "credit", text, { category, cause: "round" });
});

Then("the operator was shown {string} before and {string} after", async ({ world }, before: string, after: string) => {
  const made = main(world).locator("[data-adjusted]");
  await expect(made).toContainText(formatMoney(amount(before)));
  await expect(made).toContainText(formatMoney(amount(after)));
  await expect(main(world).locator('[data-balance="real"]')).toContainText(formatMoney(amount(after)));
});

Then("the adjustment records the reason, the round and the operator", async ({ world }) => {
  const s = state(world);
  const rows = (await walletRead(world, s.player!, s.start!.currency, "transactions")).transactions ?? [];
  expect(rows).toHaveLength(1);
  expect(rows[0]).toMatchObject({ type: "admin_adjust", reason: s.reason, category: "compensation", cause: { kind: "round", ref: s.round }, actorId: world.operator!.sub });
  await world.doubles.expectAuthorizedAs("wallet", world.operator!.sub, { method: "POST", path: /\/adjustments$/ });
  // and the page lists it with them
  const listed = main(world).locator(`[data-adjustment="${String(rows[0]!.transactionId)}"]`);
  await expect(listed).toContainText(s.reason!);
  await expect(listed).toContainText(s.round!);
  await expect(listed).toContainText(world.operator!.sub);
});

// --- a debit past the balance -----------------------------------------------------------------------

Given("a player with a real balance of {string}", async ({ world }, balance: string) => {
  await aPlayer(world, balance);
});

When("a finance operator debits {string}", async ({ world }, text: string) => {
  await adjust(world, "debit", text, { category: "error_correction" });
});

Then("the adjustment is refused because it exceeds the balance", async ({ world }) => {
  const refusal = world.page.getByRole("dialog").getByRole("alert");
  await expect(refusal).toContainText(t("refusal.debit_exceeds_balance"));
  await expect(refusal).toContainText(t("refusal.debit_exceeds_balance.next"));
});

Then("the balance is still {string}", async ({ world }, balance: string) => {
  const s = state(world);
  const { minor, currency } = amount(balance);
  expect((await walletRead(world, s.player!, currency, "balance")).realBalance).toBe((Number(minor) / 100).toFixed(2));
  await world.page.getByRole("dialog").getByRole("button", { name: t("action.cancel") }).click();
  await expect(main(world).locator('[data-balance="real"]')).toContainText(formatMoney({ minor, currency }));
  await expect(main(world).locator("[data-adjusted]")).toHaveCount(0);
});

// --- an adjustment batch ----------------------------------------------------------------------------

// The four others are credited 5.00 EUR; the short player is debited 5.00 EUR with 3.00 EUR, which
// the wallet refuses (DEBIT_EXCEEDS_BALANCE), as it would any debit past the balance.
const shortBalance = 300;
Given("an incident affecting five players, one of them debited more than their real balance", async ({ world }) => {
  const players = Array.from({ length: 5 }, () => randomUUID());
  const short = players[2]!;
  Object.assign(state(world), { players, short });
  await world.doubles.wallets({
    wallets: players.map((player, i) => ({ player, currency: "EUR", real: player === short ? shortBalance : 1000 * (i + 1) })),
  });
});

async function runBatch(w: World) {
  const m = main(w);
  await m.getByRole("button", { name: t("action.wallet.adjustBatch.label") }).click();
  const dialog = w.page.getByRole("dialog");
  await expect(dialog).toContainText(t("action.wallet.adjustBatch.effect"));
  await dialog.getByRole("button", { name: t("action.confirm") }).click();
  await expect(m.locator("[data-batch-done]")).toBeVisible({ timeout: 15_000 });
}

When("a finance operator makes an adjustment batch for the five with one reason and cause", async ({ world }) => {
  const s = state(world);
  await world.signInAs("brand_finance", { to: "/adjustments/batch" });
  const m = main(world);
  await expect(m.getByRole("heading", { name: t("corrections.batch.title") })).toBeVisible();
  await m.getByLabel(t("corrections.category"), { exact: true }).selectOption({ label: t("corrections.category.compensation") });
  await m.getByLabel(t("corrections.batch.reason")).fill("Free spins lost in the 15 November outage");
  await m.getByLabel(t("corrections.cause.kind")).selectOption({ label: t("corrections.cause.incident") });
  await m.getByLabel(t("corrections.cause.ref")).fill("INC-2026-1115");
  await m.getByLabel(t("corrections.batch.rows")).fill(s.players!.map((p) => `${p},EUR,${p === s.short ? "-5.00" : "5.00"}`).join("\n"));
  await m.getByRole("button", { name: t("corrections.batch.validate") }).click();
  await expect(m.locator("[data-batch-row]")).toHaveCount(5);
  await runBatch(world);
});

const outcomes = (w: World, outcome: string) => main(w).locator(`[data-batch-result] [data-outcome="${outcome}"]`);

Then("four are listed as made and one as refused with its reason", async ({ world }) => {
  await expect(outcomes(world, "made")).toHaveCount(4);
  await expect(outcomes(world, "refused")).toHaveCount(1);
  const refused = main(world).locator(`[data-batch-result] [data-player="${state(world).short}"]`);
  await expect(refused).toHaveAttribute("data-outcome", "refused");
  await expect(refused).toContainText(t("refusal.debit_exceeds_balance"));
  // a made row says the balance before and after
  const first = main(world).locator(`[data-batch-result] [data-player="${state(world).players![0]}"]`);
  await expect(first).toContainText(formatMoney({ minor: 1000n, currency: "EUR" }));
  await expect(first).toContainText(formatMoney({ minor: 1500n, currency: "EUR" }));
});

When("the operator repeats the adjustment batch", async ({ world }) => {
  await runBatch(world);
});

Then("four are listed as already made and none is made twice", async ({ world }) => {
  const s = state(world);
  await expect(outcomes(world, "already_made")).toHaveCount(4);
  await expect(outcomes(world, "made")).toHaveCount(0);
  for (const [i, player] of s.players!.entries()) {
    const rows = (await walletRead(world, player, "EUR", "transactions")).transactions ?? [];
    expect(rows, player).toHaveLength(player === s.short ? 0 : 1);
    expect((await walletRead(world, player, "EUR", "balance")).realBalance).toBe(player === s.short ? "3.00" : `${10 * (i + 1) + 5}.00`);
  }
});

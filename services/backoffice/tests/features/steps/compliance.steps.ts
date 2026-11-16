// features/backoffice/compliance.feature (T093): a compliance operator works the identity queue in
// arrival order and keeps their place; an identity document reaches only an operator the brand admits;
// a player's raise of a limit shows what applies now and when the pending change applies; a fraud case
// dismissed clears the flags it set. kyc, responsible-gambling and players decide each rule; their own
// features hold them. Here the backoffice is what is shown and asked.
import { t } from "../../../src/shared/messages/index.ts";
import { formatMoney, parseMoney } from "../../../src/shared/money.ts";
import { expect, Given, Then, When, type World } from "../world.ts";

/** What a scenario keeps between its steps. */
const kept = new WeakMap<World, { order?: string[]; document?: { player: string; id: string }; answer?: { status: number; body: string }; player?: string; limit?: { minor: bigint; currency: string }; raised?: { minor: bigint; currency: string }; appliesAt?: string; fraudCase?: string; flagged?: string[]; note?: string }>();
const state = (w: World) => kept.get(w) ?? (kept.set(w, {}), kept.get(w)!);

const main = (w: World) => w.page.getByRole("main");

/** "100.00 EUR" as minor units and its currency. */
function amount(text: string): { minor: bigint; currency: string } {
  const [figure = "", currency = ""] = text.trim().split(/\s+/);
  const minor = parseMoney(figure, currency);
  if (minor === undefined) throw new Error(`${text} is not an amount`);
  return { minor, currency };
}

// --- the identity queue -----------------------------------------------------------------------------

Given("an identity queue of three players whose documents arrived on three days", async ({ world }) => {
  // kyc lists the queue oldest first by the oldest document awaiting review (KycQueue.awaiting)
  const [ana, kenji, fahad] = world.brand.players;
  const arrived: [string, string][] = [
    [fahad!.playerId, "2026-11-10T09:15:00Z"],
    [ana!.playerId, "2026-11-11T14:02:00Z"],
    [kenji!.playerId, "2026-11-12T08:40:00Z"],
  ];
  state(world).order = arrived.map(([p]) => p);
  const row = ([playerId, at]: [string, string]) => ({
    playerId, status: "pending", mode: "automatic", awaiting: 1, oldestAwaitingAt: at, readyAt: null, flags: [], flaggedAt: null,
    sof: "unverified", depositsByCurrency: {}, updatedAt: at, sinceSeconds: 3600,
  });
  const read = (playerId: string, at: string) => ({
    body: {
      playerId, status: "pending", mode: "automatic", readyAt: null, sof: "unverified", requiredOfPlayer: [], deadline: null, depositsByCurrency: {}, flags: [], missingProfileFields: [], other: [],
      categories: [
        { category: "identity_front", requiredByPolicy: true, requiredOfPlayer: false, document: { documentId: `d-${playerId}`, category: "identity_front", status: "pending", uploadedAt: at, reviewedAt: null, reasonCode: null, submittedBy: "player", reviewedBy: null } },
        { category: "proof_of_address", requiredByPolicy: true, requiredOfPlayer: false, document: null },
      ],
    },
  });
  await world.doubles.fixtures("kyc", {
    "GET /v1/casino/backoffice/{brand}/kyc/queue": { body: { rows: arrived.map(row), counts: { awaiting: 3, ready: 0, unverified: 0, pending: 3, verified: 0, rejected: 0, threshold: 0, sourceOfFunds: 0, window: 0, largeDeposit: 0 } } },
    "GET /v1/casino/brands/{brand}/kyc/status": { body: { views: [{ name: "kyc-queue", asOf: "2026-11-12T08:40:01Z", rebuilding: false }] } },
    "GET /v1/casino/backoffice/{brand}/kyc/{playerId}": { by: "playerId", answers: Object.fromEntries(arrived.map(([p, at]) => [p, read(p, at)])) },
  });
});

When("a compliance operator opens the queue, then the second player, then returns", async ({ world }) => {
  const order = state(world).order!;
  await world.signInAs("brand_compliance", { to: "/identity" });
  const rows = main(world).locator("tbody tr[data-row]");
  await expect(rows).toHaveCount(3);
  await main(world).locator(`tr[data-row="${order[1]}"]`).click();
  await expect(world.page).toHaveURL(new RegExp(`/players/${order[1]}/identity`));
  await expect(main(world).getByRole("heading", { name: t("identity.player.title") })).toBeVisible();
  await world.page.goBack();
  await expect(world.page).toHaveURL(/\/identity(\?|$)/);
});

Then("the queue is in the order the documents arrived", async ({ world }) => {
  const rows = main(world).locator("tbody tr[data-row]");
  await expect(rows).toHaveCount(3);
  expect(await rows.evaluateAll((trs) => trs.map((tr) => tr.getAttribute("data-row")))).toEqual(state(world).order);
  // kyc was asked for its own order, oldest first, and nothing re-sorted it
  const asked = await world.doubles.calls("kyc", { method: "GET", path: `/v1/casino/backoffice/${world.brand.slug}/kyc/queue` });
  expect(asked.length).toBeGreaterThan(0);
  for (const c of asked) expect(new URLSearchParams(c.query).get("sort")).not.toBe("newest");
});

Then("the operator is back at the second player", async ({ world }) => {
  const second = main(world).locator(`tr[data-row="${state(world).order![1]}"]`);
  await expect(second).toHaveAttribute("aria-current", "true");
  await expect(second).toBeFocused();
});

// --- an identity document ---------------------------------------------------------------------------

Given("an identity document of a player", async ({ world }) => {
  const player = world.brand.players[0]!.playerId;
  const id = "0b6f1f9e-7a43-4c55-b0c1-2f9d6a1e3c77";
  state(world).document = { player, id };
  // kyc answers the document's bytes itself to an operator it admits (amendment K5)
  await world.doubles.fixtures("kyc", {
    "GET /v1/casino/backoffice/{brand}/kyc/{playerId}/views/{documentId}": {
      body: "%PDF-1.4 the player's identity document",
      headers: { "Content-Type": "application/pdf", "Content-Disposition": "inline", "Cache-Control": "no-store" },
    },
  });
});

When("a support operator of another brand asks to view it", async ({ world }) => {
  const { player, id } = state(world).document!;
  await world.signInAs("brand-less");
  const r = await world.page.request.get(`${world.url}/api/kyc/${player}/documents/${id}/view`);
  state(world).answer = { status: r.status(), body: await r.text() };
});

Then("the document is not shown", async ({ world }) => {
  const answer = state(world).answer!;
  expect(answer.status).toBe(403);
  expect(answer.body).not.toContain("%PDF");
  expect(JSON.parse(answer.body)).toMatchObject({ error: "brand_not_in_roles" });
  expect(await world.doubles.calls("kyc", { path: /\/views\// }), "kyc was not asked for a view").toHaveLength(0);
});

// --- a player's raise, pending ---------------------------------------------------------------------

/**
 * responsible-gambling's operator read of a player (`Render.full`) whose daily deposit limit of `limit`
 * they asked to raise to `raised`: the limit in force under `limits` (minor units), the raise under
 * `pendingChanges` (`pendingValue` in minor units as text, `appliesAt` 24 hours after the ask), the
 * usage of every money limit in the currency (major units), and the history newest first.
 */
function pendingRaise(limit: { minor: bigint; currency: string }, raised: { minor: bigint }, askedAt: string, appliesAt: string, setAt: string) {
  const c = limit.currency;
  const used = ["DEPOSIT_DAILY", "DEPOSIT_WEEKLY", "DEPOSIT_MONTHLY", "LOSS_DAILY", "LOSS_WEEKLY", "LOSS_MONTHLY"].map((limitType) => ({ limitType, currency: c, usedAmount: 0 }));
  return {
    limits: [{ limitType: "DEPOSIT_DAILY", value: Number(limit.minor), currency: c }],
    pendingChanges: [{ limitType: "DEPOSIT_DAILY", appliesAt, currency: c, pendingValue: String(raised.minor) }],
    usedAmounts: used,
    isCoolingOff: false,
    activeRestrictions: [],
    coolingOffUntil: null,
    realityCheckIntervalMinutes: null,
    exclusion: null,
    returnRequest: null,
    cases: [],
    history: [
      { eventType: "deposit_limit_change_requested", actor: "player", occurredAt: askedAt, description: `DEPOSIT_DAILY (${c}) change requested: ${raised.minor}`, limitType: "DEPOSIT_DAILY", previousValue: String(limit.minor), newValue: String(raised.minor) },
      { eventType: "deposit_limit_set", actor: "player", occurredAt: setAt, description: `DEPOSIT_DAILY (${c}) set: ${limit.minor}`, limitType: "DEPOSIT_DAILY", previousValue: null, newValue: String(limit.minor) },
    ],
  };
}

/** An instant as responsible-gambling writes one (`Instant.toString`, whole seconds here). */
const instant = (ms: number) => new Date(Math.floor(ms / 1000) * 1000).toISOString().replace(".000Z", "Z");

Given("a player with a daily deposit limit of {string} who has asked to raise it to {string}", async ({ world }, limit: string, raised: string) => {
  const a = amount(limit);
  const to = amount(raised);
  const player = world.brand.players.find((p) => p.currency === a.currency)!.playerId;
  const now = Date.now();
  // responsible-gambling applies a player's raise 24 hours after they ask (the pending change)
  const appliesAt = instant(now + 23 * 3_600_000);
  Object.assign(state(world), { player, limit: a, raised: to, appliesAt });
  await world.doubles.fixtures("responsible-gambling", {
    "GET /v1/casino/backoffice/{brand}/rg/settings": { body: { minSessionMinutes: 60, maxBreakDays: 42 } },
    "GET /v1/casino/brands/{brand}/rg/status": { body: { views: [] } },
    "GET /v1/casino/backoffice/{brand}/rg/{player}": { body: pendingRaise(a, to, instant(now - 3_600_000), appliesAt, instant(now - 30 * 86_400_000)) },
  });
});

When("a compliance operator opens the player's protection", async ({ world }) => {
  await world.signInAs("brand_compliance", { to: `/players/${state(world).player}/protection` });
});

Then("the operator is shown the limit is still {string} and {string} as a pending change with the time it applies", async ({ world }, still: string, raised: string) => {
  const s = state(world);
  const a = amount(still);
  const to = amount(raised);
  const row = main(world).locator(`tr[data-limit="DEPOSIT_DAILY/${a.currency}"]`);
  await expect(row.locator("[data-current]")).toHaveText(formatMoney(a));
  const pending = row.locator("[data-pending]");
  await expect(pending).toBeVisible();
  await expect(pending).toContainText(formatMoney(to));
  await expect(pending).toHaveAttribute("data-applies-at", s.appliesAt!);
  await expect(pending.locator("time")).toHaveAttribute("datetime", new Date(s.appliesAt!).toISOString());
  // nothing was changed to show it
  expect(await world.doubles.calls("responsible-gambling", { method: "PUT" })).toHaveLength(0);
});

// --- a fraud case dismissed -------------------------------------------------------------------------

Given("a fraud case that flagged two players as suspected duplicates", async ({ world }) => {
  const [ana, kenji] = world.brand.players;
  const caseId = "5d2c9a7e-1f4b-4e0a-9b8c-3a6d2e1f0c44";
  Object.assign(state(world), { fraudCase: caseId, flagged: [ana!.playerId, kenji!.playerId] });
  await world.doubles.fraudCases({
    cases: [
      { caseId, playerIds: [ana!.playerId, kenji!.playerId], signals: ["device", "address"], status: "OPEN", detectedAt: "2026-11-14T10:00:00Z", reviewedBy: null, reviewedAt: null, reviewNotes: null },
    ],
  });
  await world.doubles.fixtures("players", { "GET /v1/casino/brands/{brand}/players/status": { body: { views: [] } } });
});

When("a compliance operator dismisses it with a note", async ({ world }) => {
  const s = state(world);
  s.note = "same household, two people; documents checked";
  await world.signInAs("brand_compliance", { to: "/fraud" });
  await main(world).locator(`tr[data-row="${s.fraudCase}"]`).click();
  const detail = main(world).locator("[data-fraud-case]");
  for (const p of s.flagged!) await expect(detail.locator(`[data-player="${p}"]`)).toContainText(t("fraud.flagged"));
  await detail.getByRole("button", { name: t("action.fraud.dismiss.label") }).click();
  const dialog = world.page.getByRole("dialog");
  for (const p of s.flagged!) await expect(dialog).toContainText(p);
  await dialog.getByLabel(t("action.note")).fill(s.note);
  await dialog.getByRole("button", { name: t("action.confirm") }).click();
  await expect(dialog).toHaveCount(0);
});

Then("neither player is flagged as a suspected duplicate", async ({ world }) => {
  const s = state(world);
  const dismissed = await world.doubles.calls("players", { method: "PATCH", path: `/v1/casino/backoffice/${world.brand.slug}/fraud-cases/${s.fraudCase}/dismiss` });
  expect(dismissed).toHaveLength(1);
  expect(JSON.parse(dismissed[0]!.body)).toEqual({ notes: s.note });
  const detail = main(world).locator("[data-fraud-case]");
  for (const p of s.flagged!) await expect(detail.locator(`[data-player="${p}"]`)).toContainText(t("fraud.notFlagged"));
  for (const p of s.flagged!) {
    const profile = (await (await world.page.request.get(`${world.url}/api/s/players/players/${p}/profile`)).json()) as { suspectedDuplicate?: boolean };
    expect(profile.suspectedDuplicate, p).toBeUndefined();
  }
});

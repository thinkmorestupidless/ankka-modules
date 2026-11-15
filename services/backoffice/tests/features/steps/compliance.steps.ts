// features/backoffice/compliance.feature (T093): a compliance operator works the identity queue in
// arrival order and keeps their place; an identity document reaches only an operator the brand admits;
// a limit change shows what applies now and when the pending change takes effect; a fraud case
// dismissed clears the flags it set. kyc, responsible-gambling and players decide each rule; their own
// features hold them. Here the backoffice is what is shown and asked.
import { t } from "../../../src/shared/messages/index.ts";
import { formatMoney, parseMoney } from "../../../src/shared/money.ts";
import { DoublesPort } from "../../doubles/env.ts";
import { expect, Given, Then, When, type World } from "../world.ts";

/** What a scenario keeps between its steps. */
const kept = new WeakMap<World, { order?: string[]; document?: { player: string; id: string }; answer?: { status: number; body: string }; player?: string; limit?: { minor: bigint; currency: string }; appliesAt?: string; fraudCase?: string; flagged?: string[]; note?: string }>();
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
  const object = `/document-store/${world.brand.slug}/players/${player}/${id}`;
  await world.doubles.fixtures("kyc", {
    "GET /v1/casino/backoffice/{brand}/kyc/{playerId}/views/{documentId}": {
      body: { url: `http://127.0.0.1:${DoublesPort}${object}?X-Amz-Signature=double`, expiresAt: "2026-11-15T12:15:00Z" },
    },
  });
  await world.doubles.fixtures("document-store", {
    "GET /{brand}/players/{player}/{document}": { anonymous: true, body: "%PDF-1.4 the player's identity document", headers: { "Content-Type": "application/pdf" } },
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
  expect(await world.doubles.calls("document-store"), "the store was not asked").toHaveLength(0);
  expect(await world.doubles.calls("kyc", { path: /\/views\// }), "kyc was not asked for a view").toHaveLength(0);
});

// --- a limit raised ---------------------------------------------------------------------------------

/** responsible-gambling's operator read of the player (Render.full), with the limits and pending changes given. */
const protection = (limits: unknown[], pendingChanges: unknown[]) => ({
  limits, pendingChanges, usedAmounts: [], isCoolingOff: false, activeRestrictions: [], coolingOffUntil: null, realityCheckIntervalMinutes: null,
  exclusion: null, returnRequest: null, cases: [], history: [],
});

Given("a player with a daily deposit limit of {string}", async ({ world }, limit: string) => {
  const a = amount(limit);
  const player = world.brand.players.find((p) => p.currency === a.currency)!.playerId;
  Object.assign(state(world), { player, limit: a });
  await world.doubles.fixtures("responsible-gambling", {
    "GET /v1/casino/backoffice/{brand}/rg/settings": { body: { minSessionMinutes: 60, maxBreakDays: 42 } },
    "GET /v1/casino/brands/{brand}/rg/status": { body: { views: [] } },
    "GET /v1/casino/backoffice/{brand}/rg/{player}": { body: protection([{ limitType: "DEPOSIT_DAILY", value: Number(a.minor), currency: a.currency }], []) },
  });
});

When("a compliance operator raises it to {string}", async ({ world }, raised: string) => {
  const s = state(world);
  const to = amount(raised);
  await world.signInAs("brand_compliance", { to: `/players/${s.player}/protection` });
  const row = main(world).locator(`tr[data-limit="DEPOSIT_DAILY/${to.currency}"]`);
  await expect(row).toContainText(formatMoney(s.limit!));
  await row.getByRole("button", { name: t("action.protection.setLimit.label") }).click();
  const dialog = world.page.getByRole("dialog");
  await dialog.getByLabel(t("protection.limit.value")).fill(raised.split(/\s+/)[0]!);
  // responsible-gambling stages the raise until the brand's delay passes (its `appliesAt`)
  const appliesAt = new Date(Date.now() + 24 * 3_600_000).toISOString().replace(/\.\d+Z$/, "Z");
  s.appliesAt = appliesAt;
  const after = protection(
    [{ limitType: "DEPOSIT_DAILY", value: Number(s.limit!.minor), currency: to.currency }],
    [{ limitType: "DEPOSIT_DAILY", appliesAt, currency: to.currency, pendingValue: String(to.minor) }],
  );
  await world.doubles.fixtures("responsible-gambling", {
    "PUT /v1/casino/backoffice/{brand}/rg/{player}/limits/{type}": { body: after },
    "GET /v1/casino/backoffice/{brand}/rg/{player}": { body: after },
  });
  await dialog.getByRole("button", { name: t("action.confirm") }).click();
  await expect(dialog).toHaveCount(0);
  const put = await world.doubles.calls("responsible-gambling", { method: "PUT", path: `/v1/casino/backoffice/${world.brand.slug}/rg/${s.player}/limits/DEPOSIT_DAILY` });
  expect(put).toHaveLength(1);
  expect(JSON.parse(put[0]!.body)).toEqual({ value: String(to.minor), currency: to.currency });
});

Then("the operator is shown the limit is still {string} until the time the brand's rules set", async ({ world }, still: string) => {
  const s = state(world);
  const a = amount(still);
  const row = main(world).locator(`tr[data-limit="DEPOSIT_DAILY/${a.currency}"]`);
  await expect(row.locator("[data-current]")).toHaveText(formatMoney(a));
  const pending = row.locator("[data-pending]");
  await expect(pending).toBeVisible();
  await expect(pending).toHaveAttribute("data-applies-at", s.appliesAt!);
  await expect(pending.locator("time")).toHaveAttribute("datetime", s.appliesAt!);
});

// --- a fraud case dismissed -------------------------------------------------------------------------

Given("a fraud case that flagged two players as suspected duplicates", async ({ world }) => {
  const [ana, kenji] = world.brand.players;
  const caseId = "5d2c9a7e-1f4b-4e0a-9b8c-3a6d2e1f0c44";
  Object.assign(state(world), { fraudCase: caseId, flagged: [ana!.playerId, kenji!.playerId] });
  await world.doubles.fraudCases({
    cases: [
      { caseId, playerIds: [ana!.playerId, kenji!.playerId], signals: ["device", "ip"], status: "OPEN", detectedAt: "2026-11-14T10:00:00Z", reviewedBy: null, reviewedAt: null, reviewNotes: null },
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

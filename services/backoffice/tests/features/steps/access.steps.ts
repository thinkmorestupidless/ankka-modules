// features/backoffice/access.feature (T048): who works in a brand's backoffice and what they may do.
// What each owner admits is held by its own operator-roles.feature; here the page offers or refuses
// each action, and the server passes the operator's token to the owner. The player's page shows its
// standing above tabs (`/players/{p}/{tab}`, FR-020): what a scenario reads or writes in a tab is looked
// for in that tab, opened from the page's tab links.
import type { Locator, Page } from "@playwright/test";
import { expect, Given, Then, When, type World } from "../world.ts";
import { bearerOf } from "../../doubles/client.ts";
import { backofficeUrlOf, DoublesUrl, operator, type Who } from "../../doubles/env.ts";
import type { Identity } from "../../doubles/realm.ts";
import { rolesGiving } from "../../../src/shared/access.ts";
import permissions from "../../../src/shared/generated/operator-permissions.v1.json" with { type: "json" };
import { t } from "../../../src/shared/messages/index.ts";
import { Actions, describe, type ActionId } from "../../../src/web/actions/index.ts";

/** What a scenario carries between its steps. */
interface Scenario {
  identity?: Identity;
  playerId?: string;
  requestId?: string;
}
const scenarios = new WeakMap<World, Scenario>();
const of = (world: World): Scenario => {
  let s = scenarios.get(world);
  if (!s) scenarios.set(world, (s = {}));
  return s;
};

const page = (world: World): Page => world.page;
const main = (world: World): Locator => page(world).getByRole("main");
const signedInFrame = (world: World) => expect(page(world).getByRole("banner")).toBeVisible();

/** The roles that give a permission by the brand's default bags, as a disabled action names them. */
const needs = (permission: string) => t("action.needsRole", { roles: rolesGiving(permission, permissions.defaults).join(", ") });

/** An enabled action opens its confirmation dialog (Radix sets `aria-haspopup="dialog"`). */
const offered = (scope: Locator) => scope.locator('button[aria-haspopup="dialog"]:not([disabled])');

/** Open one of the player's tabs from the page's tab links, and wait for what it reads. */
async function openTab(world: World, label: string) {
  await page(world).getByRole("group", { name: t("players.tabs") }).getByRole("link", { name: label, exact: true }).click();
  await expect(page(world).getByRole("group", { name: t("players.tabs") }).getByRole("link", { name: label, exact: true })).toHaveAttribute("aria-current", "page");
  await page(world).waitForLoadState("networkidle");
  await expect(main(world).getByText(t("list.loading"), { exact: true })).toHaveCount(0);
}

// --- the brand ------------------------------------------------------------------------------------

Given("the backoffice of the brand {string}", ({ world }, brand: string) => {
  expect(world.brand.slug, "the server runs as the seeded brand's backoffice").toBe(brand);
});

Given("an operator whose roles name the brands {string} and {string}", ({ world }, a: string, b: string) => {
  of(world).identity = { sub: "op-two-brands", name: "Tess Twobrands", roles: [`brand_${a}`, `brand_${b}`, `brand_support@${a}`] };
});

Given("an operator whose roles name only the brand {string}", ({ world }, brand: string) => {
  of(world).identity = { sub: `op-${brand}-only`, name: "Sam Other", roles: [`brand_${brand}`, `brand_support@${brand}`] };
});

When("the operator signs in to the backoffice", async ({ world }) => {
  const who = of(world).identity;
  if (!who) throw new Error("no operator was given for this scenario");
  await world.signInAs(who);
});

Then("everything the operator sees and does is in {string}", async ({ world }, brand: string) => {
  await expect(page(world).getByRole("banner")).toContainText(brand);
  await expect(page(world).getByRole("navigation", { name: t("frame.areas") })).toBeVisible();
  const calls = (await world.doubles.calls()).filter((c) => c.owner !== "backoffice-state");
  expect(calls.length, "the server read the brand from its owners").toBeGreaterThan(0);
  // A call to a shared project (`psp-gateway.payments`) names no brand: that project decides by the
  // operator's own roles there. It must still name none of the operator's other brands.
  const shared = (c: { owner: string }) => c.owner.includes(".");
  const RoleNames = new Set(["brand_support", "brand_finance", "brand_compliance", "brand_admin"]);
  const elsewhere = calls.filter((c) => !shared(c) && !c.path.split("/").includes(brand)).map((c) => `${c.owner} ${c.method} ${c.path}`);
  expect(elsewhere, `every call the server made to the brand's own owners named ${brand}`).toEqual([]);
  const others = (of(world).identity?.roles ?? []).flatMap((r) => (r.startsWith("brand_") && !r.includes("@") && r !== `brand_${brand}` && !RoleNames.has(r) ? [r.slice("brand_".length)] : []));
  const leaked = calls.filter((c) => shared(c) && others.some((o) => c.path.split("/").includes(o))).map((c) => `${c.owner} ${c.method} ${c.path}`);
  expect(leaked, "no call to a shared project named another brand").toEqual([]);
});

Then("no other brand is offered", async ({ world }) => {
  const other = /spinvibe/i;
  await expect(page(world).getByText(other)).toHaveCount(0);
  for (const role of ["link", "button", "option", "menuitem", "tab"] as const)
    await expect(page(world).getByRole(role, { name: other })).toHaveCount(0);
  await expect(page(world).getByRole("banner").getByRole("combobox")).toHaveCount(0);
  const calls = await world.doubles.calls();
  expect(calls.filter((c) => other.test(c.path)).map((c) => `${c.owner} ${c.path}`), "no call named another brand").toEqual([]);
});

Then("the operator is told the role for {string} is needed", async ({ world }, brand: string) => {
  await expect(page(world).getByText(t("access.needRole", { brand, role: `brand_${brand}` }))).toBeVisible();
});

Then("nothing of the brand is shown", async ({ world }) => {
  await expect(page(world).getByRole("navigation")).toHaveCount(0);
  await expect(page(world).getByRole("main")).toHaveCount(0);
  await expect(page(world).getByRole("banner")).toHaveCount(0);
  for (const p of world.brand.players) await expect(page(world).getByText(p.username)).toHaveCount(0);
});

// --- an action the roles do not admit (the withdrawal request page: a later task) -----------------

Given("a compliance operator of a brand and a withdrawal request pending review", async ({ world }) => {
  await pendingWithdrawal(world);
  await world.signInAs("brand_compliance");
});

/** A withdrawal request pending review, as payouts answers it in any brand. */
async function pendingWithdrawal(world: World): Promise<string> {
  const requestId = "w-2001";
  of(world).requestId = requestId;
  await world.doubles.fixtures("payouts", {
    "GET /v1/casino/brands/{brand}/withdrawal-requests/{requestId}": {
      by: "requestId",
      answers: {
        [requestId]: {
          // a request as payouts answers an operator (OperatorEndpoint.detail)
          body: {
            requestId,
            playerId: "p-kenji",
            kycStatus: "verified",
            amount: 30000,
            paidAmount: 30000,
            voidedAmount: 0,
            currency: "JPY",
            status: "PENDING_REVIEW",
            payoutStatus: "NONE",
            terminal: { id: "t-pix", name: "Pix out", gateway: "paysafe", scheme: "bank_transfer" },
            destination: { destinationId: "d-1", firstName: "Kenji", lastName: "Sato", ibanLastFour: "4821" },
            bonusForfeited: false,
            forfeitedAmount: 0,
            clawbackAmount: 0,
            providerReference: null,
            balancesAtRequest: 30000,
            restrictedAtRequest: 0,
            decisions: [],
            outcomes: [],
            transitions: [{ status: "pending_review", at: "2026-11-14T22:15:00Z", by: "player" }],
            createdAt: "2026-11-14T22:15:00Z",
            initiatedAt: null,
            settledAt: null,
          },
        },
      },
    },
  });
  return requestId;
}

When("the operator opens the withdrawal request", async ({ world }) => {
  await world.open(`/withdrawals/${of(world).requestId}`);
  await signedInFrame(world);
});

const approve = (world: World) => main(world).getByRole("button", { name: t(describe("withdrawal.approve").label), exact: true });

Then("approving it is shown as needing the finance role", async ({ world }) => {
  const button = approve(world);
  await expect(button).toBeVisible();
  await expect(button).toHaveAccessibleDescription(needs(describe("withdrawal.approve").permission));
  await expect(button).toHaveAccessibleDescription(/brand_finance/);
});

Then("the operator cannot start it", async ({ world }) => {
  const button = approve(world);
  await expect(button).toBeDisabled();
  await button.click({ force: true });
  await expect(page(world).getByRole("dialog")).toHaveCount(0);
  expect(await world.doubles.calls("payouts", { method: "POST" })).toEqual([]);
});

// --- a refusal with its reason and next step (the player's rewards: a later task) ------------------

Given("a reward the brand refuses to award because the player is flagged as a suspected duplicate", async ({ world }) => {
  const playerId = "p-fahad";
  of(world).playerId = playerId;
  await world.doubles.fixtures("rewards", {
    "GET /v1/casino/brands/{brand}/reward-grants": { body: { items: [], total: 0, page: 1, pageSize: 0 } },
    "GET /v1/casino/brands/{brand}/offers": {
      body: { items: [{ id: "o-welcome", name: "Welcome match", offerType: "DEPOSIT_MATCH", active: true, currency: "KWD" }], total: 1 },
    },
    // OperatorAwards.scala: a decline is 409 {status: "ineligible", reason}; Decline.SuspectedDuplicate.
    "POST /v1/casino/brands/{brand}/reward-grants/{playerId}": { status: 409, body: { status: "ineligible", reason: "SUSPECTED_DUPLICATE" } },
  });
  await world.signInAs("brand_finance");
});

When("an operator awards the player the reward", async ({ world }) => {
  await world.open(`/players/${of(world).playerId}/rewards`);
  await signedInFrame(world);
  const award = main(world).getByRole("button", { name: t(describe("reward.grant").label), exact: true });
  await expect(award, "the player's rewards offer an award").toBeEnabled();
  await award.click();
  const dialog = page(world).getByRole("dialog");
  await expect(dialog).toBeVisible();
  await dialog.getByRole("radio", { name: t("rewards.grant.fromOffer") }).check();
  // the brand's offers are listed to an operator who manages them; another names the offer by its id
  const offers = dialog.getByRole("combobox", { name: t("rewards.grant.offer") });
  if ((await offers.count()) > 0) await offers.selectOption({ label: "Welcome match" });
  else await dialog.getByRole("textbox", { name: t("rewards.grant.offerId") }).fill("o-welcome");
  await dialog.getByRole("button", { name: t("action.confirm"), exact: true }).click();
});

const refusal = (world: World) => page(world).getByRole("alert").filter({ hasText: t("refusal.suspected_duplicate") });

Then("the operator is shown that the player is a suspected duplicate", async ({ world }) => {
  await expect(refusal(world)).toBeVisible();
  expect(await world.doubles.calls("rewards", { method: "POST", path: `/v1/casino/brands/${world.brand.slug}/reward-grants/${of(world).playerId}` })).toHaveLength(1);
});

Then("the operator is shown that clearing the flag would allow it", async ({ world }) => {
  await expect(refusal(world)).toContainText(t("refusal.suspected_duplicate.next"));
});

// --- the sign-in never reaches the page's scripts ----------------------------------------------------

Given("an operator signed in to the backoffice", async ({ world }) => {
  await world.signInAs("brand_support");
});

/** Everything the page's scripts can read, keyed by where it came from. */
const readable = new WeakMap<World, Record<string, string>>();

When("the backoffice's page runs in the operator's browser", async ({ world }) => {
  await signedInFrame(world);
  const seen = await page(world).evaluate(async () => {
    const read = async (path: string) => {
      const r = await fetch(path, { credentials: "same-origin" });
      return `${r.status} ${JSON.stringify([...r.headers])} ${await r.text()}`;
    };
    const storage = (s: Storage) => JSON.stringify(Object.fromEntries(Object.keys(s).map((k) => [k, s.getItem(k)])));
    return {
      cookie: document.cookie,
      localStorage: storage(window.localStorage),
      sessionStorage: storage(window.sessionStorage),
      document: document.documentElement.outerHTML,
      me: await read("/api/me"),
      permissions: await read("/api/permissions"),
      players: await read("/api/s/players/players"),
    };
  });
  readable.set(world, seen);
});

Then("nothing the page can read holds the operator's sign-in", async ({ world }) => {
  const seen = readable.get(world);
  if (!seen) throw new Error("the page was not read");
  const cookie = (await page(world).context().cookies(world.url)).find((c) => c.name === "__Host-bo");
  expect(cookie, "the server's session cookie is set").toBeDefined();
  expect(cookie!.httpOnly, "the session cookie is HttpOnly").toBe(true);

  const calls = await world.doubles.calls();
  const tokens = [...new Set(calls.map(bearerOf).filter((x): x is string => x !== undefined))];
  expect(tokens.length, "the server called an owner with the operator's token").toBeGreaterThan(0);
  const sessions = [...new Set(calls.flatMap((c) => /^\/sessions\/([^/]+)\/token$/.exec(c.path)?.[1] ?? []))].map(decodeURIComponent);
  expect(sessions.length, "the server refreshed the token from backoffice-state").toBeGreaterThan(0);
  const secrets = [cookie!.value, ...tokens, ...sessions].filter((s) => s.length >= 8);

  for (const [where, text] of Object.entries(seen)) {
    for (const secret of secrets) expect(text.includes(secret), `${where} holds the sign-in`).toBe(false);
    expect(text, `${where} holds something token-shaped`).not.toMatch(/eyJ[\w-]{8,}\.[\w-]{8,}\./);
    expect(text, `${where} names the session cookie`).not.toContain("__Host-bo");
  }
});

// --- a support operator reads a player (the player page: a later task) -----------------------------

Given("a support operator of a brand", async ({ world }) => {
  of(world).playerId = "p-ana";
  await seedPlayer(world);
  await world.signInAs("brand_support");
});

When("the operator reads a player", async ({ world }) => {
  await world.open(`/players/${of(world).playerId}`);
  await signedInFrame(world);
});

Then("the operator sees the player's account, wallets, rounds, withdrawal requests and rewards", async ({ world }) => {
  const player = of(world).playerId!;
  // the standing: the account and the wallets
  for (const part of ["players.standing.account", "players.standing.balances"] as const)
    await expect(main(world).getByRole("heading", { level: 2, name: t(part), exact: true })).toBeVisible();
  await expect(main(world).locator('[data-part="identity"]')).toContainText("ana@example.test");
  // the tabs: rounds, withdrawal requests and rewards, each read and none refused
  const tabs: [string, string][] = [
    [t("players.tab.games"), t("players.tab.games")],
    [t("players.tab.withdrawals"), t("withdrawals.player.title", { player })],
    [t("players.tab.rewards"), t("rewards.player.title", { player })],
  ];
  for (const [tab, heading] of tabs) {
    await openTab(world, tab);
    await expect(main(world).getByRole("heading", { name: heading, exact: true })).toBeVisible();
    await expect(main(world).getByRole("alert"), `${tab} is read`).toHaveCount(0);
  }
});

/** The actions whose label is this text. */
const actionsNamed = (text: string) => (Object.keys(Actions) as ActionId[]).filter((id) => t(describe(id).label) === text);

Then("the only writes offered are a note and an inbox item", async ({ world }) => {
  // every write offered on the player's page: its standing, and each of its tabs
  const seen = new Set<string>();
  const collect = async () => {
    for (const name of await offered(main(world)).allTextContents()) seen.add(name.trim());
  };
  await collect();
  const tabs = (await page(world).getByRole("group", { name: t("players.tabs") }).getByRole("link").allTextContents()).map((s) => s.trim());
  for (const tab of tabs) {
    await openTab(world, tab);
    // a tab that could not be read could hide what it offers
    await expect(main(world).getByRole("alert"), `${tab} is read`).toHaveCount(0);
    await collect();
  }
  const names = [...seen];
  const allowed = ["note.write", "message.send"];
  const other = names.filter((n) => !actionsNamed(n).some((id) => allowed.includes(describe(id).permission)));
  expect(other, `writes offered beside a note and an inbox item (${tabs.join(", ")})`).toEqual([]);
  expect(names, "a note is offered").toContain(t(describe("note.write").label));
  expect(names, "an inbox item is offered").toContain(t(describe("message.send").label));
});

// --- adjustments and limits by role ----------------------------------------------------------------

Given("an operator of a brand holding the {string} role", async ({ world }, role: string) => {
  of(world).playerId = "p-ana";
  await seedPlayer(world);
  await world.doubles.fixtures("wallet", {
    "POST /v1/casino/backoffice/{brand}/wallets/{player}/{currency}/adjustments": { body: { transactionId: "t-1", balanceMinorUnits: 126050 } },
  });
  await world.doubles.fixtures("responsible-gambling", {
    "PUT /v1/casino/backoffice/{brand}/rg/{player}/limits/{type}": { body: { type: "DEPOSIT_DAILY", amount: 50000, status: "active" } },
  });
  await world.signInAs(operator(role as Who));
});

When("the operator makes an adjustment and sets a limit for a player", async ({ world }) => {
  await world.open(`/players/${of(world).playerId}`);
  await signedInFrame(world);
});

/** The adjustment, in the player's wallets tab. */
async function adjustment(world: World) {
  await openTab(world, t("players.tab.wallets"));
  return main(world).getByRole("button", { name: t(describe("wallet.adjust").label), exact: true });
}
/** A new limit, in the player's protection tab. */
async function limit(world: World) {
  await openTab(world, t("players.tab.protection"));
  return main(world).getByRole("button", { name: t(describe("protection.newLimit").label), exact: true });
}

/** Admitted: the page offers the action, and a call through the server reaches the owner with the operator's token. */
async function admittedOrRefused(world: World, action: Locator, expected: string, permission: string, call: { owner: string; method: string; path: string; upstream: RegExp; body: unknown }) {
  await expect(action).toBeVisible();
  if (expected === "refused") {
    await expect(action).toBeDisabled();
    await expect(action).toHaveAccessibleDescription(needs(permission));
    return;
  }
  expect(expected).toBe("admitted");
  await expect(action).toBeEnabled();
  await expect(action).toHaveAttribute("aria-haspopup", "dialog");
  const status = await page(world).evaluate(
    async ({ method, path, body }) => (await fetch(path, { method, headers: { "Content-Type": "application/json", "X-Backoffice": "1" }, body: JSON.stringify(body) })).status,
    { method: call.method, path: call.path, body: call.body },
  );
  expect(status, `${call.method} ${call.path} through the server`).toBe(200);
  await world.doubles.expectAuthorizedAs(call.owner, world.operator!.sub, { method: call.method, path: call.upstream });
}

Then("the adjustment is {string}", async ({ world }, expected: string) => {
  await admittedOrRefused(world, await adjustment(world), expected, describe("wallet.adjust").permission, {
    owner: "wallet",
    method: "POST",
    path: "/api/s/wallet/wallets/p-ana/BRL/adjustments",
    upstream: /\/wallets\/p-ana\/BRL\/adjustments$/,
    body: { key: "adjust/access-feature", walletType: "real", amountMinor: 1000, reason: "goodwill", category: "compensation" },
  });
});

Then("the limit is {string}", async ({ world }, expected: string) => {
  await admittedOrRefused(world, await limit(world), expected, describe("protection.newLimit").permission, {
    owner: "responsible-gambling",
    method: "PUT",
    path: "/api/s/responsible-gambling/p-ana/limits/DEPOSIT_DAILY",
    upstream: /\/rg\/p-ana\/limits\/DEPOSIT_DAILY$/,
    body: { value: "50000", currency: "BRL" },
  });
});

/** The reads a player page makes beyond the seeded profile, answered empty. */
async function seedPlayer(world: World) {
  await world.doubles.fixtures("responsible-gambling", {
    "GET /v1/casino/backoffice/{brand}/rg/{player}": {
      body: { limits: [], pendingChanges: [], usedAmounts: [], isCoolingOff: false, activeRestrictions: [], coolingOffUntil: null, realityCheckIntervalMinutes: null, exclusion: null, returnRequest: null, cases: [], history: [] },
    },
    "GET /v1/casino/backoffice/{brand}/rg/{player}/history": { body: [] },
  });
  await world.doubles.fixtures("wallet", {
    "GET /v1/casino/backoffice/{brand}/wallets/{player}/{currency}/balance": {
      body: { realBalance: "1250.50", bonusBalance: "0.00", currency: "BRL", primary: null, depositPoolBalance: "1250.50", convertedPoolBalance: "0.00", reserved: "0.00", available: "1250.50" },
    },
    "GET /v1/casino/backoffice/{brand}/wallets/{player}/{currency}/restricted-funds": { body: { records: [], headline: null, restrictedBalance: 0 } },
    "GET /v1/casino/backoffice/{brand}/wallets/{player}/{currency}/lifetime-totals": { body: { totalDeposited: "0.00", totalWagered: "0.00", totalWon: "0.00" } },
    "GET /v1/casino/backoffice/{brand}/wallets/{player}/{currency}/transactions": { body: { transactions: [], page: 0, pageSize: 20, total: 0 } },
  });
  await world.doubles.fixtures("payouts", {
    "GET /v1/casino/brands/{brand}/players/{playerId}/withdrawal-history": { body: { requests: [], total: 0 } },
  });
  await world.doubles.fixtures("rewards", {
    "GET /v1/casino/brands/{brand}/reward-grants": { body: { items: [], total: 0, page: 1, pageSize: 0 } },
  });
  await world.doubles.fixtures("players", {
    "GET /v1/casino/backoffice/{brand}/players/{playerId}/bets": { body: { items: [], total: 0 } },
    "GET /v1/casino/backoffice/{brand}/players/{playerId}/sessions": { body: { items: [], total: 0 } },
    "GET /v1/casino/backoffice/{brand}/players/{playerId}/activity": { body: { events: [], page: 0, pageSize: 50, hasMore: false, total: 0, next: null } },
    "GET /v1/casino/backoffice/{brand}/players/{playerId}/player-sessions": { body: { sessions: [], total: 0 } },
    "GET /v1/casino/backoffice/{brand}/players/{playerId}/notes": { body: { notes: [], total: 0 } },
    "GET /v1/casino/backoffice/{brand}/players/{playerId}/messages": { body: { items: [], total: 0, page: 1, pageSize: 20 } },
    "GET /v1/casino/backoffice/{brand}/players/{playerId}/crm": { body: { emailMarketingConsent: false, smsMarketingConsent: false, messageSuppressed: false } },
    "GET /v1/casino/backoffice/{brand}/merge-tags": { body: { tags: [] } },
    // the games played, devices, and tier and tags tabs (T114)
    "GET /v1/casino/backoffice/{brand}/players/{playerId}/games": { body: { games: [], truncated: false } },
    "GET /v1/casino/backoffice/{brand}/players/{playerId}/devices": { body: { devices: [] } },
    "GET /v1/casino/backoffice/{brand}/players/{playerId}/tags": { body: { tags: [], setBy: null, setAt: null } },
  });
  await world.doubles.fixtures("engagement", {
    "GET /v1/casino/backoffice/{brand}/players/{player}/sends": { body: { sends: [], total: 0 } },
  });
  await world.doubles.fixtures("kyc", {
    "GET /v1/casino/backoffice/{brand}/kyc/{playerId}": {
      body: { playerId: "p-ana", status: "verified", mode: "automatic", categories: [], other: [], missingProfileFields: [], readyAt: null, sof: "unverified", requiredOfPlayer: [], deadline: null, depositsByCurrency: {}, flags: [] },
    },
    "GET /v1/casino/backoffice/{brand}/kyc/{playerId}/decisions": { body: { decisions: [] } },
  });
  await world.doubles.fixtures("game-provider", {
    "GET /v1/casino/backoffice/{brand}/game-provider/rounds": { body: { rounds: [], total: 0, page: 1, pageSize: 50 } },
  });
}

// --- roles held per brand (spec 032) -------------------------------------------------------------
// Each brand's backoffice runs (the seeded brand's and the second's, `doubles/env.ts`); a brand's giving
// is what players asks merchant to set, so here the players double is asked as the backoffice asks it.

/** The default role a step names by its short name ("finance") or any role by its own. */
const roleNamed = (word: string) => (["support", "finance", "compliance", "admin"].includes(word) ? `brand_${word}` : word);

/** `brand`'s giving, as its backoffice asks players for it (P13). */
async function give(world: World, brand: string, roles: string[]): Promise<void> {
  const who = of(world).identity;
  if (!who) throw new Error("no operator was given for this scenario");
  const r = await fetch(`${DoublesUrl}/players/v1/casino/backoffice/${brand}/role-assignments/${who.sub}`, {
    method: "PUT",
    headers: { authorization: "Bearer giver", "content-type": "application/json" },
    body: JSON.stringify({ roles }),
  });
  expect(r.status, await r.clone().text()).toBeLessThan(300);
}

/** What `/api/me` answers the signed-in operator at `brand`'s backoffice. */
async function me(world: World, brand: string): Promise<{ roles: string[]; permissions: string[] }> {
  const r = await page(world).request.get(`${backofficeUrlOf(brand)}/api/me`);
  expect(r.status(), await r.text()).toBe(200);
  return (await r.json()) as { roles: string[]; permissions: string[] };
}

Given("the operator holds the {word} role in {string}", ({ world }, word: string, brand: string) => {
  const who = of(world).identity;
  if (!who) throw new Error("no operator was given for this scenario");
  who.roles = [...who.roles.filter((r) => !r.endsWith(`@${brand}`)), `${roleNamed(word)}@${brand}`];
});

When("{string} gives the operator its {word} role", async ({ world }, brand: string, word: string) => {
  await give(world, brand, [roleNamed(word)]);
});

When("{string} gives the operator its role {string}", async ({ world }, brand: string, role: string) => {
  await give(world, brand, [role]);
});

const approveIn = async (world: World, brand: string) => {
  const requestId = of(world).requestId ?? (await pendingWithdrawal(world));
  const who = of(world).identity;
  if (!who) throw new Error("no operator was given for this scenario");
  await world.signInAs(who, { brand });
  await page(world).goto(`${backofficeUrlOf(brand)}/withdrawals/${requestId}`);
  await signedInFrame(world);
  return approve(world);
};

Then("in {string} approving a withdrawal request is refused, naming the permission it needs", async ({ world }, brand: string) => {
  const button = await approveIn(world, brand);
  await expect(button).toBeDisabled();
  await expect(button).toHaveAccessibleDescription(needs(describe("withdrawal.approve").permission));
});

Then("in {string} approving a withdrawal request is admitted", async ({ world }, brand: string) => {
  const button = await approveIn(world, brand);
  await expect(button).toBeEnabled();
});

/** What each brand's "vip_host" carries in the two-brands scenario: different, and none a default support role holds. */
const VipHost: Record<string, string[]> = { goldvibe: ["offer.manage"], spinvibe: ["tier.change"] };

Given("the brands {string} and {string} each have a role {string} carrying different permissions", async ({ world }, a: string, b: string, role: string) => {
  for (const brand of [a, b]) {
    const r = await fetch(`${DoublesUrl}/players/v1/casino/backoffice/${brand}/roles/${role}`, {
      method: "PUT",
      headers: { authorization: "Bearer admin", "content-type": "application/json" },
      body: JSON.stringify({ description: `${brand}'s ${role}`, permissions: VipHost[brand] }),
    });
    expect(r.status, await r.clone().text()).toBe(200);
  }
  expect(VipHost[a]).not.toEqual(VipHost[b]);
  void world;
});

Then("in {string} the operator is offered nothing that the role {string} of {string} carries", async ({ world }, brand: string, _role: string, owner: string) => {
  const who = of(world).identity!;
  await world.signInAs(who, { brand });
  const held = new Set((await me(world, brand)).permissions);
  expect(VipHost[owner]!.filter((p) => held.has(p)), `${brand} opens none of its own role's permissions for a role another brand gave`).toEqual([]);
  // the brand that gave it opens its own bag, once its server has read the role (at most 10 s, FR-008b)
  await world.signInAs(who);
  const giver = world.brand.slug;
  await expect
    .poll(async () => (await me(world, giver)).permissions.filter((p) => VipHost[giver]!.includes(p)), {
      message: `${giver} opens its own role's permissions`,
      timeout: 15_000,
    })
    .toEqual(VipHost[giver]);
});

Given("an operator who holds the finance role in {string} and whose roles do not name the brand {string}", ({ world }, brand: string, _same: string) => {
  of(world).identity = { sub: "op-finance-unnamed", name: "Fin Unnamed", roles: ["brand_spinvibe", `brand_finance@${brand}`] };
});

When("the operator approves a withdrawal request in {string}", async ({ world }, brand: string) => {
  const requestId = await pendingWithdrawal(world);
  await world.signInAs(of(world).identity!, { brand });
  await page(world).goto(`${backofficeUrlOf(brand)}/withdrawals/${requestId}`);
});

Given("an operator who holds the finance role in {string} and the support role in {string}", ({ world }, a: string, b: string) => {
  of(world).identity = { sub: "op-finance-support", name: "Fran Twobrands", roles: [`brand_${a}`, `brand_${b}`, `brand_finance@${a}`, `brand_support@${b}`] };
});

Then("the operator is shown the finance role and offered what it carries", async ({ world }) => {
  const answer = await me(world, world.brand.slug);
  expect(answer.roles).toEqual(["brand_finance"]);
  const finance = (permissions.defaults as Record<string, string[]>).brand_finance!;
  expect([...answer.permissions].filter((p) => permissions.permissions.includes(p)).sort()).toEqual([...finance].sort());
});

Then("no role of {string} is shown", async ({ world }, other: string) => {
  const answer = await me(world, world.brand.slug);
  expect(answer.roles.filter((r) => r.includes(other) || r === "brand_support")).toEqual([]);
  await expect(page(world).getByText(new RegExp(other, "i"))).toHaveCount(0);
});

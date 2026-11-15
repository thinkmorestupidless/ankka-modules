// features/backoffice/access.feature (T048): who works in a brand's backoffice and what they may do.
// What each owner admits is held by its own operator-roles.feature; here the page offers or refuses
// each action, and the server passes the operator's token to the owner. Screens built by later tasks
// (the withdrawal request, the player, the player's rewards) are bound now and fail until they exist.
import type { Locator, Page } from "@playwright/test";
import { expect, Given, Then, When, type World } from "../world.ts";
import { bearerOf } from "../../doubles/client.ts";
import { operator, type Who } from "../../doubles/env.ts";
import type { Identity } from "../../doubles/realm.ts";
import { rolesGiving } from "../../../src/shared/access.ts";
import permissions from "../../../src/shared/generated/operator-permissions.v1.json" with { type: "json" };
import { t } from "../../../src/shared/messages/index.ts";
import { describe } from "../../../src/web/actions/index.ts";

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

// --- the brand ------------------------------------------------------------------------------------

Given("the backoffice of the brand {string}", ({ world }, brand: string) => {
  expect(world.brand.slug, "the server runs as the seeded brand's backoffice").toBe(brand);
});

Given("an operator whose roles name the brands {string} and {string}", ({ world }, a: string, b: string) => {
  of(world).identity = { sub: "op-two-brands", name: "Tess Twobrands", roles: [`brand_${a}`, `brand_${b}`, "brand_support"] };
});

Given("an operator whose roles name only the brand {string}", ({ world }, brand: string) => {
  of(world).identity = { sub: `op-${brand}-only`, name: "Sam Other", roles: [`brand_${brand}`, "brand_support"] };
});

When("the operator signs in to the backoffice", async ({ world }) => {
  const who = of(world).identity;
  if (!who) throw new Error("no operator was given for this scenario");
  await world.signInAs(who);
});

Then("everything the operator sees and does is in {string}", async ({ world }, brand: string) => {
  await expect(page(world).getByRole("banner")).toContainText(brand);
  await expect(page(world).getByRole("navigation")).toBeVisible();
  const calls = (await world.doubles.calls()).filter((c) => c.owner !== "backoffice-state");
  expect(calls.length, "the server read the brand from its owners").toBeGreaterThan(0);
  const elsewhere = calls.filter((c) => !c.path.split("/").includes(brand)).map((c) => `${c.owner} ${c.method} ${c.path}`);
  expect(elsewhere, `every call the server made named ${brand}`).toEqual([]);
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
  const requestId = "w-2001";
  of(world).requestId = requestId;
  await world.doubles.fixtures("payouts", {
    "GET /v1/casino/brands/{brand}/withdrawal-requests/{requestId}": {
      by: "requestId",
      answers: {
        [requestId]: {
          body: {
            requestId,
            playerId: "p-kenji",
            kycStatus: "verified",
            amount: 30000,
            currency: "JPY",
            terminalName: "Pix out",
            method: "bank_transfer",
            destinationLastFour: "4821",
            createdAt: "2026-11-14T22:15:00Z",
            status: "pending_review",
            payoutStatus: "NONE",
          },
        },
      },
    },
  });
  await world.signInAs("brand_compliance");
});

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
  await world.open(`/players/${of(world).playerId}?tab=rewards`);
  await signedInFrame(world);
  const award = main(world).getByRole("button", { name: /award/i }).first();
  await expect(award, "the player's rewards offer an award").toBeVisible();
  await award.click();
  const dialog = page(world).getByRole("dialog");
  await expect(dialog).toBeVisible();
  const offer = dialog.getByRole("combobox");
  if ((await offer.count()) > 0) await offer.first().selectOption({ label: "Welcome match" });
  const reason = dialog.getByRole("textbox", { name: t("action.reason") });
  if ((await reason.count()) > 0) await reason.fill("Goodwill after a failed deposit");
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
  for (const part of [/account/i, /wallet/i, /round/i, /withdrawal/i, /reward/i])
    await expect(main(world).getByRole("heading", { name: part }).first()).toBeVisible();
  await expect(main(world)).toContainText("ana.souza");
});

Then("the only writes offered are a note and an inbox item", async ({ world }) => {
  const names = (await offered(main(world)).allTextContents()).map((s) => s.trim());
  expect(names.length, "a note and an inbox item are offered").toBe(2);
  expect(names.some((n) => /note/i.test(n)), `a note among ${names.join(", ")}`).toBe(true);
  expect(names.some((n) => /inbox|message/i.test(n)), `an inbox item among ${names.join(", ")}`).toBe(true);
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

const adjustment = (world: World) => main(world).getByRole("button", { name: t(describe("wallet.adjust").label), exact: true });
const limit = (world: World) => main(world).getByRole("button", { name: /limit/i }).first();

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
  await admittedOrRefused(world, adjustment(world), expected, describe("wallet.adjust").permission, {
    owner: "wallet",
    method: "POST",
    path: "/api/s/wallet/wallets/p-ana/BRL/adjustments",
    upstream: /\/wallets\/p-ana\/BRL\/adjustments$/,
    body: { amountMinorUnits: 1000, reason: "goodwill" },
  });
});

Then("the limit is {string}", async ({ world }, expected: string) => {
  await admittedOrRefused(world, limit(world), expected, "protection.change", {
    owner: "responsible-gambling",
    method: "PUT",
    path: "/api/s/responsible-gambling/p-ana/limits/DEPOSIT_DAILY",
    upstream: /\/rg\/p-ana\/limits\/DEPOSIT_DAILY$/,
    body: { amountMinorUnits: 50000 },
  });
});

/** The reads a player page makes beyond the seeded profile and wallet, answered empty. */
async function seedPlayer(world: World) {
  await world.doubles.fixtures("payouts", {
    "GET /v1/casino/brands/{brand}/players/{playerId}/withdrawal-history": { body: { requests: [], total: 0 } },
  });
  await world.doubles.fixtures("rewards", {
    "GET /v1/casino/brands/{brand}/reward-grants": { body: { items: [], total: 0, page: 1, pageSize: 0 } },
  });
  await world.doubles.fixtures("players", {
    "GET /v1/casino/backoffice/{brand}/players/{playerId}/bets": { body: { items: [], total: 0 } },
    "GET /v1/casino/backoffice/{brand}/players/{playerId}/sessions": { body: { items: [], total: 0 } },
    "GET /v1/casino/backoffice/{brand}/players/{playerId}/activity": { body: { items: [], total: 0 } },
  });
}

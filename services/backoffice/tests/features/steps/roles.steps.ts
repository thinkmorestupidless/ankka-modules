// features/backoffice/roles.feature (T048): a brand's administrators make roles from permissions and
// give them, from the backoffice; a role's change reaches its operators without their signing in again.
// What players admits for each role is held by its own features (features/players/roles.feature); here
// the backoffice is what is offered. "Offered" is read from the frame (the areas each permission opens)
// and from `/api/me`'s permissions, from which every action the page shows is decided.
import type { Browser, Page } from "@playwright/test";
import { t } from "../../../src/shared/messages/index.ts";
import { describePermission } from "../../../src/shared/permissions.ts";
import { backofficeUrlOf, DoublesUrl, operator, OtherBrand } from "../../doubles/env.ts";
import type { Identity } from "../../doubles/realm.ts";
import { signIn } from "../../doubles/sign-in.ts";
import { expect, Given, Then, When, type World } from "../world.ts";

/** What a scenario keeps between its steps. */
const kept = new WeakMap<World, { admin?: Page; other?: Identity; marker?: string; sessions?: number }>();
const state = (w: World) => kept.get(w) ?? (kept.set(w, {}), kept.get(w)!);

/** The permissions the scenario's words name, by their catalogue description. */
const Named: Record<string, string> = {
  "read a player": "player.read",
  "send an inbox item": "message.send",
  "make an adjustment": "adjustment.make",
};

async function adminPage(world: World, browser: Browser): Promise<Page> {
  const s = state(world);
  if (s.admin) return s.admin;
  const page = world.operator ? await (await browser.newContext()).newPage() : world.page;
  await signIn(page, world.doubles, operator("brand_admin"), { to: "/team" });
  s.admin = page;
  return page;
}

async function editRole(page: Page, role: string, toggle: string[]) {
  await page.goto(new URL("/team", page.url()).href);
  const row = page.getByRole("row").filter({ has: page.getByRole("rowheader", { name: new RegExp(`^${role}\\b`) }) });
  await row.getByRole("button", { name: t("roles.edit") }).click();
  for (const p of toggle) await page.getByRole("checkbox", { name: new RegExp(`^${escape(t(describePermission(p)))}`) }).first().click();
  await page.getByRole("button", { name: t("action.role.change.label") }).click();
  await page.getByRole("dialog").getByRole("button", { name: t("action.confirm") }).click();
  await expect(page.getByRole("dialog")).toHaveCount(0);
}

const escape = (s: string) => s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");

const sessionsOpened = async (world: World) => (await world.doubles.calls("backoffice-state", { method: "POST", path: /^\/sessions$/ })).length;

async function permissionsOf(page: Page): Promise<string[]> {
  return ((await (await page.request.get(new URL("/api/me", page.url()).href)).json()) as { permissions: string[] }).permissions;
}

Given("an admin operator of a brand", async ({ world, browser }) => {
  await adminPage(world, browser);
});

When(
  "the operator creates the role {string} carrying the permissions to read a player and to send an inbox item",
  async ({ world, browser }, role: string) => {
    const page = await adminPage(world, browser);
    await page.getByRole("button", { name: t("roles.new") }).click();
    await page.getByLabel(t("roles.name")).fill(role);
    for (const p of ["player.read", "message.send"]) await page.getByRole("checkbox", { name: new RegExp(`^${escape(t(describePermission(p)))}`) }).first().click();
    await page.getByRole("button", { name: t("action.role.create.label") }).click();
    const dialog = page.getByRole("dialog");
    await expect(dialog).toContainText("message.send, player.read");
    await dialog.getByRole("button", { name: t("action.confirm") }).click();
    await expect(page.getByRole("rowheader", { name: new RegExp(`^${role}\\b`) })).toBeVisible();
  },
);

When("gives the role {string} to another operator of the brand", async ({ world, browser }, role: string) => {
  const page = await adminPage(world, browser);
  const other: Identity = { sub: "op-new-host", name: "Nadia Host", roles: [`brand_${world.brand.slug}`] };
  state(world).other = other;
  await page.getByLabel(t("assign.operator")).fill(other.sub);
  await page.getByRole("checkbox", { name: new RegExp(`^${role}$`) }).click();
  await page.getByRole("button", { name: t("action.role.give.label") }).click();
  await page.getByRole("dialog").getByRole("button", { name: t("action.confirm") }).click();
  await expect(page.getByRole("status").filter({ hasText: other.sub })).toContainText(role);
});

Then("that operator is offered reading a player and sending an inbox item", async ({ world }) => {
  const other = state(world).other!;
  await world.signInAs(other);
  // the server reads the brand's roles every 10 s, so a role made moments ago reaches it within that
  // (FR-008b's 15 s); the page is told by /api/events and re-reads the operator
  await expect.poll(() => permissionsOf(world.page), { timeout: 15_000 }).toEqual(expect.arrayContaining(["player.read", "message.send"]));
  await expect(world.page.getByRole("navigation").getByRole("link", { name: t("area.players") })).toBeVisible();
});

Then("is offered no other action", async ({ world }) => {
  expect((await permissionsOf(world.page)).sort()).toEqual(["message.send", "player.read"]);
  // the areas that player.read opens, and none another permission would
  const areas = await world.page.getByRole("navigation").getByRole("link").allTextContents();
  expect(areas.map((a) => a.trim()).sort()).toEqual([t("area.players"), t("area.worklist")].sort());
});

Given("a support operator signed in to the backoffice", async ({ world }) => {
  await world.signInAs("brand_support");
  // a mark only this page holds: a reload or a new sign-in would lose it
  const marker = `kept-${Date.now()}`;
  await world.page.evaluate((m) => ((window as unknown as { __kept: string }).__kept = m), marker);
  state(world).marker = marker;
  state(world).sessions = await sessionsOpened(world);
  await expect(world.page.getByRole("navigation").getByRole("link", { name: t("area.adjustments") })).toHaveCount(0);
});

When("an admin operator adds the permission to make an adjustment to the role {string}", async ({ world, browser }, role: string) => {
  const page = await adminPage(world, browser);
  await editRole(page, role, [Named["make an adjustment"]!]);
});

Then("within {string} seconds the support operator is offered making an adjustment", async ({ world }, seconds: string) => {
  await expect(world.page.getByRole("navigation").getByRole("link", { name: t("area.adjustments") })).toBeVisible({ timeout: Number(seconds) * 1_000 });
  expect(await permissionsOf(world.page)).toContain("adjustment.make");
});

Then("the support operator has not signed in again", async ({ world }) => {
  expect(await world.page.evaluate(() => (window as unknown as { __kept?: string }).__kept)).toBe(state(world).marker);
  // since the support operator signed in, one session was opened: the admin's
  expect(await sessionsOpened(world)).toBe(state(world).sessions! + 1);
});

// --- spec 032: a giving's state, and the roles routes check by name ------------------------------

const Desk: Identity = { sub: "op-desk", name: "Dana Desk", roles: [] };
const Refusal = "role brand_finance@goldvibe: 403 the role mapping is refused";

/** `brand`'s giving as its backoffice asks players for it (P13); the double's realm ends it as the scenario set. */
async function giveDirectly(brand: string, operatorId: string, roles: string[]) {
  const r = await fetch(`${DoublesUrl}/players/v1/casino/backoffice/${brand}/role-assignments/${operatorId}`, {
    method: "PUT",
    headers: { authorization: "Bearer giver", "content-type": "application/json" },
    body: JSON.stringify({ roles }),
  });
  expect(r.status, await r.clone().text()).toBe(202);
}

/** What players answers `brand` of the operator's giving (P13r). */
async function givingOf(brand: string, operatorId: string): Promise<{ roles: string[]; state?: string; held: string[] }> {
  const r = await fetch(`${DoublesUrl}/players/v1/casino/backoffice/${brand}/role-assignments/${operatorId}`, { headers: { authorization: "Bearer reader" } });
  return (await r.json()) as { roles: string[]; state?: string; held: string[] };
}

Given("{string} has given an operator the finance role, not yet in force", async ({ world }, brand: string) => {
  await world.doubles.giving(brand, Desk.sub, { state: "pending" });
  await giveDirectly(brand, Desk.sub, ["brand_finance"]);
  state(world).other = Desk;
});

Given("{string} has given an operator the finance role, and the realm refused it", async ({ world }, brand: string) => {
  await world.doubles.giving(brand, Desk.sub, { state: "failed", reason: Refusal });
  await giveDirectly(brand, Desk.sub, ["brand_finance"]);
  state(world).other = Desk;
});

When("an admin operator of {string} reads the operator's roles", async ({ world, browser }, brand: string) => {
  expect(brand).toBe(world.brand.slug);
  const page = await adminPage(world, browser);
  await page.getByLabel(t("assign.operator")).fill(state(world).other!.sub);
});

const givingShown = (world: World) => state(world).admin!.getByRole("group", { name: t("assign.giving.title") });

Then("the finance role is shown as asked and not yet in force", async ({ world }) => {
  const shown = givingShown(world);
  await expect(shown).toContainText(t("assign.giving.asked", { roles: "brand_finance" }));
  await expect(shown).toContainText(t("assign.giving.pending"));
});

Then("the finance role is shown as failed, with the realm's reason", async ({ world }) => {
  const shown = givingShown(world);
  await expect(shown).toContainText(t("assign.giving.asked", { roles: "brand_finance" }));
  await expect(shown).toContainText(t("assign.giving.failed", { reason: Refusal }));
});

Given("an admin operator of {string}", async ({ world, browser }, brand: string) => {
  expect(brand).toBe(world.brand.slug);
  await adminPage(world, browser);
});

When("the operator gives another operator of {string} the role {string}", async ({ world, browser }, brand: string, role: string) => {
  expect(brand).toBe(world.brand.slug);
  const page = await adminPage(world, browser);
  const other: Identity = { sub: "op-keys-desk", name: "Kai Keys", roles: [`brand_${brand}`, `brand_${OtherBrand}`] };
  state(world).other = other;
  await page.getByLabel(t("assign.operator")).fill(other.sub);
  await page.getByRole("checkbox", { name: new RegExp(`^${role}$`) }).click();
  await page.getByRole("button", { name: t("action.role.give.label") }).click();
  await page.getByRole("dialog").getByRole("button", { name: t("action.confirm") }).click();
  await expect(page.getByRole("status").filter({ hasText: other.sub })).toContainText(role);
});

Then("that operator holds the role {string} in {string} only", async ({ world }, role: string, brand: string) => {
  const other = state(world).other!;
  expect((await givingOf(brand, other.sub)).held).toEqual([role]);
  expect((await givingOf(OtherBrand, other.sub)).held).toEqual([]);
  // the brand's backoffice shows it; the other brand's does not
  await world.signInAs(other);
  const me = (await (await world.page.request.get(`${backofficeUrlOf(brand)}/api/me`)).json()) as { roles: string[] };
  expect(me.roles).toContain(role);
  await world.signInAs(other, { brand: OtherBrand });
  const there = (await (await world.page.request.get(`${backofficeUrlOf(OtherBrand)}/api/me`)).json()) as { roles: string[] };
  expect(there.roles).not.toContain(role);
});

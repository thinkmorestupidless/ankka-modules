// features/backoffice/roles.feature (T048): a brand's administrators make roles from permissions and
// give them, from the backoffice; a role's change reaches its operators without their signing in again.
// What players admits for each role is held by its own features (features/players/roles.feature); here
// the backoffice is what is offered. "Offered" is read from the frame (the areas each permission opens)
// and from `/api/me`'s permissions, from which every action the page shows is decided.
import type { Browser, Page } from "@playwright/test";
import { t } from "../../../src/shared/messages/index.ts";
import { describePermission } from "../../../src/shared/permissions.ts";
import { operator } from "../../doubles/env.ts";
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

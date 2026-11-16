// features/backoffice/affiliates.feature (T115): managers run the affiliate programme from the brand's
// backoffice. A manager holds `affiliate_manager` and the brand's role, and no role of the brand's bags:
// the programme admits them (its `Access.manager`), the brand's own services do not. The programme is a
// shared project, `affiliate-programme.programme` at the calling address, asked for this brand only.
import type { Locator } from "@playwright/test";
import { expect, Given, Then, When, type World } from "../world.ts";
import { claimsOf } from "../../doubles/client.ts";
import type { Identity } from "../../doubles/realm.ts";
import { t } from "../../../src/shared/messages/index.ts";

const Programme = "affiliate-programme.programme";

interface Scenario {
  manager?: Identity;
  flagId?: string;
  playerRef?: string;
  reason?: string;
}
const scenarios = new WeakMap<World, Scenario>();
const of = (world: World): Scenario => {
  let s = scenarios.get(world);
  if (!s) scenarios.set(world, (s = {}));
  return s;
};

const main = (world: World): Locator => world.page.getByRole("main");
const manager = (brand: string): Identity => ({ sub: "op-affiliate-manager", name: "Mara Gestora", roles: [`brand_${brand}`, "affiliate_manager"] });

// --- a manager sees the programme only ------------------------------------------------------------

Given("a manager whose roles name the brand {string}", ({ world }, brand: string) => {
  of(world).manager = manager(brand);
});

When("the manager signs in to the backoffice", async ({ world }) => {
  const who = of(world).manager;
  if (!who) throw new Error("no manager was given for this scenario");
  await world.signInAs(who, { to: "/affiliates" });
});

Then("the manager sees the programme for {string} only", async ({ world }, brand: string) => {
  await expect(main(world).getByRole("heading", { level: 1, name: t("area.affiliates") })).toBeVisible();
  await expect(world.page.getByRole("banner")).toContainText(brand);
  const rail = world.page.getByRole("navigation");
  await expect(rail.getByRole("link", { name: t("area.affiliates") })).toBeVisible();
  // the programme was asked, as the manager, and only for this brand
  await expect.poll(async () => (await world.doubles.calls(Programme)).length, { timeout: 10_000 }).toBeGreaterThan(0);
  const calls = await world.doubles.calls(Programme);
  expect(calls.filter((c) => !c.path.startsWith(`/v1/affiliates/${brand}/`)).map((c) => `${c.method} ${c.path}`), `every call named ${brand}`).toEqual([]);
  await world.doubles.expectAuthorizedAs(Programme, of(world).manager!.sub);
});

Then("sees no player, wallet or withdrawal request", async ({ world }) => {
  const rail = world.page.getByRole("navigation");
  for (const area of ["area.worklist", "area.players", "area.withdrawals", "area.adjustments", "area.payments", "area.fraud"] as const)
    await expect(rail.getByRole("link", { name: t(area) })).toHaveCount(0);
  // the backoffice's home is the worklist; a manager, who has none, is taken to the programme
  await world.open("/");
  await expect(world.page).toHaveURL(/\/affiliates$/);
  await expect(main(world).getByRole("heading", { level: 1, name: t("area.affiliates") })).toBeVisible();
  // nothing of a player, a wallet or a withdrawal was asked as the manager (the server's own reads of the
  // brand's roles and modules carry no token)
  const asked = (await world.doubles.calls()).filter((c) => ["players", "wallet", "payouts", "kyc", "responsible-gambling", "rewards"].includes(c.owner) && claimsOf(c) !== undefined);
  expect(asked.map((c) => `${c.owner} ${c.method} ${c.path}`)).toEqual([]);
});

// --- a fraud flag dismissed with a reason ---------------------------------------------------------

Given("a fraud flag raised for a player of the brand", async ({ world }) => {
  const s = of(world);
  s.manager = manager(world.brand.slug);
  s.flagId = "3f0b8f4e-5a51-4c55-9d0e-2b7a8a1c6d10";
  s.playerRef = "pl-7c1e93";
  await world.doubles.fraudFlags({
    flags: [
      {
        flagId: s.flagId,
        playerId: s.playerRef,
        affiliateId: "0f3a7d52-8e7b-4c2a-a8f1-5d6e4b3c2a19",
        brandId: world.brand.slug,
        period: "2026-10",
        reason: "bonus-cost",
        description: "4 bonus deductions, past the brand's threshold of 3",
        flaggedAt: "2026-11-02T09:30:00Z",
        dismissed: false,
        dismissedBy: null,
        dismissedAt: null,
        dismissalReason: null,
      },
    ],
  });
});

When("a manager dismisses it with the reason {string}", async ({ world }, reason: string) => {
  const s = of(world);
  s.reason = reason;
  await world.signInAs(s.manager!, { to: "/affiliates/fraud" });
  const row = main(world).locator(`tr[data-row="${s.flagId}"]`);
  await expect(row).toContainText(s.playerRef!);
  await row.click();
  await main(world).locator(`[data-flag="${s.flagId}"]`).getByRole("button", { name: t("action.fraud.dismiss.label") }).click();
  const dialog = world.page.getByRole("dialog");
  await dialog.getByLabel(t("action.reason")).fill(reason);
  await dialog.getByRole("button", { name: t("action.confirm") }).click();
  await expect(dialog).toHaveCount(0);
});

Then("the fraud flag is dismissed with that reason and the manager's name", async ({ world }) => {
  const s = of(world);
  const dismissed = await world.doubles.calls(Programme, { method: "PATCH", path: `/v1/affiliates/${world.brand.slug}/fraud-flags/${s.flagId}/dismiss` });
  expect(dismissed).toHaveLength(1);
  expect(JSON.parse(dismissed[0]!.body)).toEqual({ reason: s.reason });
  expect(claimsOf(dismissed[0]!)?.sub, "the programme records the manager who dismissed it").toBe(s.manager!.sub);
  const shown = main(world).locator(`[data-flag="${s.flagId}"]`);
  await expect(shown).toContainText(t("status.dismissed"), { timeout: 10_000 });
  await expect(shown).toContainText(s.reason!);
  await expect(shown).toContainText(s.manager!.name);
});

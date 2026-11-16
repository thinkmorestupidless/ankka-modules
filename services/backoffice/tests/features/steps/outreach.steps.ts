// features/backoffice/outreach.feature (T120; US11, FR-036): an operator who asked is told, wherever they
// are, when a player of their brand comes online, and opens the player from the notice; an operator who
// did not ask is told nothing, and the server takes no agent-stream ticket for them. An inbox item is
// previewed for the player with its merge tags filled, and is sent only when the operator sends it.
// engagement's agent stream is the double's (`tests/doubles/presence.ts`, as AgentStream.scala writes
// it); players' merge tags, preview and messages answer as OperatorStatusRoutes.scala does.
import type { Browser, Page } from "@playwright/test";
import { t } from "../../../src/shared/messages/index.ts";
import { operator } from "../../doubles/env.ts";
import { signIn } from "../../doubles/sign-in.ts";
import { expect, Given, Then, When, type World } from "../world.ts";

const P = "/v1/casino/backoffice/{brand}";

interface Scenario {
  player?: { playerId: string; prefix: string };
  subject?: string;
  body?: string;
}
const scenarios = new WeakMap<World, Scenario>();
const of = (w: World): Scenario => scenarios.get(w) ?? (scenarios.set(w, {}), scenarios.get(w)!);

/** The notice that names `prefix` coming online, wherever the operator is. */
const notice = (page: Page, prefix: string) => page.locator("[data-online]").filter({ hasText: t("outreach.notice.online", { name: prefix }) });

/** Waits until engagement holds `n` open agent streams for the brand: the server has relayed for whoever asked. */
async function streamsOpen(world: World, n: number): Promise<string[]> {
  let open: string[] = [];
  await expect.poll(async () => (open = await world.doubles.agentStreams(world.brand.slug)).length, { timeout: 15_000 }).toBe(n);
  return open;
}

// --- told when a player comes online -------------------------------------------------------------

Given("an operator who asked to be told when players of {string} come online", async ({ world }, brand: string) => {
  expect(brand).toBe(world.brand.slug);
  await world.signInAs("brand_support", { preferences: { onlineNotices: true }, to: "/" });
  await expect(world.page.getByRole("main")).toBeVisible();
});

When("a player of {string} comes online", async ({ world }, brand: string) => {
  expect(brand).toBe(world.brand.slug);
  const p = world.brand.players[0]!;
  of(world).player = { playerId: p.playerId, prefix: p.username };
  await streamsOpen(world, 1);
  expect(await world.doubles.online({ brand, playerId: p.playerId, emailPrefix: p.username })).toBe(1);
});

Then("the operator is told, and can open the player from what they were told", async ({ world }) => {
  const { playerId, prefix } = of(world).player!;
  const told = notice(world.page, prefix);
  await expect(told).toBeVisible({ timeout: 15_000 });
  await told.getByRole("link", { name: t("outreach.notice.open") }).click();
  await expect(world.page).toHaveURL(new RegExp(`/players/${encodeURIComponent(playerId)}(/|$|\\?)`));
});

async function other(browser: Browser, world: World): Promise<Page> {
  const page = await (await browser.newContext()).newPage();
  await signIn(page, world.doubles, operator("brand_finance"), { preferences: { onlineNotices: false }, to: "/" });
  await expect(page.getByRole("main")).toBeVisible();
  return page;
}

Then("no operator who did not ask is told", async ({ world, browser }) => {
  const page = await other(browser, world);
  // The other operator's events stream is open; the asking one's agent stream is still the only one.
  const asking = world.operator!.sub;
  const open = await streamsOpen(world, 1);
  expect(open).toEqual([asking]);
  const next = world.brand.players[1]!;
  expect(await world.doubles.online({ brand: world.brand.slug, playerId: next.playerId, emailPrefix: next.username })).toBe(1);
  await expect(notice(world.page, next.username)).toBeVisible({ timeout: 15_000 });
  await expect(page.locator("[data-online]")).toHaveCount(0);
  // Every ticket was taken with the asking operator's token, none with the other's.
  await world.doubles.expectAuthorizedAs("engagement", asking, { method: "POST", path: /\/agent-stream\/ticket$/ });
  await page.context().close();
});

// --- an inbox item previewed with its merge tags filled -----------------------------------------

const Tags = {
  tags: [
    { name: "first_name", label: "First name", example: "David" },
    { name: "verification_status", label: "Verification status", example: "Verified" },
    { name: "play_now_cta", label: "Play now CTA", example: "Play now" },
  ],
};

Given("an inbox item using the player's first name merge tag", async ({ world }) => {
  const s = of(world);
  s.subject = "Hello {{first_name|there}}";
  s.body = "Welcome back, {{first_name|there}}. Your table is waiting.";
  await world.doubles.fixtures("players", {
    [`GET ${P}/merge-tags`]: { body: Tags },
    [`GET ${P}/players/{playerId}/messages`]: { body: { items: [], total: 0, page: 1, pageSize: 20 } },
    // OperatorStatusRoutes.preview: the player's values filled, every tag on its fallback beside it
    [`POST ${P}/messages/preview`]: {
      body: {
        resolved: { subject: "Hello Ana", body: "Welcome back, Ana. Your table is waiting." },
        fallback: { subject: "Hello there", body: "Welcome back, there. Your table is waiting." },
        hasAllValues: true,
      },
    },
    [`POST ${P}/players/{playerId}/messages`]: {
      status: 201,
      body: { notificationId: "7f3c1a2e-4b5d-4e6f-8a9b-0c1d2e3f4a5b", state: "sent", createdAt: "2026-11-16T10:00:00Z", subject: "Hello Ana", body: "Welcome back, Ana. Your table is waiting." },
    },
  });
  await world.signInAs("brand_support", { to: "/" });
});

When("an operator previews it for a player", async ({ world }) => {
  const s = of(world);
  const ana = world.brand.players.find((p) => p.playerId === "p-ana")!;
  await world.open(`/players/${ana.playerId}/messages`);
  const main = world.page.getByRole("main");
  await main.getByLabel(t("messages.subject"), { exact: true }).fill(s.subject!);
  await main.getByLabel(t("messages.body"), { exact: true }).fill(s.body!);
  await main.getByRole("radio", { name: t("messages.category.service") }).check();
  await main.getByRole("button", { name: t("messages.preview"), exact: true }).click();
});

Then("the operator sees the inbox item with the player's first name in place of the merge tag", async ({ world }) => {
  const preview = world.page.getByRole("region", { name: t("messages.preview.heading") });
  await expect(preview).toContainText("Hello Ana");
  await expect(preview).toContainText("Welcome back, Ana. Your table is waiting.");
  await expect(preview.getByText(/\{\{/)).toHaveCount(0);
  const asked = await world.doubles.calls("players", { method: "POST", path: /\/messages\/preview$/ });
  expect(asked).toHaveLength(1);
  expect(JSON.parse(asked[0]!.body)).toMatchObject({ playerId: "p-ana", subjectTemplate: of(world).subject, bodyTemplate: of(world).body });
});

Then("the inbox item is not sent until the operator sends it", async ({ world }) => {
  const sent = () => world.doubles.calls("players", { method: "POST", path: /\/players\/p-ana\/messages$/ });
  expect(await sent()).toHaveLength(0);
  const main = world.page.getByRole("main");
  await main.getByRole("button", { name: t("action.message.send.label"), exact: true }).click();
  const dialog = world.page.getByRole("dialog");
  await expect(dialog).toContainText(t("action.message.send.effect"));
  expect(await sent()).toHaveLength(0);
  await dialog.getByRole("button", { name: t("action.confirm") }).click();
  await expect(main.getByText(t("messages.sent"))).toBeVisible();
  const calls = await sent();
  expect(calls).toHaveLength(1);
  expect(JSON.parse(calls[0]!.body)).toMatchObject({ category: "service", subjectTemplate: of(world).subject, bodyTemplate: of(world).body });
  await world.doubles.expectAuthorizedAs("players", world.operator!.sub, { method: "POST", path: /\/players\/p-ana\/messages$/ });
});

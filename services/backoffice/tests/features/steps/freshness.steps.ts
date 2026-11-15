// features/backoffice/freshness.feature (T048): a list read from the brand's views says how fresh they
// are and when it is provisional. players answers its views at `GET …/players/status` (T051); the page
// reads them through `GET /api/freshness?services=players` (T052); the players list is a later task.
import { expect, Given, Then, When } from "../world.ts";
import { t } from "../../../src/shared/messages/index.ts";

Given("a brand whose views are catching up {string} minutes behind its facts", async ({ world }, minutes: string) => {
  const asOf = new Date(Date.now() - Number(minutes) * 60_000).toISOString();
  await world.doubles.fixtures("players", {
    "GET /v1/casino/brands/{brand}/players/status": { body: { views: [{ name: "players", asOf, rebuilding: true }] } },
  });
});

When("an operator lists the brand's players", async ({ world }) => {
  await world.signInAs("brand_support", { to: "/players" });
  await expect(world.page.getByRole("main")).toContainText(world.brand.players[0]!.username);
});

Then("the list shows the views' freshness", async ({ world }) => {
  const main = world.page.getByRole("main");
  await expect(main.getByText(t("list.updated"), { exact: true })).toBeVisible();
  expect(await world.doubles.calls("players", { method: "GET", path: `/v1/casino/brands/${world.brand.slug}/players/status` }), "the views' freshness was read from players").not.toHaveLength(0);
});

Then("the list is marked provisional", async ({ world }) => {
  await expect(world.page.getByRole("main").getByText(t("list.provisional"), { exact: true })).toBeVisible();
});

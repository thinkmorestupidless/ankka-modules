// SC-008: no page route has a serious or critical axe finding, in the dark theme or the light. The
// routes are every path of `src/web/frame/router.tsx` (`routes.ts`, held to the router below), with
// the seeded records for their parameters and every section and tab; the doubles answer each page's
// owners with content (`seed.ts`), so axe looks at the tables, cards and actions, not an empty state.
// The page takes its theme from the operator's preferences, which the double `backoffice-state`
// serves; the run checks the page applied it, and that it rendered without an error, before axe looks.
import { readFileSync } from "node:fs";
import { test, expect } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import { DoublesClient } from "../doubles/client.ts";
import { DoublesUrl, operator } from "../doubles/env.ts";
import { signIn } from "../doubles/sign-in.ts";
import { byPath, player, routes, withdrawal } from "./routes.ts";
import { seedPages } from "./seed.ts";

const doubles = new DoublesClient(DoublesUrl);
const admin = operator("platform_admin");

// The router's `Pages` paths, read from its source (its modules hold CSS the test runner cannot load).
const served = [...readFileSync(new URL("../../src/web/frame/router.tsx", import.meta.url), "utf8").matchAll(/^\s*\["(\/[^"]*)",/gm)].map((m) => m[1]!);

test("the suite opens every path the router serves", () => {
  expect(served.length).toBeGreaterThan(40);
  expect(Object.keys(byPath).sort()).toEqual(served.sort());
});

for (const theme of ["dark", "light"] as const) {
  test.describe(`${theme} theme`, () => {
    test.beforeEach(async ({ page }) => {
      await doubles.reset();
      await seedPages(doubles);
      await signIn(page, doubles, admin, { preferences: { theme } });
    });

    for (const route of routes) {
      test(route, async ({ page }) => {
        const errors: string[] = [];
        page.on("pageerror", (e) => errors.push(e.message));
        await page.goto(route);
        // a page holding a stream open (who is online) never goes idle; its content is there by then
        await page.waitForLoadState("networkidle", { timeout: 10_000 }).catch(() => undefined);
        await expect(page.locator("html"), "the page applies the operator's theme").toHaveAttribute("data-theme", theme, { timeout: 5_000 });
        await expect(page.getByRole("main"), "the page renders").not.toBeEmpty();
        expect(errors, `${route} renders without an error`).toEqual([]);
        // a figure the owner did not answer is a dash, never "NaN" (DR-16)
        await expect(page.getByRole("main"), `${route} shows no NaN`).not.toContainText(/\bNaN\b/);
        const { violations } = await new AxeBuilder({ page }).analyze();
        const serious = violations.filter((v) => v.impact === "serious" || v.impact === "critical");
        expect(
          serious.map((v) => `${v.impact} ${v.id}: ${v.help} (${v.nodes.map((n) => n.target.join(" ")).slice(0, 3).join("; ")})`),
          `axe on ${route} in ${theme}`,
        ).toEqual([]);
      });
    }
  });
}

// The states platform_admin never sees: an action shown disabled with the role it needs, a refusal, the
// not-in-brand page, and a confirmation dialog open. One theme is enough for these: the two are held
// by every route above.
async function axeOn(page: import("@playwright/test").Page, what: string) {
  const { violations } = await new AxeBuilder({ page }).analyze();
  const serious = violations.filter((v) => v.impact === "serious" || v.impact === "critical");
  expect(serious.map((v) => `${v.impact} ${v.id}: ${v.help} (${v.nodes.map((n) => n.target.join(" ")).slice(0, 3).join("; ")})`), `axe on ${what}`).toEqual([]);
}

const settle = (page: import("@playwright/test").Page) => page.waitForLoadState("networkidle", { timeout: 10_000 }).catch(() => undefined);

test.describe("as brand_support", () => {
  test.beforeEach(async ({ page }) => {
    await doubles.reset();
    await seedPages(doubles);
    await signIn(page, doubles, operator("brand_support"));
  });

  for (const route of [`/players/${player}`, `/players/${player}/protection`, `/players/${player}/timeline`, `/withdrawals/${withdrawal}`, "/reports/revenue", "/configuration/brand"]) {
    test(route, async ({ page }) => {
      await page.goto(route);
      await settle(page);
      await expect(page.getByRole("main"), "the page renders").not.toBeEmpty();
      await axeOn(page, `${route} as brand_support`);
    });
  }

  test("a disabled action, with the role it needs, is among what was checked", async ({ page }) => {
    await page.goto(`/players/${player}/timeline`);
    await settle(page);
    await expect(page.locator("[data-action-disabled]").first()).toBeVisible();
  });
});

test("not in the brand", async ({ page }) => {
  await doubles.reset();
  await signIn(page, doubles, operator("brand-less"));
  await page.goto("/");
  await settle(page);
  await axeOn(page, "the not-in-brand page");
});

test("a confirmation dialog open", async ({ page }) => {
  await doubles.reset();
  await seedPages(doubles);
  await signIn(page, doubles, admin);
  await page.goto(`/players/${player}`);
  await settle(page);
  await page.locator('main button[aria-haspopup="dialog"]').first().click();
  await expect(page.getByRole("dialog")).toBeVisible();
  await axeOn(page, `/players/${player} with a confirmation open`);
});

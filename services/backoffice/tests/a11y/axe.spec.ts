// SC-008: no page route has a serious or critical axe finding, in the dark theme or the light. The
// routes are contracts/routes.md's *The page's routes*, with the seeded brand's records for their
// parameters and the areas tasks.md builds. The page takes its theme from the operator's preferences,
// which the double `backoffice-state` serves; the run checks the page applied it before axe looks.
import { test, expect } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import { DoublesClient } from "../doubles/client.ts";
import { DoublesUrl, operator } from "../doubles/env.ts";
import { signIn } from "../doubles/sign-in.ts";

const player = "p-ana";
const withdrawal = "w-1001";

export const routes: string[] = [
  "/",
  "/search",
  "/players",
  `/players/${player}`,
  ...["standing", "timeline", "wallets", "rewards", "identity", "protection", "sessions", "devices", "games", "messages"].map((t) => `/players/${player}/${t}`),
  "/withdrawals",
  `/withdrawals/${withdrawal}`,
  "/adjustments/batch",
  "/rewards",
  "/offers",
  "/identity",
  "/protection",
  "/fraud",
  "/dashboard",
  ...["revenue", "players", "transactions", "rewards", "payment-providers", "day-end", "compliance-exports", "disputes"].map((r) => `/reports/${r}`),
  ...["brand", "games", "callbacks", "crm", "agent", "attribution", "sends", "identity-policy", "protection-settings"].map((a) => `/configuration/${a}`),
  ...["hierarchy", "providers", "terminals", "deposit-bounds", "merchant", "routing", "outcomes", "lost-answers", "deposits"].map((a) => `/payments/${a}`),
  "/team",
  "/team/roles",
  ...["dashboard", "accounts", "plans", "links", "creatives", "settings", "messages", "managers", "payouts", "fraud"].map((a) => `/affiliates/${a}`),
  "/jobs",
  "/preferences",
];

const doubles = new DoublesClient(DoublesUrl);
const admin = operator("platform_admin");

for (const theme of ["dark", "light"] as const) {
  test.describe(`${theme} theme`, () => {
    test.beforeEach(async ({ page }) => {
      await doubles.reset();
      await signIn(page, doubles, admin, { preferences: { theme } });
    });

    for (const route of routes) {
      test(route, async ({ page }) => {
        await page.goto(route);
        await page.waitForLoadState("networkidle");
        await expect(page.locator("html"), "the page applies the operator's theme").toHaveAttribute("data-theme", theme, { timeout: 5_000 });
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

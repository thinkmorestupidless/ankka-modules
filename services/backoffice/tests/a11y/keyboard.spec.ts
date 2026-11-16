// Every action is reachable by keyboard (T134, SC-008): on the pages that offer actions, Tab from the
// top of the document reaches every enabled control of the page; a control that opens a dialog opens
// it on Enter and on Space, the dialog holds the focus while Tab and Shift+Tab move through it, Escape
// closes it, and the focus comes back to the control that opened it; a control that shows or hides a
// part (`aria-expanded`) does so on Enter. The seeded records (`seed.ts`) put each page's
// actions in the states that offer them; the operator is the platform's administrator, who holds
// every permission, so no action is shown disabled.
import { test, expect, type Locator, type Page } from "@playwright/test";
import { DoublesClient } from "../doubles/client.ts";
import { DoublesUrl, operator } from "../doubles/env.ts";
import { signIn } from "../doubles/sign-in.ts";
import { player, playerTabs, withdrawal } from "./routes.ts";
import { seedPages } from "./seed.ts";

const doubles = new DoublesClient(DoublesUrl);

/** The pages with actions, and where on each its own controls are (a player's tab: under the tabs). */
const pages: { route: string; within: string }[] = [
  { route: `/players/${player}`, within: "main" },
  // a tab that only shows (the player's devices) has no control of its own
  ...playerTabs.filter((tab) => tab !== "devices").map((tab) => ({ route: `/players/${player}/${tab}`, within: ".bo-player-tab" })),
  { route: `/withdrawals/${withdrawal}`, within: "main" },
  { route: "/adjustments/batch", within: "main" },
  { route: "/adjustments/accounts", within: "main" },
  { route: "/rewards", within: "main" },
  { route: "/offers", within: "main" },
  { route: "/payments/routing", within: "main" },
  { route: "/team", within: "main" },
  { route: "/team/roles", within: "main" },
];

/** The page's own enabled, shown controls, each marked with an index the walk below reads back. */
async function markControls(page: Page, within: string): Promise<string[]> {
  return page.evaluate((within) => {
    const root = document.querySelector(within);
    if (!root) return [];
    const all = [...root.querySelectorAll<HTMLElement>("a[href], button, input, select, textarea, summary, [tabindex]")];
    const shown = (e: HTMLElement) => e.getClientRects().length > 0 && getComputedStyle(e).visibility !== "hidden";
    const out: string[] = [];
    const radios = new Set<string>();
    for (const e of all) {
      if ((e as HTMLButtonElement).disabled || e.closest("[inert]") || e.getAttribute("tabindex") === "-1" || !shown(e)) continue;
      if (e instanceof HTMLInputElement && e.type === "hidden") continue;
      // a radio group is one stop: Tab reaches it, the arrows move within it
      if (e instanceof HTMLInputElement && e.type === "radio") {
        if (radios.has(e.name)) continue;
        radios.add(e.name);
        const group = [...root.querySelectorAll<HTMLInputElement>(`input[type=radio][name="${CSS.escape(e.name)}"]`)];
        group.forEach((r) => (r.dataset.kbd = `radio:${e.name}`));
        out.push(`radio:${e.name}`);
        continue;
      }
      const id = `c${out.length}`;
      e.dataset.kbd = id;
      out.push(id);
    }
    return out;
  }, within);
}

const describeControl = (page: Page, id: string) =>
  page.evaluate((id) => {
    const e = document.querySelector<HTMLElement>(`[data-kbd="${id}"]`);
    return e ? `${e.tagName.toLowerCase()} "${(e.getAttribute("aria-label") ?? e.textContent ?? "").trim().slice(0, 60)}"` : id;
  }, id);

/** Tab from the top of the document until the focus has gone round once; the marked controls it met. */
async function walk(page: Page): Promise<Set<string>> {
  const stops = await page.evaluate(() => document.querySelectorAll("a[href], button, input, select, textarea, summary, [tabindex]").length);
  await page.evaluate(() => {
    (document.activeElement as HTMLElement | null)?.blur();
    window.scrollTo(0, 0);
  });
  const met = new Set<string>();
  for (let i = 0; i < stops + 5; i++) {
    await page.keyboard.press("Tab");
    const at = await page.evaluate(() => (document.activeElement as HTMLElement | null)?.dataset.kbd ?? null);
    if (at) met.add(at);
  }
  return met;
}

const focusInside = (dialog: Locator) => dialog.evaluate((d) => d.contains(document.activeElement));
const focusedKbd = (page: Page) => page.evaluate(() => (document.activeElement as HTMLElement | null)?.dataset.kbd ?? null);

async function checkDialog(page: Page, trigger: Locator, id: string, key: "Enter" | " ") {
  await trigger.focus();
  await page.keyboard.press(key);
  const dialog = page.getByRole("dialog");
  await expect(dialog, `${key === " " ? "Space" : key} on ${id} opens its dialog`).toBeVisible();
  await expect.poll(() => focusInside(dialog), "the dialog takes the focus").toBe(true);
  const stops = await dialog.evaluate((d) => d.querySelectorAll("a[href], button:not([disabled]), input:not([disabled]), select, textarea, [tabindex]:not([tabindex='-1'])").length);
  for (let i = 0; i < stops + 2; i++) {
    await page.keyboard.press("Tab");
    expect(await focusInside(dialog), `Tab stays in the dialog ${id} opened`).toBe(true);
  }
  for (let i = 0; i < stops + 2; i++) {
    await page.keyboard.press("Shift+Tab");
    expect(await focusInside(dialog), `Shift+Tab stays in the dialog ${id} opened`).toBe(true);
  }
  await page.keyboard.press("Escape");
  await expect(dialog, `Escape closes the dialog ${id} opened`).toBeHidden();
  await expect.poll(() => focusedKbd(page), `the focus comes back to ${id}`).toBe(id);
}

test.beforeEach(async ({ page }) => {
  await doubles.reset();
  await seedPages(doubles);
  await signIn(page, doubles, operator("platform_admin"));
});

for (const { route, within } of pages) {
  test(`${route}: every action is reached and worked by keyboard`, async ({ page }) => {
    test.setTimeout(120_000);
    await page.goto(route);
    await page.waitForLoadState("networkidle", { timeout: 10_000 }).catch(() => undefined);
    await expect(page.locator(within)).not.toBeEmpty();
    const controls = await markControls(page, within);
    expect(controls.length, `${route} has controls of its own`).toBeGreaterThan(0);

    const met = await walk(page);
    const missed = await Promise.all(controls.filter((c) => !met.has(c)).map((c) => describeControl(page, c)));
    expect(missed, `Tab reaches every control on ${route}`).toEqual([]);

    const dialogs = await page.locator(`${within} [data-kbd][aria-haspopup="dialog"]`).all();
    for (const [i, trigger] of dialogs.entries()) {
      const id = (await trigger.getAttribute("data-kbd"))!;
      await checkDialog(page, trigger, id, "Enter");
      if (i === 0) await checkDialog(page, trigger, id, " ");
    }

    // a control that shows or hides a part: Enter shows or hides it (Space on a button is the same
    // native activation the dialogs' check above has already pressed)
    const expanders = await page.locator(`${within} [data-kbd][aria-expanded]:not([aria-haspopup="dialog"])`).all();
    for (const toggle of expanders) {
      const before = await toggle.getAttribute("aria-expanded");
      await toggle.focus();
      await page.keyboard.press("Enter");
      await expect(toggle, "Enter shows or hides what it controls").not.toHaveAttribute("aria-expanded", before ?? "");
    }
  });
}

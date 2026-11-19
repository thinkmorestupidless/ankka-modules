import { execFileSync } from "node:child_process";
import { mkdtempSync, readdirSync, readFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { expect, type Page } from "@playwright/test";
import { createBdd } from "playwright-bdd";
import { test } from "./fixtures.ts";
import { forbidden } from "../imports.ts";

const { Given, When, Then } = createBdd(test);
const root = path.resolve(import.meta.dirname, "../..");

/** The language the page answers in: the document's own, and a word only that language's lobby shows. */
async function answersIn(page: Page, language: string) {
  await expect(page.locator("html")).toHaveAttribute("lang", language);
  await expect(page.getByRole("heading", { level: 1 })).toHaveText(language === "fr" ? "Jeux" : "Games");
}

// ── French and English ──────────────────────────────────────────────────────────────────────────
Given("brand {string} running the module {string}", async ({ sites }, brand: string, module: string) => {
  const site = await sites.start({ brand });
  expect(site.doubles.brand.modules).toContain(module);
});
When("a visitor whose browser asks for {string} opens the brand's player site address", async ({ browser, sites, $testInfo }, language: string) => {
  const context = await browser.newContext({ locale: language });
  const page = await context.newPage();
  await page.goto(sites.current.url);
  ($testInfo as unknown as { page: Page }).page = page;
});
Then("the reference site answers in {string}", async ({ $testInfo }, language: string) => {
  await answersIn(($testInfo as unknown as { page: Page }).page, language);
});
When("the visitor chooses {string}", async ({ $testInfo }, language: string) => {
  const page = ($testInfo as unknown as { page: Page }).page;
  await page.getByRole("combobox").selectOption(language);
  await page.reload();
});

// ── register, sign in, deposit and play ─────────────────────────────────────────────────────────
Given("brand {string} running the modules {string}, {string} and {string} with a payment provider stub", async ({ sites, page }, brand: string, a: string, b: string, c: string) => {
  await sites.start({ brand, modules: [a, b, c], balance: { real: "0.00", bonus: "0.00" } });
  // the provider's page is the stub's: opened in a tab and answered here, never on the network
  await page.context().route("https://psp.example/**", (r) => r.fulfill({ status: 200, contentType: "text/html", body: "<p>stub</p>" }));
});
When("a visitor registers, verifies their email address and signs in at the reference site", async ({ page, sites }) => {
  const email = "zoe@example.com";
  const password = "a long enough password 7";
  await page.goto(`${sites.current.url}/register`);
  await page.getByLabel(/adresse e-mail|email address/i).fill(email);
  await page.getByLabel(/mot de passe|password/i).fill(password);
  await page.getByRole("button", { name: /créer mon compte|create my account/i }).click();
  await expect(page.getByText(email)).toBeVisible();
  // the link players mails: `verify-<player>` is the doubles' token
  const player = sites.current.doubles.brand.players.find((p) => p.email === email)!;
  await page.goto(`${sites.current.url}/verify-email?token=verify-${player.playerId}`);
  await expect(page.getByText(/est confirmée|is confirmed/)).toBeVisible();
  await page.goto(`${sites.current.url}/sign-in`);
  await page.getByLabel(/adresse e-mail|email address/i).fill(email);
  await page.getByLabel(/mot de passe|password/i).fill(password);
  await page.locator("form").getByRole("button", { name: /^(se connecter|sign in)$/i }).click();
  await expect(page.getByRole("button", { name: /déconnexion|sign out/i })).toBeVisible();
});
When("they deposit {string} minor units of {string}", async ({ page, sites }, minor: string, currency: string) => {
  const major = (Number(minor) / 100).toString();
  await page.getByRole("button", { name: /^(déposer|deposit)$/i }).first().click();
  const sheet = page.getByRole("dialog");
  await sheet.getByLabel(/autre montant|another amount/i).fill(major);
  await sheet.getByRole("button", { name: /continuer|continue/i }).click();
  await sheet.getByRole("button", { name: "Carte" }).click();
  await expect(sheet.getByText(/quelques informations|a few details/i)).toBeVisible();
  const values: Record<string, string> = { telephone: "+594694000000", téléphone: "+594694000000", "first name": "Zoé", prénom: "Zoé", "last name": "Martin", nom: "Martin" };
  for (const field of await sheet.getByRole("textbox").all()) {
    const label = ((await field.getAttribute("aria-label")) ?? (await field.evaluate((e) => e.closest("label")?.textContent ?? ""))).trim().toLowerCase();
    await field.fill(values[label] ?? "Cayenne");
  }
  const popup = page.waitForEvent("popup");
  await sheet.getByRole("button", { name: /payer|pay/i }).click();
  await (await popup).close();
  // the provider stub settles the payment, and the wallet credits it
  const brand = sites.current.doubles.brand;
  const payment = [...brand.payments.values()].at(-1)!;
  expect(payment.amountCents).toBe(Number(minor));
  expect(payment.currency).toBe(currency);
  payment.status = "COMPLETED";
  brand.balance.real = (Number(minor) / 100).toFixed(2);
  await expect(sheet.getByRole("status")).toHaveText(/reçu|received/, { timeout: 15_000 });
  await sheet.getByRole("button", { name: /fermer|close/i }).click();
});
When("they pick a game", async ({ page }) => {
  await page.getByRole("button", { name: /^(jouer|play)$/i }).first().click();
});
Then("the player's real balance is {string} minor units of {string}", async ({ page }, minor: string, currency: string) => {
  // written as the page writes it, in the page's own language
  const language = (await page.locator("html").getAttribute("lang")) ?? "fr";
  const shown = new Intl.NumberFormat(language === "en" ? "en-GB" : language, { style: "currency", currency }).format(Number(minor) / 100);
  await expect(page.getByRole("banner")).toContainText(shown);
});
Then("the play behaviour stands at playing", async ({ page }) => {
  await expect(page.locator("iframe[src^='https://games.example/']")).toBeVisible();
});

// ── what the pages call ─────────────────────────────────────────────────────────────────────────
let found: string[] = [];
When("the reference site's pages are checked for what they call", () => {
  found = forbidden(path.join(root, "src"));
});
Then("its pages reach the brand only through behaviours of the player SDK", () => {
  expect(found).toEqual([]);
});

// ── a rollout ───────────────────────────────────────────────────────────────────────────────────
let builds: { old: string; next: string } | undefined;
Given("the reference site is being replaced by a new build", () => {
  // two builds that differ, as two releases would: the old one as `npm run build` makes it, the new one unminified
  const old = path.join(root, "dist");
  const next = mkdtempSync(path.join(tmpdir(), "player-site-next-"));
  execFileSync("npx", ["vite", "build", "--outDir", next, "--emptyOutDir", "--minify", "false", "--logLevel", "error"], { cwd: root });
  builds = { old, next };
});
When("a page of the old build asks for one of its files", async ({ sites, request, $testInfo }) => {
  const { old, next } = builds!;
  const oldSite = await sites.start({}, old);
  const newSite = await sites.start({}, next);
  const page = await (await request.get(oldSite.url)).text();
  const file = /\/assets\/[^"]+\.js/.exec(page)![0];
  // the new build names its files by their own content, so it never answers the old name with other content
  expect(readdirSync(path.join(next, "assets"))).not.toContain(path.basename(file));
  expect((await request.get(newSite.url + file)).status()).toBe(404);
  // the old instances serve until the rollout ends: the old page's file is found there, kept for good
  ($testInfo as unknown as { answer: { status: number; cache: string; body: string; own: string } }).answer = await (async () => {
    const r = await request.get(oldSite.url + file);
    return { status: r.status(), cache: r.headers()["cache-control"] ?? "", body: await r.text(), own: readFileSync(path.join(old, file), "utf8") };
  })();
  // and each instance's first page is never kept, so the next load takes the new build
  expect((await request.get(newSite.url)).headers()["cache-control"]).toBe("no-cache");
});
Then("the file is found", ({ $testInfo }) => {
  const answer = ($testInfo as unknown as { answer: { status: number; cache: string; body: string; own: string } }).answer;
  expect(answer.status).toBe(200);
  expect(answer.cache).toContain("immutable");
  expect(answer.body).toBe(answer.own);
});

// ── without the casino ──────────────────────────────────────────────────────────────────────────
Given("a brand {string} running the modules {string} and {string} and not the module {string}", async ({ sites }, brand: string, a: string, b: string, without: string) => {
  await sites.start({ brand, modules: [a, b] });
  expect([a, b]).not.toContain(without);
});
When("a visitor opens the brand's player site address", async ({ page, sites }) => {
  await page.goto(sites.current.url);
});
Then("the reference site answers", async ({ page }) => {
  await expect(page.getByRole("link", { name: "Guyanabet" })).toBeVisible();
});
Then("the play behaviour is not offered", async ({ page }) => {
  await expect(page.getByRole("button", { name: /^(jouer|play|essayer|try)$/i })).toHaveCount(0);
  expect(await page.evaluate(() => fetch("/api/site").then((r) => r.json()))).toMatchObject({ modules: ["core", "player-site"] });
});

// ── the render: the modules tool's own suite binds it (RenderSuite, T049); playwright.config.ts leaves it out ──
/* eslint-disable @typescript-eslint/no-unused-vars -- playwright-bdd counts a step function's parameters against its expression */
const theTools = () => {
  throw new Error("the render scenario is bound by tools/modules' RenderSuite, not in a browser");
};
When("the modules tool renders brand {string} with the modules {string}, {string}, {string} and {string}", ({}, _brand: string, _a: string, _b: string, _c: string, _d: string) => theTools());
Then("brand {string} runs the services of the modules {string}, {string}, {string}, {string} and {string}", ({}, _brand: string, _a: string, _b: string, _c: string, _d: string, _e: string) => theTools());
Then("brand {string} takes no real money", ({}, _brand: string) => theTools());

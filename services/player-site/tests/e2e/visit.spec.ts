// reference-site.feature's end-to-end scenario against a running brand, in a browser, timed (spec 027
// SC-007, T051): a visitor registers, verifies, signs in, deposits with the payment provider stub and
// is playing a game within 3 minutes, in French. Nothing is a double: the site, the brand's services,
// the shared payments and the provider stub are the ones `local/guyanabet` runs (or an installation's).
//
//   PLAYER_SITE_URL  the site (default http://localhost:3310)
//   PLAYERS_URL      players, for the verification link an email would carry (default http://127.0.0.1:9202)
//   STAFF_TOKEN      a brand operator's token for that link (`local/guyanabet/operator-token.sh`)
import { readFileSync } from "node:fs";
import path from "node:path";
import { expect, test } from "@playwright/test";

const site = (process.env.PLAYER_SITE_URL ?? "http://localhost:3310").replace(/\/+$/, "");
const players = (process.env.PLAYERS_URL ?? "http://127.0.0.1:9202").replace(/\/+$/, "");
const brand = process.env.PLAYER_SITE_BRAND ?? "guyanabet";
const staffToken = () =>
  process.env.STAFF_TOKEN ??
  readFileSync(path.resolve(import.meta.dirname, `../../../../local/${brand}/.ankka/local/staff-token`), "utf8").trim();

test("a visitor registers, signs in, deposits and plays at the reference site, within 3 minutes, in French", async ({ page }) => {
  test.setTimeout(240_000);
  const started = Date.now();
  const email = `visite-${Date.now()}@${brand}.test`;
  const password = "une phrase assez longue 7";

  // registers
  await page.goto(`${site}/register`);
  await expect(page.locator("html")).toHaveAttribute("lang", "fr");
  await page.getByLabel(/adresse e-mail/i).fill(email);
  await page.getByLabel(/mot de passe/i).fill(password);
  // On one machine the address is private, so no country is found and the form asks for one.
  const country = page.locator("form select");
  if (await country.waitFor({ timeout: 5_000 }).then(() => true, () => false)) await country.selectOption("GF");
  await page.getByRole("button", { name: /créer mon compte/i }).click();
  await expect(page.getByText(email)).toBeVisible();

  // verifies: the link players would mail, as an operator reads it on a brand without real money
  const registered = await page.evaluate(() => document.body.innerText);
  expect(registered).toContain(email);
  // The operator's list is a view: it holds the player a moment after the registration.
  let list: { playerId: string }[] = [];
  for (const deadline = Date.now() + 20_000; list.length === 0 && Date.now() < deadline; await page.waitForTimeout(500)) {
    const search = await fetch(`${players}/v1/casino/backoffice/${brand}/players?search=${encodeURIComponent(email)}`,
      { headers: { authorization: `Bearer ${staffToken()}` } });
    list = ((await search.json()) as { items?: { playerId: string }[] }).items ?? [];
  }
  expect(list.length, "the operator finds the new player").toBeGreaterThan(0);
  const link = await fetch(`${players}/v1/casino/backoffice/${brand}/players/${list[0]!.playerId}/verification-link`,
    { headers: { authorization: `Bearer ${staffToken()}` } });
  expect(link.status).toBe(200);
  await page.goto(((await link.json()) as { link: string }).link);
  await expect(page.getByText(/est confirmée/)).toBeVisible();

  // signs in
  await page.goto(`${site}/sign-in`);
  await page.getByLabel(/adresse e-mail/i).fill(email);
  await page.getByLabel(/mot de passe/i).fill(password);
  await page.locator("form").getByRole("button", { name: /^se connecter$/i }).click();
  await expect(page.getByRole("button", { name: /déconnexion/i })).toBeVisible();

  // deposits 50,00 € with the provider stub, whose checkout opens in a window of its own
  await page.getByRole("button", { name: /^déposer$/i }).first().click();
  const sheet = page.getByRole("dialog");
  await sheet.getByLabel(/autre montant/i).fill("50");
  await sheet.getByRole("button", { name: /continuer/i }).click();
  await sheet.getByRole("button").filter({ hasText: /dreamzpay/i }).first().click();
  if (await sheet.getByText(/quelques informations/i).waitFor({ timeout: 10_000 }).then(() => true, () => false)) {
    for (const field of await sheet.getByRole("textbox").all()) {
      const label = ((await field.getAttribute("aria-label")) ?? (await field.evaluate((e) => e.closest("label")?.textContent ?? ""))).trim().toLowerCase();
      await field.fill(/téléphone|phone/.test(label) ? "+594694000000" : /prénom/.test(label) ? "Zoé" : /nom/.test(label) ? "Martin" : "Cayenne");
    }
  }
  const popup = page.waitForEvent("popup");
  await sheet.getByRole("button", { name: /payer/i }).click();
  const provider = await popup;
  await provider.waitForURL(/:18103\//, { timeout: 30_000 }); // the relay page posted the player to the stub
  await expect(sheet.getByRole("status")).toHaveText(/reçu/, { timeout: 60_000 });
  await provider.close();
  await sheet.getByRole("button", { name: /fermer/i }).click();

  // the real balance, as the page writes it
  await expect(page.getByRole("banner")).toContainText(new Intl.NumberFormat("fr", { style: "currency", currency: "EUR" }).format(50));

  // picks a game, and is playing it
  await page.goto(`${site}/`);
  await page.getByRole("button", { name: /^jouer$/i }).first().click();
  await expect(page.locator("iframe").first()).toBeVisible({ timeout: 30_000 });
  const src = await page.locator("iframe").first().getAttribute("src");
  expect(src).toMatch(/^https?:\/\//);

  const seconds = (Date.now() - started) / 1000;
  console.log(`SC-007: playing after ${seconds.toFixed(1)} s`);
  expect(seconds).toBeLessThan(180);
});

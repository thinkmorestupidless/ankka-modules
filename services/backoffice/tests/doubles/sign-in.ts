// Signing a browser in through the double realm: the operator is put in the realm's cookie, and the
// page's own `/auth/sign-in` runs the code flow to the end, so the `__Host-bo` cookie is the server's.
import type { Page } from "@playwright/test";
import type { DoublesClient } from "./client.ts";
import { BackofficeUrl, DoublesUrl } from "./env.ts";
import { IdentityCookie, identityValue, type Identity } from "./realm.ts";
import type { Preferences } from "../../src/server/session.ts";

export async function signIn(page: Page, doubles: DoublesClient, who: Identity, opts: { preferences?: Partial<Preferences>; to?: string; at?: string } = {}): Promise<void> {
  const BackofficeUrl_ = opts.at ?? BackofficeUrl;
  if (opts.preferences) await doubles.preferences(who.sub, opts.preferences);
  await page.context().addCookies([{ name: IdentityCookie, value: identityValue(who), url: DoublesUrl }]);
  const to = opts.to ?? "/";
  await page.goto(`${BackofficeUrl_}/auth/sign-in?return=${encodeURIComponent(to)}`);
  const landed = new URL(page.url());
  if (landed.origin !== BackofficeUrl_ || landed.pathname + landed.search !== to)
    throw new Error(`signing in as ${who.sub} ended at ${page.url()}, not ${to}: ${(await page.textContent("body"))?.slice(0, 200)}`);
}

/** Whether the browser holds the server's sign-in cookie (it cannot be read by the page's scripts). */
export async function signedIn(page: Page): Promise<boolean> {
  return (await page.context().cookies(BackofficeUrl)).some((c) => c.name === "__Host-bo");
}

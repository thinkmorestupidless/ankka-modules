// The backoffice in a browser for the integration suites: Chromium signed in through the realm's own
// form, every request it makes recorded. The local installation's certificates are its own CA's, which
// Chromium does not read from NODE_EXTRA_CA_CERTS, so this context accepts them; it is used only against
// a local installation.
import { chromium, type Browser, type BrowserContext, type Page } from "@playwright/test";
import type { Credentials, Installation } from "./support.ts";

export interface Seen {
  url: string;
  method: string;
  status?: number;
  resourceType: string;
}

export interface Signed {
  browser: Browser;
  context: BrowserContext;
  page: Page;
  /** Every request since the sign-in finished (the realm's pages before it are not the session's). */
  seen: Seen[];
  /** Every request, the sign-in's included. */
  all: Seen[];
  close(): Promise<void>;
}

export async function openSignedIn(inst: Installation, credentials: Credentials, viewport = { width: 1440, height: 900 }): Promise<Signed> {
  const browser = await chromium.launch();
  const context = await browser.newContext({ ignoreHTTPSErrors: true, viewport });
  const page = await context.newPage();
  const all: Seen[] = [];
  const seen: Seen[] = [];
  let signedIn = false;
  page.on("request", (r) => {
    const s: Seen = { url: r.url(), method: r.method(), resourceType: r.resourceType() };
    all.push(s);
    if (signedIn) seen.push(s);
  });
  page.on("response", (r) => {
    const s = [...all].reverse().find((x) => x.url === r.url() && x.status === undefined);
    if (s) s.status = r.status();
  });
  await page.goto(`${inst.backoffice}/auth/sign-in?return=/`);
  await page.locator("#username").fill(credentials.username);
  await page.locator("#password").fill(credentials.password);
  await Promise.all([page.waitForURL((u) => u.toString().startsWith(inst.backoffice) && !u.pathname.startsWith("/auth/"), { timeout: 60_000 }), page.locator("#kc-login").click()]);
  signedIn = true;
  await settle(page);
  return {
    browser,
    context,
    page,
    seen,
    all,
    close: async () => {
      await context.close();
      await browser.close();
    },
  };
}

/** Each amount the page shows: its text and its currency (`components/money.tsx` marks them). */
export async function amountsShown(page: Page): Promise<{ text: string; currency: string }[]> {
  return page.locator("[data-figure][data-currency]").evaluateAll((els) =>
    els.map((e) => ({ text: (e.textContent ?? "").trim(), currency: e.getAttribute("data-currency") ?? "" })),
  );
}

/**
 * The page has loaded and its reads have answered. Not `networkidle`: the page keeps `/api/events` open,
 * so the network is never idle. A region still loading says so with `aria-busy`.
 */
export async function settle(page: Page, timeout = 20_000): Promise<void> {
  await page.waitForLoadState("load");
  await page.waitForFunction(() => document.querySelector("main") !== null && document.querySelector('[aria-busy="true"]') === null, undefined, { timeout }).catch(() => undefined);
  await page.waitForTimeout(500);
}

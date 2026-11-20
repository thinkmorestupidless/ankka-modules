// Records the original's operator screens (spec 030 T131, research R19): one capture per screen per
// role, from the casino backoffice (`casino/frontend`, which also mounts the payments pages under
// `/payments/*`) and the standalone payments backoffice (`payments/frontend`), against the original
// on kind. Read-only: it opens screens and never presses an action. README.md says how to run it.
//
//   node tests/original/backoffice-screens/record.ts
//
// Env: PERSONAS (a JSON file, [{role, username, password}], never committed), OUT (default
// `captures/` beside this file, git-ignored), CASINO_URL (https://casino.eitheror.local),
// KEYCLOAK_URL (https://auth.eitheror.local), PAYMENTS_UI_DIR (the payments build taken from its image; the standalone payments app is skipped
// without it), MERCHANT_URL (http://localhost:8086), GRAPHQL_URL (http://localhost:8082),
// INDEX (default `index.json` beside this file, committed), ORIGINAL_COMMIT (written into the index), ONLY (a comma list of screen ids).
import { createHash } from "node:crypto";
import { createRequire } from "node:module";
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
// Playwright comes from the backoffice's own install; this directory has no package of its own.
const require = createRequire(path.join(here, "../../../services/backoffice/package.json"));
const { chromium } = require("playwright") as typeof import("playwright");
type Page = import("playwright").Page;
type BrowserContext = import("playwright").BrowserContext;

const CasinoUrl = process.env.CASINO_URL ?? "https://casino.eitheror.local";
// The payments app is off on kind (`frontend.backoffice.enabled: false`), so `payments.eitheror.local`
// answers 502; its build, taken from its image, is served at that host (a redirect the realm's
// `payments-backoffice` client admits) by the browser context, not by nginx.
const PaymentsUrl = "https://payments.eitheror.local";
const PaymentsDir = process.env.PAYMENTS_UI_DIR;
const MerchantUrl = process.env.MERCHANT_URL ?? "http://localhost:8086";
const GraphqlUrl = process.env.GRAPHQL_URL ?? "http://localhost:8082";
const KeycloakUrl = process.env.KEYCLOAK_URL ?? "https://auth.eitheror.local";
const Out = process.env.OUT ?? path.join(here, "captures");
const Index = process.env.INDEX ?? path.join(here, "index.json");
const Only = process.env.ONLY?.split(",").filter(Boolean);
const Brand = process.env.BRAND ?? "goldvibe";
const Viewport = { width: 1440, height: 900 };

interface Persona {
  role: string;
  username: string;
  password: string;
}

/** A screen: an app, a route, or a route found by opening a list and following its first record. */
interface Screen {
  id: string;
  app: "casino" | "payments";
  component: string;
  route?: string;
  /** open `from` (and its `tab`), click the first `click`, and keep the route it lands on when it matches `lands` */
  follow?: { from: string; tab?: string; via?: { click: string; lands: RegExp }; click: string; lands: RegExp };
  /** a route that needs no sign-in */
  public?: boolean;
  /** the screen's tabs, each captured as `<id>.<tab>` after the screen itself */
  tabs?: string[];
}

const c = (id: string, route: string, component: string): Screen => ({ id: `casino.${id}`, app: "casino", route, component });
const p = (id: string, route: string, component: string): Screen => ({ id: `payments.${id}`, app: "payments", route, component });
const row = "tbody tr";

export const screens: Screen[] = [
  // casino/frontend/src/App.tsx, in its order
  c("dashboard", "/dashboard", "DashboardPage"),
  c("players", "/players", "PlayersPage"),
  {
    id: "casino.player",
    app: "casino",
    component: "PlayerDetailPage",
    follow: { from: "/players", click: row, lands: /\/players\/[^/]+$/ },
    tabs: ["Timeline", "Responsible Gambling", "KYC", "CRM", "Withdrawals", "Bets", "Bonuses", "Devices"],
  },
  c("wallets", "/wallets", "WalletsPage"),
  c("withdrawals", "/withdrawals", "WithdrawalRequestsPage"),
  { ...c("sessions", "/sessions", "SessionsPage"), tabs: ["Game Sessions"] },
  { id: "casino.player-session", app: "casino", component: "PlayerSessionDetailPage", follow: { from: "/sessions", click: row, lands: /\/sessions\/player\/[^/]+$/ } },
  {
    id: "casino.game-session-timeline",
    app: "casino",
    component: "GameSessionTimelinePage",
    // reached from a player session's game sessions
    follow: { from: "/sessions", via: { click: row, lands: /\/sessions\/player\/[^/]+$/ }, click: "main a[href*='/timeline']", lands: /\/sessions\/game\/[^/]+\/timeline$/ },
  },
  c("transaction-search", "/transactions/search", "TransactionSearchPage"),
  c("bonuses", "/bonuses", "BonusesPage"),
  { ...c("bonus-offers", "/bonus-offers", "BonusOffersPage"), tabs: ["System"] },
  c("rg", "/rg", "RGCasesPage"),
  c("fraud-cases", "/fraud-cases", "FraudCasesPage"),
  c("team", "/team", "payments TeamPage"),
  c("providers", "/providers", "ProvidersPage"),
  c("game-catalog", "/game-catalog", "GameCatalogPage"),
  { ...c("reporting", "/reporting", "ReportingPage"), tabs: ["Players", "Bonus", "Transactions", "PSP / Payments"] },
  c("brand-config", "/brand-config", "BrandConfigPage"),
  c("kyc-policy", "/kyc-policy", "KycPolicyPage"),
  ...["banners", "promotions", "categories", "pages", "media", "homepage-locales", "translation-languages", "navigation", "site-config", "footer-config"].map((s) =>
    c(`content.${s}`, `/casino-content/${s}`, "casino-content (Strapi; dropped, FR-043)"),
  ),
  { ...c("payments.transactions", "/payments/transactions", "payments TransactionsPage"), tabs: ["Pay-out"] },
  { id: "casino.payments.transaction", app: "casino", component: "payments TransactionDetailPage", follow: { from: "/payments/transactions", click: row, lands: /\/payments\/transactions\/[^/]+$/ } },
  { id: "casino.payments.withdrawal", app: "casino", component: "payments WithdrawalDetailPage", follow: { from: "/payments/transactions", tab: "Pay-out", click: row, lands: /\/payments\/withdrawals\/[^/]+$/ } },
  { ...c("payments.hierarchy", "/payments/hierarchy", "payments HierarchyPage"), tabs: ["Companies", "Sub-brands"] },
  { id: "casino.payments.merchant", app: "casino", component: "payments MerchantDetailPage", follow: { from: "/payments/hierarchy", tab: "Sub-brands", click: row, lands: /\/merchants\/[^/]+$/ } },
  { id: "casino.brand-crm-config", app: "casino", component: "payments BrandCrmConfigPage", follow: { from: "/payments/hierarchy", click: "main a[href*='/crm-config'], main button:has-text('CRM Config')", lands: /\/brands\/[^/]+\/crm-config$/ } },
  c("payments.payouts", "/payments/payouts", "payments PayoutsPage"),
  c("payments.routing-rules", "/payments/routing-rules", "payments RoutingRulesPage"),
  c("payments.psps", "/payments/psps", "payments PSPsPage"),
  c("payments.risk", "/payments/risk", "payments RiskPage (dropped)"),
  c("payments.analytics", "/payments/analytics", "payments AnalyticsPage"),
  c("affiliates.dashboard", "/affiliates/dashboard", "ProgrammeDashboardPage"),
  c("affiliates.plans", "/affiliates/plans", "CommissionPlansPage"),
  c("affiliates.accounts", "/affiliates/accounts", "AffiliatesPage"),
  {
    id: "casino.affiliates.account",
    app: "casino",
    component: "AffiliateDetailPage",
    follow: { from: "/affiliates/accounts", click: row, lands: /\/affiliates\/accounts\/[^/]+$/ },
    tabs: ["Tracking Links", "Revenue History", "Players", "Network", "Notes", "Audit Trail"],
  },
  c("affiliates.links", "/affiliates/links", "TrackingLinksPage"),
  c("affiliates.reporting", "/affiliates/reporting", "AffiliateReportingPage"),
  c("affiliates.creatives", "/affiliates/creatives", "OperatorCreativesPage"),
  c("affiliates.payouts", "/affiliates/payouts", "AffiliatePayoutsPage"),
  c("affiliates.fraud", "/affiliates/fraud", "FraudFlagsPage"),
  c("affiliates.settings", "/affiliates/settings", "AffiliateSettingsPage"),
  c("affiliates.messaging", "/affiliates/messaging", "AffiliateMessagingPage"),
  c("affiliates.managers", "/affiliates/managers", "ManagersPage"),
  { ...c("accept-invitation", "/accept-invitation", "AcceptInvitationPage"), public: true },
  { ...c("reset-password", "/reset-password", "AcceptInvitationPage (reset)"), public: true },
  { ...c("not-authorised", "/not-authorised", "NotAuthorisedPage"), public: true },
  // payments/frontend/src/App.tsx: the same page components in the payments shell
  p("transactions", "/transactions", "TransactionsPage"),
  { ...p("hierarchy", "/hierarchy", "HierarchyPage"), tabs: ["Companies", "Sub-brands"] },
  { id: "payments.transaction", app: "payments", component: "TransactionDetailPage", follow: { from: "/transactions", click: row, lands: /\/transactions\/[^/]+$/ } },
  { id: "payments.merchant", app: "payments", component: "MerchantDetailPage", follow: { from: "/hierarchy", tab: "Sub-brands", click: row, lands: /\/merchants\/[^/]+$/ } },
  { id: "payments.brand-crm-config", app: "payments", component: "BrandCrmConfigPage", follow: { from: "/hierarchy", click: "main a[href*='/crm-config'], main button:has-text('CRM Config')", lands: /\/brands\/[^/]+\/crm-config$/ } },
  p("payouts", "/payouts", "PayoutsPage"),
  p("routing-rules", "/routing-rules", "RoutingRulesPage"),
  p("psps", "/psps", "PSPsPage"),
  p("risk", "/risk", "RiskPage (dropped)"),
  p("analytics", "/analytics", "AnalyticsPage"),
  p("team", "/team", "TeamPage"),
  { ...p("accept-invitation", "/accept-invitation", "AcceptInvitationPage"), public: true },
];

interface Entry {
  screen: string;
  app: string;
  component: string;
  role: string;
  route: string | null;
  url: string | null;
  file: string | null;
  sha256: string | null;
  outcome: "captured" | "refused" | "not reached";
  note?: string;
}

const files = new Map<string, string>([[".js", "text/javascript"], [".css", "text/css"], [".html", "text/html"], [".svg", "image/svg+xml"], [".png", "image/png"], [".woff2", "font/woff2"], [".ico", "image/x-icon"]]);

/** Serves the payments build at `PaymentsUrl`, and its `/v1`, `/psp-api` and `/graphql` as its nginx would. */
async function servePayments(context: BrowserContext): Promise<void> {
  if (!PaymentsDir) return;
  // The browser follows the realm's redirect back to the app without asking the context's routes,
  // so it would reach the real host (502). The realm's answers are fetched here instead, and a
  // redirect to the app is turned into a page that goes there, a navigation the routes do serve.
  await context.route((u) => u.origin === KeycloakUrl, async (route) => {
    const res = await route.fetch({ maxRedirects: 0 });
    const location = res.headers()["location"];
    if (res.status() >= 300 && res.status() < 400 && location?.startsWith(PaymentsUrl)) {
      const headers = { ...res.headers(), "content-type": "text/html" };
      delete headers["location"];
      return route.fulfill({ status: 200, headers, body: `<script>location.replace(${JSON.stringify(location)})</script>` });
    }
    return route.fulfill({ response: res });
  });
  await context.route((u) => u.origin === PaymentsUrl, async (route) => {
    const req = route.request();
    const url = new URL(req.url());
    const upstream = url.pathname.startsWith("/v1/")
      ? `${MerchantUrl}${url.pathname}${url.search}`
      : url.pathname.startsWith("/psp-api/")
        ? `${MerchantUrl}${url.pathname.slice("/psp-api".length)}${url.search}`
        : url.pathname.startsWith("/graphql")
          ? `${GraphqlUrl}/graphql${url.search}`
          : undefined;
    if (upstream) {
      // read-only: the recording opens screens, it never writes
      if (!["GET", "HEAD", "OPTIONS"].includes(req.method()) && !url.pathname.startsWith("/graphql"))
        return route.fulfill({ status: 405, body: "the screen recorder sends no writes" });
      const res = await route.fetch({ url: upstream });
      return route.fulfill({ response: res });
    }
    if (url.pathname === "/runtime-env.js") return route.fulfill({ contentType: "text/javascript", body: `window.__ENV = { FARO_URL: "", CASINO_URL: "${CasinoUrl}" };` });
    let file = path.join(PaymentsDir, decodeURIComponent(url.pathname));
    if (!file.startsWith(PaymentsDir) || !existsSync(file) || url.pathname === "/") file = path.join(PaymentsDir, "index.html");
    if (existsSync(file) && !file.endsWith("/")) {
      try {
        // the build names Keycloak as `http://localhost:8180`, whose sign-in form posts to the realm's
        // own hostname and loses its cookie there; it is pointed at that hostname, as the casino's is
        const body = path.extname(file) === ".js" ? readFileSync(file, "utf8").replaceAll("http://localhost:8180", KeycloakUrl) : readFileSync(file);
        return route.fulfill({ contentType: files.get(path.extname(file)) ?? "application/octet-stream", body });
      } catch {
        /* a directory: the app's own route */
      }
    }
    return route.fulfill({ contentType: "text/html", body: readFileSync(path.join(PaymentsDir, "index.html")) });
  });
}

function base(app: Screen["app"]): string {
  return app === "casino" ? CasinoUrl : PaymentsUrl;
}

/**
 * Opens a route of the app. The payments app keeps its session only in memory and signs in again
 * on every load, landing on `/`, so its routes are opened inside the running app, as its links do.
 */
async function go(page: Page, app: Screen["app"], route: string): Promise<void> {
  if (app === "payments" && page.url().startsWith(PaymentsUrl)) {
    await page.evaluate((r) => {
      history.pushState({}, "", r);
      dispatchEvent(new PopStateEvent("popstate"));
    }, route);
    return;
  }
  await page.goto(`${base(app)}${route}`);
}

interface Twin {
  twinId: string;
  properties: Record<string, unknown>;
}

/**
 * Signs in through the realm's own form, as an operator does, from the app's root, then picks the
 * brand in the scope selector's place: a platform operator with no working scope sees no brand's
 * records (`useScopeBrandName`), so the scope is set to `Brand`'s sub-brand, company and holding,
 * read from the hierarchy the app itself loads.
 */
async function signIn(page: Page, app: Screen["app"], who: Persona): Promise<void> {
  const twins: Record<string, Twin[]> = {};
  const listen = async (r: import("playwright").Response): Promise<void> => {
    if (!r.url().endsWith("/graphql")) return;
    try {
      const concept = (JSON.parse(r.request().postData() ?? "{}") as { variables?: { conceptId?: string } }).variables?.conceptId;
      const data = (await r.json()) as { data?: { twins?: Twin[] } };
      if (concept && data.data?.twins) twins[concept] = data.data.twins;
    } catch {
      /* not the hierarchy */
    }
  };
  page.on("response", listen);
  await page.goto(`${base(app)}/`, { waitUntil: "domcontentloaded" });
  const user = page.locator("#username");
  await user.waitFor({ timeout: 30_000 });
  await user.fill(who.username);
  await page.locator("#password").fill(who.password);
  await page.locator("#kc-login, [type=submit]").first().click();
  await page.waitForURL((u) => u.toString().startsWith(base(app)), { timeout: 30_000 });
  await settle(page);
  page.off("response", listen);
  const id = (t: Twin | undefined): string | null => (t ? String(t.properties.id ?? t.twinId.split("/").pop()) : null);
  const sub = twins["sub-brand"]?.find((t) => t.properties.name === Brand);
  const company = twins["company"]?.find((t) => id(t) === sub?.properties.company_id) ?? twins["company"]?.find((t) => t.properties.name === Brand);
  const holding = twins["holding"]?.find((t) => id(t) === company?.properties.holding_id) ?? twins["holding"]?.find((t) => t.properties.name === Brand);
  if (!sub) {
    console.log(`  (no sub-brand named ${Brand} in the hierarchy the app loaded; the scope stays as the token gives it)`);
    return;
  }
  const scope = {
    holdingId: id(holding),
    holdingName: holding?.properties.name ?? null,
    companyId: id(company),
    companyName: company?.properties.name ?? null,
    subBrandId: id(sub),
    subBrandName: sub.properties.name,
  };
  await page.evaluate((s) => localStorage.setItem("eitheror_working_scope", JSON.stringify(s)), scope);
  // the payments app signs in again on a load, and reads the scope as it starts
  if (app === "payments") await page.goto(`${PaymentsUrl}/`);
  else await page.reload();
  await settle(page);
}

async function settle(page: Page): Promise<void> {
  await page.waitForLoadState("networkidle", { timeout: 20_000 }).catch(() => undefined);
  // the original shows spinners and skeletons while its reads are in flight
  await page
    .waitForFunction(() => !document.querySelector(".animate-spin, .animate-pulse, [aria-busy=true]"), undefined, { timeout: 60_000 })
    .catch(() => undefined);
  await page.waitForTimeout(500);
}

async function capture(page: Page, screen: Screen, who: Persona): Promise<Entry> {
  const entry: Entry = { screen: screen.id, app: screen.app, component: screen.component, role: who.role, route: screen.route ?? null, url: null, file: null, sha256: null, outcome: "not reached" };
  try {
    if (screen.follow) {
      await go(page, screen.app, screen.follow.from);
      await settle(page);
      if (screen.follow.tab && !(await chooseTab(page, screen.follow.tab))) return { ...entry, note: `no tab "${screen.follow.tab}" on ${screen.follow.from}` };
      if (screen.follow.tab) await settle(page);
      if (screen.follow.via) {
        const via = page.locator(screen.follow.via.click).first();
        if ((await via.count()) === 0) return { ...entry, note: `no record to follow on ${screen.follow.from}` };
        await via.click({ timeout: 5_000 });
        await page.waitForURL(screen.follow.via.lands, { timeout: 10_000 }).catch(() => undefined);
        await settle(page);
      }
      const target = page.locator(screen.follow.click).first();
      if ((await target.count()) === 0) return { ...entry, note: `no record to follow on ${screen.follow.from}` };
      await target.click({ timeout: 5_000 });
      await page.waitForURL(screen.follow.lands, { timeout: 10_000 }).catch(() => undefined);
      if (!screen.follow.lands.test(new URL(page.url()).pathname)) return { ...entry, note: `following ${screen.follow.click} on ${screen.follow.from} landed on ${new URL(page.url()).pathname}` };
    } else {
      await go(page, screen.app, screen.route!);
    }
    await settle(page);
    const landed = new URL(page.url());
    entry.url = landed.pathname + landed.search;
    if (screen.follow) entry.route = landed.pathname;
    const body = (await page.locator("body").innerText().catch(() => "")).slice(0, 4000);
    if (/not authori[sz]ed|access denied|you do not have permission|this is the operator backoffice/i.test(body) || landed.pathname === "/not-authorised") entry.outcome = "refused";
    else if (screen.route && !screen.follow && landed.pathname !== screen.route) {
      entry.outcome = "refused";
      entry.note = `redirected to ${landed.pathname}`;
    } else entry.outcome = "captured";
    return await shoot(page, entry, `${screen.id}--${who.role}.png`);
  } catch (e) {
    return { ...entry, note: (e as Error).message.split("\n")[0] };
  }
}

/** A tab of a captured screen: the screen's own url again, the tab chosen, then captured. */
async function captureTab(page: Page, screen: Screen, of: Entry, tab: string, who: Persona): Promise<Entry> {
  const id = `${screen.id}.${tab.toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "")}`;
  const entry: Entry = { ...of, screen: id, component: `${screen.component}, tab "${tab}"`, file: null, sha256: null, outcome: "not reached", note: undefined };
  try {
    await go(page, screen.app, of.url!);
    await settle(page);
    if (!(await chooseTab(page, tab))) return { ...entry, note: `no tab "${tab}"` };
    await settle(page);
    entry.outcome = "captured";
    return await shoot(page, entry, `${id}--${who.role}.png`);
  } catch (e) {
    return { ...entry, note: (e as Error).message.split("\n")[0] };
  }
}

/** Chooses a tab of the screen by its words, or by words it starts with (a tab may carry a count). */
async function chooseTab(page: Page, tab: string): Promise<boolean> {
  const main = page.locator("main");
  const starts = new RegExp(`^\\s*${tab.replace(/[.*+?^${}()|[\]\\/]/g, "\\$&")}`);
  const candidates = [
    main.getByRole("tab", { name: tab, exact: true }),
    main.getByRole("button", { name: tab, exact: true }),
    main.getByText(tab, { exact: true }),
    main.getByRole("tab", { name: starts }),
    main.getByRole("button", { name: starts }),
  ];
  for (const c of candidates) {
    if ((await c.count()) > 0) {
      await c.first().click({ timeout: 5_000 });
      return true;
    }
  }
  return false;
}

async function shoot(page: Page, entry: Entry, name: string): Promise<Entry> {
  // both shells scroll inside `main` under a screen-high frame; let the page grow so the capture
  // holds the whole screen, not the first 900 px of it
  await page.addStyleTag({ content: ".h-screen{height:auto!important;min-height:100vh} main,.overflow-hidden,.overflow-auto{overflow:visible!important}" }).catch(() => undefined);
  const png = await page.screenshot({ fullPage: true, animations: "disabled" });
  writeFileSync(path.join(Out, name), png);
  return { ...entry, file: name, sha256: createHash("sha256").update(png).digest("hex") };
}

async function main(): Promise<void> {
  const personasFile = process.env.PERSONAS;
  if (!personasFile) throw new Error("PERSONAS must name a JSON file of [{role, username, password}] (README.md)");
  const personas = JSON.parse(readFileSync(personasFile, "utf8")) as Persona[];
  mkdirSync(Out, { recursive: true });
  const chosen = screens.filter((s) => !Only || Only.includes(s.id)).filter((s) => s.app === "casino" || PaymentsDir);
  const browser = await chromium.launch();
  const entries: Entry[] = [];
  try {
    for (const who of personas) {
      for (const app of ["casino", "payments"] as const) {
        const mine = chosen.filter((s) => s.app === app);
        if (mine.length === 0) continue;
        // a public screen is captured once, signed out, under the role `anyone`
        const context = await browser.newContext({ viewport: Viewport, ignoreHTTPSErrors: true, colorScheme: "dark" });
        await servePayments(context);
        const page = await context.newPage();
        try {
          await signIn(page, app, who);
        } catch (e) {
          for (const s of mine.filter((s) => !s.public)) entries.push({ screen: s.id, app, component: s.component, role: who.role, route: s.route ?? null, url: null, file: null, sha256: null, outcome: "not reached", note: `sign-in failed: ${(e as Error).message.split("\n")[0]}` });
          await context.close();
          continue;
        }
        // an account with no business in the app (an affiliate) sees only the wrong-portal page,
        // whatever the route: captured once, as that screen, rather than once per route
        if (/this is the operator backoffice/i.test(await page.locator("body").innerText().catch(() => ""))) {
          const e = await shoot(page, { screen: `${app}.wrong-portal`, app, component: "WrongPortalPage", role: who.role, route: "/", url: new URL(page.url()).pathname, file: null, sha256: null, outcome: "refused", note: "every route shows this page" }, `${app}.wrong-portal--${who.role}.png`);
          entries.push(e);
          console.log(`${e.outcome.padEnd(11)} ${who.role.padEnd(18)} ${e.screen}  (${e.note})`);
          await context.close();
          continue;
        }
        for (const s of mine.filter((s) => !s.public)) {
          const e = await capture(page, s, who);
          entries.push(e);
          console.log(`${e.outcome.padEnd(11)} ${who.role.padEnd(18)} ${s.id}${e.note ? `  (${e.note})` : ""}`);
          if (e.outcome !== "captured" || !e.url) continue;
          for (const tab of s.tabs ?? []) {
            const t = await captureTab(page, s, e, tab, who);
            entries.push(t);
            console.log(`${t.outcome.padEnd(11)} ${who.role.padEnd(18)} ${t.screen}${t.note ? `  (${t.note})` : ""}`);
          }
        }
        await context.close();
      }
    }
    for (const s of chosen.filter((s) => s.public)) {
      const context = await browser.newContext({ viewport: Viewport, ignoreHTTPSErrors: true, colorScheme: "dark" });
      await servePayments(context);
      const page = await context.newPage();
      const e = await capture(page, s, { role: "anyone", username: "", password: "" });
      entries.push(e);
      console.log(`${e.outcome.padEnd(11)} ${"anyone".padEnd(18)} ${s.id}${e.note ? `  (${e.note})` : ""}`);
      await context.close();
    }
  } finally {
    await browser.close();
  }
  const index = {
    recordedAt: new Date().toISOString(),
    original: process.env.ORIGINAL_COMMIT ?? null,
    casinoUrl: CasinoUrl,
    viewport: Viewport,
    roles: personas.map((p) => p.role),
    captures: entries,
  };
  writeFileSync(Index, JSON.stringify(index, null, 2) + "\n");
  console.log(`${entries.filter((e) => e.outcome === "captured").length} captured, ${entries.filter((e) => e.outcome === "refused").length} refused, ${entries.filter((e) => e.outcome === "not reached").length} not reached; index at ${Index}`);
}

if (import.meta.url === `file://${process.argv[1]}`) await main();

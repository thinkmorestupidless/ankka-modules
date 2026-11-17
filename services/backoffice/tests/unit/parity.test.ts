// Parity (FR-030, SC-001): every capability of specs/030-backoffice/parity.md not dropped or moved is
// reached by a route the server serves and a page under src/web/areas/ that calls it. `Reached` below
// names them per row; a row with no entry, a route the server does not serve, a page that is not there
// or a page that does not call its route fails. And FR-044: nothing outside the backoffice module
// needs it, and the backoffice leaves the original alone.
import { execFileSync } from "node:child_process";
import { existsSync, readdirSync, readFileSync, statSync } from "node:fs";
import path from "node:path";
import { describe, expect, test } from "vitest";
import { nameOf } from "../../scripts/owners.ts";
import { table, type Row } from "../../src/server/routes.ts";

const service = path.join(import.meta.dirname, "../..");
const repo = path.join(service, "../..");
const web = path.join(service, "src/web");
const server = path.join(service, "src/server");

/**
 * A route is `"<METHOD> <service> <path>"`, a row of the table (`/api/s/<service><path>`), or
 * `"<METHOD> <path>"`, one the server serves itself (src/server/*.ts). A page is a file or directory
 * under src/web, at least one of them under `areas/`; together they call every route named.
 */
interface Reach {
  routes: string[];
  pages: string[];
}

const Reached: Record<string, Reach> = {
  // Shell and access
  "Sign in at the `eitheror` realm; wrong-portal page for affiliates": {
    routes: ["GET /auth/sign-in", "POST /auth/sign-out"],
    pages: ["areas/access/NotInBrand.tsx", "frame/session.ts"],
  },
  "Accept an operator invitation; reset a password": {
    routes: ["POST merchant /operators/{id}/resend-invitation", "POST merchant /operators/{id}/reset-password"],
    pages: ["areas/team/operators.tsx"],
  },
  "Holding, company, sub-brand picker; impersonation banner": {
    routes: ["GET merchant /holdings", "GET merchant /holdings/{id}/companies", "GET merchant /sub-brands"],
    pages: ["areas/payments/hierarchy.tsx"],
  },
  "Theme preference": { routes: ["PUT /api/preferences"], pages: ["areas/preferences/PreferencesPage.tsx"] },
  "Notice when a player comes online": {
    routes: ["GET engagement /presence", "GET /api/events"],
    pages: ["areas/outreach/online.tsx", "frame/events.ts"],
  },

  // Dashboard and reports
  "KPI tiles with history; trend; live activity; map; top games and players; reward summary; payment health": {
    routes: [
      "GET reporting /dashboard/kpis", "GET reporting /dashboard/trend", "GET reporting /dashboard/activity", "GET reporting /dashboard/geo",
      "GET reporting /dashboard/rankings", "GET reporting /dashboard/bonus-summary", "GET reporting /dashboard/payment-health",
    ],
    pages: ["areas/dashboard/DashboardPage.tsx", "areas/reports/read.ts"],
  },
  "Reports: GGR, players, rewards, transactions with export, payment providers": {
    routes: [
      "GET reporting /reports/ggr", "GET reporting /reports/players", "GET reporting /reports/bonus", "GET reporting /reports/transactions",
      "GET reporting /reports/psp", "POST /api/jobs",
    ],
    pages: ["areas/reports/ReportsPage.tsx", "areas/reports/read.ts", "areas/jobs/jobs.ts"],
  },
  "Payments analytics (captured volume, status counts)": { routes: ["GET merchant /cashier/summary"], pages: ["areas/payments/deposits.tsx"] },
  "Day-end and compliance exports": {
    routes: ["GET reporting /day-end/{day}", "GET reporting /compliance/rg-cases", "GET reporting /compliance/kyc-decisions", "POST /api/jobs"],
    pages: ["areas/reports/ReportsPage.tsx", "areas/reports/read.ts", "areas/jobs/jobs.ts"],
  },

  // Players
  "Player list: search, status, VIP": { routes: ["GET players /players"], pages: ["areas/players/list.tsx"] },
  "Player list facets: identity queue states, birthday window, potential VIP, online, country, currency, counts": {
    routes: ["GET players /players"],
    pages: ["areas/players/list.tsx"],
  },
  "Profile read and edit": {
    routes: ["GET players /players/{playerId}/profile", "PATCH players /players/{playerId}/profile"],
    // the profile is read through players/read.ts's profileQuery, which every screen shares
    pages: ["areas/players/profile.tsx", "areas/players/read.ts"],
  },
  "Correct date of birth with a reason": { routes: ["PATCH players /players/{playerId}/date-of-birth"], pages: ["areas/players/profile.tsx"] },
  "Notes: add, pin, unpin": {
    routes: [
      "GET players /players/{playerId}/notes", "POST players /players/{playerId}/notes",
      "PATCH players /players/{playerId}/notes/{noteId}/pin", "PATCH players /players/{playerId}/notes/{noteId}/unpin",
    ],
    pages: ["areas/players/notes.tsx"],
  },
  "Send message with merge tags and preview": {
    routes: ["POST players /players/{playerId}/messages", "GET players /merge-tags", "POST players /messages/preview"],
    pages: ["areas/players/messages.tsx"],
  },
  "Reset password": { routes: ["POST players /players/{playerId}/reset-password"], pages: ["areas/players/page.tsx"] },
  "Suspend, reinstate, close, reopen": {
    routes: [
      "PATCH players /players/{playerId}/suspend", "PATCH players /players/{playerId}/reinstate",
      "POST players /players/{playerId}/close", "POST players /players/{playerId}/reopen",
    ],
    pages: ["areas/players/page.tsx"],
  },
  "Self-exclusion": { routes: ["POST responsible-gambling /{player}/self-exclude"], pages: ["areas/players/protection.tsx"] },
  // T114 names "the tags and VIP tier controls" without a file: any of the player's pages
  "VIP tier, potential VIP": {
    routes: ["PUT players /players/{playerId}/vip-tier", "PUT players /players/{playerId}/pot-vip"],
    pages: ["areas/players"],
  },
  "Manager tags": { routes: ["GET players /players/{playerId}/tags", "PUT players /players/{playerId}/tags"], pages: ["areas/players"] },
  "Clear suspected-duplicate flag; clear no-deposit block": {
    routes: ["POST players /players/{playerId}/clear-suspect-flag", "POST players /players/{playerId}/clear-no-deposit-block"],
    pages: ["areas/players/page.tsx"],
  },
  "Activity feed with categories, search, period; CSV export as a job": {
    routes: ["GET /api/players/:player/timeline", "POST /api/players/:player/timeline/export"],
    pages: ["areas/players/timeline.tsx", "areas/players/read.ts"],
  },
  "Games panel (what the player plays)": { routes: ["GET players /players/{playerId}/games"], pages: ["areas/players/games.tsx"] },
  "Devices tab": { routes: ["GET players /players/{playerId}/devices"], pages: ["areas/players/devices.tsx"] },
  "Marketing consents and suppression": {
    routes: ["GET players /players/{playerId}/crm", "PATCH players /players/{playerId}/crm"],
    pages: ["areas/players/profile.tsx"],
  },
  "Erasure request": { routes: ["POST players /players/{playerId}/erasure"], pages: ["areas/players/page.tsx"] },
  "Verification link": { routes: ["GET players /players/{playerId}/verification-link"], pages: ["areas/players/page.tsx"] },
  "Wallet balances, restricted funds, lifetime totals, transactions": {
    routes: ["GET /api/players/:player/standing", "GET wallet /wallets/{player}/{currency}/transactions"],
    pages: ["areas/players/page.tsx", "areas/players/read.ts", "areas/players/wallets.tsx"],
  },

  // Sessions, rounds and transactions
  "Player sessions list with filters; force-close": {
    // the brand-wide list (P5) as well as a player's
    routes: [
      "GET players /players/{playerId}/player-sessions", "POST players /players/{playerId}/player-sessions/{sessionId}/force-close",
      "GET players /sessions",
    ],
    pages: ["areas/players/sessions.tsx"],
  },
  "Game sessions active and closed; force-end; force-end all": {
    routes: [
      "GET players /players/{playerId}/player-sessions", "POST players /players/{playerId}/sessions/{sessionId}/force-end",
      "POST players /players/{playerId}/sessions/force-end-all",
    ],
    pages: ["areas/players/sessions.tsx"],
  },
  "A session's bets": { routes: ["GET players /players/{playerId}/sessions/{sessionId}/bets"], pages: ["areas/players/sessions.tsx"] },
  "Player session timeline with data-gap warning": {
    routes: ["GET players /players/{playerId}/player-sessions"],
    pages: ["areas/players/sessions.tsx"],
  },
  "Game session rounds": { routes: ["GET game-provider /rounds"], pages: ["areas/players/rounds.tsx"] },
  "Bet and win search by provider round or transaction id, type, status, period, with totals": {
    // a round by its id here, by a transaction's in the search; the filters and totals are players' `bets` (P10)
    routes: ["GET game-provider /rounds", "GET game-provider /rounds/by-round/{roundId}", "GET players /players/{playerId}/bets"],
    pages: ["areas/players/rounds.tsx"],
  },
  "Dispute report": { routes: ["POST /api/jobs"], pages: ["areas/reports/ReportsPage.tsx", "areas/jobs/jobs.ts"] },
  "Deposits list and detail": {
    routes: ["GET merchant /cashier", "GET merchant /{paymentId}"],
    pages: ["areas/payments/deposits.tsx"],
  },

  // Withdrawals and payouts
  "Withdrawal queue by state, needs-review count, filters": {
    routes: ["GET /api/withdrawals"],
    pages: ["areas/withdrawals/queue.tsx", "areas/withdrawals/read.ts"],
  },
  "Approve, reject, confirm manual payment, mark payment failed": {
    routes: [
      "POST payouts /withdrawal-requests/{requestId}/approve", "POST payouts /withdrawal-requests/{requestId}/reject",
      "POST payouts /withdrawal-requests/{requestId}/confirm-payout", "POST payouts /withdrawal-requests/{requestId}/mark-payout-failed",
    ],
    pages: ["areas/withdrawals/request.tsx"],
  },
  "Withdrawal request page with its whole story": {
    routes: ["GET payouts /withdrawal-requests/{requestId}"],
    pages: ["areas/withdrawals/request.tsx", "areas/withdrawals/read.ts"],
  },
  "A player's withdrawal history in every state": {
    routes: ["GET payouts /players/{playerId}/withdrawal-history"],
    pages: ["areas/players/withdrawals.tsx", "areas/withdrawals/read.ts"],
  },
  "Deposit records; per-player turnover override": {
    routes: ["GET wallet /wallets/{player}/{currency}/transactions", "PUT wallet /wallets/{player}/{currency}/turnover-override"],
    pages: ["areas/players/wallets.tsx"],
  },
  "Payments payouts page (substrate payout twins)": {
    routes: ["GET /api/withdrawals"],
    pages: ["areas/withdrawals/queue.tsx", "areas/withdrawals/read.ts"],
  },

  // Corrections
  "Adjustment: credit or debit, real or restricted, reason": {
    routes: ["POST wallet /wallets/{player}/{currency}/adjustments"],
    pages: ["areas/corrections/adjust.tsx"],
  },
  "Adjustments to many players": { routes: ["POST /api/adjustment-batches"], pages: ["areas/corrections/batch.tsx"] },
  "Account adjustments (non-player accounts)": {
    routes: ["POST wallet /accounts/{kind}/{name}/adjustments", "GET wallet /accounts/{kind}/{name}/balance"],
    pages: ["areas/corrections/accounts.tsx"],
  },

  // Rewards
  "Brand-wide reward list with search and state": {
    routes: ["GET rewards /reward-grants"],
    pages: ["areas/rewards/list.tsx", "areas/rewards/read.ts"],
  },
  "A player's rewards with wagering progress": {
    routes: ["GET rewards /reward-grants", "GET wallet /wallets/{player}/{currency}/restricted-funds"],
    pages: ["areas/players/rewards.tsx", "areas/rewards/read.ts"],
  },
  "Forfeit or reclaim": { routes: ["POST rewards /reward-grants/{rewardId}/revoke"], pages: ["areas/players/rewards.tsx"] },
  "Grant from an offer; grant by hand (amount, or free rounds)": {
    routes: ["POST rewards /reward-grants/{playerId}"],
    pages: ["areas/rewards/grant.tsx"],
  },
  "Offers: create, edit, deactivate, reorder, dependants named, free rounds values, games picker": {
    routes: [
      "GET rewards /offers", "POST rewards /offers", "PATCH rewards /offers/{offerId}", "DELETE rewards /offers/{offerId}",
      "PUT rewards /offers/order", "GET rewards /offers/free-rounds/spin-values", "GET game-provider /game-catalog",
    ],
    pages: ["areas/rewards/offers.tsx", "areas/rewards/read.ts"],
  },

  // Identity and responsible gambling
  "Identity queue (documents awaiting, pending, ready for final review, verified, rejected)": {
    routes: ["GET kyc /queue"],
    pages: ["areas/identity/queue.tsx", "areas/identity/kyc.ts"],
  },
  "Documents: view, approve, reject with reason, move to review": {
    routes: ["GET /api/kyc/:player/documents/:document/view", "POST kyc /{playerId}/reviews/{documentId}"],
    pages: ["areas/players/identity.tsx", "areas/identity/kyc.ts"],
  },
  "Verification mode, status, required documents, override, source of funds, dismiss a flag": {
    routes: [
      "PUT kyc /{playerId}/mode", "PUT kyc /{playerId}/status", "PUT kyc /{playerId}/required", "POST kyc /{playerId}/override",
      "POST kyc /{playerId}/sof", "POST kyc /{playerId}/flags/{reason}/dismiss",
    ],
    pages: ["areas/players/identity.tsx"],
  },
  "KYC policy and licence profile": {
    routes: ["GET kyc /policy", "PUT kyc /policy", "POST kyc /policy/profile"],
    pages: ["areas/configuration/identity-policy.tsx", "areas/identity/kyc.ts"],
  },
  "Limits set and removed, with pending change": {
    routes: ["GET responsible-gambling /{player}", "PUT responsible-gambling /{player}/limits/{type}", "DELETE responsible-gambling /{player}/limits/{type}"],
    pages: ["areas/players/protection.tsx", "areas/protection/rg.ts"],
  },
  "Breaks started and lifted; return": {
    routes: ["PUT responsible-gambling /{player}/cooling-off", "POST responsible-gambling /{player}/cooling-off/lift", "POST responsible-gambling /{player}/return"],
    pages: ["areas/players/protection.tsx"],
  },
  "Cases list; notes; close": {
    // a player's cases, and the brand's open ones (G2)
    routes: [
      "POST responsible-gambling /{player}/cases", "POST responsible-gambling /{player}/cases/{caseId}/notes",
      "POST responsible-gambling /{player}/cases/{caseId}/close", "GET responsible-gambling /cases",
    ],
    pages: ["areas/protection/cases.tsx"],
  },
  "Fraud cases: list, review, dismiss": {
    routes: ["GET players /fraud-cases", "PATCH players /fraud-cases/{caseId}/review", "PATCH players /fraud-cases/{caseId}/dismiss"],
    pages: ["areas/fraud/cases.tsx"],
  },

  // Games and providers
  "Providers list": { routes: ["GET game-provider /providers"], pages: ["areas/configuration/games.tsx"] },
  "Provider configuration and catalogue refresh": {
    routes: ["PUT game-provider /providers/{provider}", "POST game-provider /providers/{provider}/catalogue/refresh"],
    pages: ["areas/configuration/games.tsx"],
  },
  "Game catalogue: developers, categories, per-game overrides": {
    routes: ["GET game-provider /game-catalog", "GET game-provider /game-catalog-config", "PUT game-provider /game-catalog-config"],
    pages: ["areas/configuration/games.tsx"],
  },
  "Unacknowledged callbacks: replay, resolve": {
    routes: [
      "GET game-provider /unacknowledged", "POST game-provider /unacknowledged/{transactionId}/replay",
      "POST game-provider /unacknowledged/{transactionId}/resolve",
    ],
    pages: ["areas/configuration/callbacks.tsx"],
  },

  // Brand configuration
  "Lobby and deposit URLs": { routes: ["GET players /brand-config", "PUT players /brand-config"], pages: ["areas/configuration/brand.tsx"] },
  "Country allow-list": { routes: ["GET players /allow-list", "PUT players /allow-list"], pages: ["areas/configuration/brand.tsx"] },
  "Currency and zone": { routes: ["GET players /currency-config", "PUT players /currency-config"], pages: ["areas/configuration/brand.tsx"] },
  "Withdrawal settings": { routes: ["GET payouts /withdrawal-config", "PUT payouts /withdrawal-config"], pages: ["areas/configuration/brand.tsx"] },
  "Wagering settings and game contribution": {
    routes: ["GET wallet /wallet-config", "PUT wallet /wallet-config/turnover", "PUT wallet /wallet-config/rewards"],
    pages: ["areas/configuration/brand.tsx"],
  },
  "Deposit bounds (minimum and maximum per method)": {
    routes: ["GET merchant /sub-brands/{id}/deposit-bounds", "PUT merchant /sub-brands/{id}/deposit-bounds"],
    pages: ["areas/payments/deposit-bounds.tsx"],
  },
  "Terminal capabilities": {
    routes: ["GET merchant /payments/terminals", "PUT merchant /merchants/{id}/gateways/integrations/{tid}/capability"],
    pages: ["areas/payments/terminals.tsx"],
  },
  "CRM configuration": {
    routes: ["GET engagement /crm-config", "PUT engagement /crm-config"],
    pages: ["areas/configuration/crm.tsx"],
  },
  "Support agent configuration": { routes: ["GET support-agent /config", "PUT support-agent /config"], pages: ["areas/configuration/agent.tsx"] },
  "Modules page": { routes: ["GET players /modules", "POST players /modules/requests"], pages: ["areas/configuration/brand.tsx"] },
  "Attribution networks, webhooks, signing keys": {
    routes: ["GET attribution /networks", "GET attribution /webhooks", "GET attribution /signing-keys"],
    pages: ["areas/configuration/attribution.tsx"],
  },
  "Reporting settings": { routes: ["GET reporting /settings", "PUT reporting /settings"], pages: ["areas/configuration/brand.tsx"] },

  // Payments administration
  "Hierarchy: holdings, companies, sub-brands; status": {
    routes: [
      "GET merchant /holdings", "GET merchant /companies", "GET merchant /sub-brands", "PUT merchant /holdings/{id}/status",
      "PUT merchant /companies/{id}/status", "PUT merchant /sub-brands/{id}/status",
    ],
    pages: ["areas/payments/hierarchy.tsx"],
  },
  "Payment provider configurations: create, edit, activate, disable, delete, test payment": {
    routes: [
      "GET merchant /sub-brands/{id}/psp-credentials", "POST merchant /sub-brands/{id}/psp-credentials",
      "PUT merchant /sub-brands/{id}/psp-credentials/{provider}", "DELETE merchant /sub-brands/{id}/psp-credentials/{provider}",
      "POST merchant /sub-brands/{id}/psp-credentials/{provider}/activate", "POST merchant /sub-brands/{id}/psp-credentials/{provider}/disable",
      "POST merchant /sub-brands/{id}/psp-credentials/{provider}/test-payment",
    ],
    pages: ["areas/payments/providers.tsx"],
  },
  "Routing rules: tree, draft, simulate, deploy, retire, roll back; terminals; gateway catalogue; integrations": {
    routes: [
      "GET merchant /payments/routing-rules/state", "POST merchant /payments/routing-rules", "POST merchant /payments/routing-rules/simulate",
      "POST merchant /payments/routing-rules/deploy", "POST merchant /payments/routing-rules/retire",
      "POST merchant /payments/routing-rules/rollback/{version}",
    ],
    pages: ["areas/payments/routing.tsx"],
  },
  "Merchant detail": { routes: ["GET merchant /merchants/{id}"], pages: ["areas/payments/merchant.tsx"] },
  "Undelivered and refused provider outcomes; redrive": {
    routes: ["GET psp-gateway /psp/outcomes/undelivered", "GET psp-gateway /psp/outcomes/refused", "POST psp-gateway /psp/outcomes/{reference}/redrive"],
    pages: ["areas/payments/outcomes.tsx"],
  },
  "Lost answers": {
    routes: ["GET ingest /ingest/lost-answers", "POST ingest /ingest/lost-answers/{recordId}/seen"],
    pages: ["areas/payments/lost-answers.tsx"],
  },

  // Team
  "Operators: list, invite with role and level, resend, reset, revoke": {
    routes: [
      "GET merchant /operators", "POST merchant /operators", "POST merchant /operators/{id}/resend-invitation",
      "POST merchant /operators/{id}/reset-password", "DELETE merchant /operators/{id}",
    ],
    pages: ["areas/team/operators.tsx"],
  },
  "Roles: create, change what each may do, reset, delete": {
    routes: ["GET players /roles", "PUT players /roles/{name}", "DELETE players /roles/{name}", "POST players /roles/{name}/reset"],
    pages: ["areas/team/roles.tsx"],
  },
  "Give and take away an operator's roles for the brand": {
    routes: ["PUT players /role-assignments/{operator}"],
    pages: ["areas/team/assign.tsx", "areas/team/roles.tsx"],
  },

  // Affiliate programme
  "Programme dashboard; reporting by campaign and country": {
    routes: ["GET affiliate-programme /revenue", "GET affiliate-programme /revenue/by-campaign", "GET affiliate-programme /revenue/geo", "GET affiliate-programme /traffic"],
    pages: ["areas/affiliates/dashboard.tsx"],
  },
  Plans: {
    routes: ["GET affiliate-programme /commission-plans", "POST affiliate-programme /commission-plans", "PATCH affiliate-programme /commission-plans/{plan}"],
    pages: ["areas/affiliates/plans.tsx"],
  },
  "Accounts: list, create, approve, reject, status, edit, notes, audit, password, email, network, reparent": {
    routes: [
      "GET affiliate-programme /accounts", "POST affiliate-programme /accounts", "POST affiliate-programme /accounts/{affiliate}/approve",
      "POST affiliate-programme /accounts/{affiliate}/reject", "PATCH affiliate-programme /accounts/{affiliate}",
      "GET affiliate-programme /accounts/{affiliate}/notes", "POST affiliate-programme /accounts/{affiliate}/notes",
      "GET affiliate-programme /accounts/{affiliate}/audit", "POST affiliate-programme /accounts/{affiliate}/reset-password",
      "POST affiliate-programme /accounts/{affiliate}/update-email", "GET affiliate-programme /accounts/{affiliate}/network-health",
      "POST affiliate-programme /network/{sub}/suspend", "POST affiliate-programme /network/{sub}/reactivate",
    ],
    pages: ["areas/affiliates/accounts.tsx", "areas/affiliates/account.tsx"],
  },
  "View as the affiliate": { routes: ["GET affiliate-programme /accounts/{affiliate}/portal/{view}"], pages: ["areas/affiliates/account.tsx"] },
  "Tracking links, creatives": {
    routes: [
      "GET affiliate-programme /tracking-links", "POST affiliate-programme /tracking-links", "GET affiliate-programme /creatives",
      "POST affiliate-programme /creatives", "DELETE affiliate-programme /creatives/{creative}",
    ],
    pages: ["areas/affiliates/links.tsx", "areas/affiliates/creatives.tsx"],
  },
  "Payouts by period: close, status, export": {
    routes: [
      "GET affiliate-programme /payouts", "POST affiliate-programme /payouts/close", "PATCH affiliate-programme /payouts/{payout}/status",
      "GET affiliate-programme /payouts/export",
    ],
    pages: ["areas/affiliates/payouts.tsx"],
  },
  "Fraud flags: dismiss with reason": {
    routes: ["GET affiliate-programme /fraud-flags", "PATCH affiliate-programme /fraud-flags/{flag}/dismiss"],
    pages: ["areas/affiliates/fraud.tsx"],
  },
  "Settings, programme settings, messages, managers": {
    routes: [
      "GET affiliate-programme /settings", "PUT affiliate-programme /settings", "GET affiliate-programme /programme",
      "PUT affiliate-programme /programme", "POST affiliate-programme /messages", "GET affiliate-programme /managers",
      "POST affiliate-programme /managers", "DELETE affiliate-programme /managers/{user}",
    ],
    pages: ["areas/affiliates/settings.tsx", "areas/affiliates/messages.tsx", "areas/affiliates/managers.tsx"],
  },
  "Affiliate adjustments": { routes: ["POST affiliate-programme /accounts/{affiliate}/adjustments"], pages: ["areas/affiliates/account.tsx"] },

  // Unreachable in the original, kept
  "Customer.io page (`CustomerIOConfigPage.tsx`)": {
    routes: ["GET engagement /crm-config", "PUT engagement /crm-config"],
    pages: ["areas/configuration/crm.tsx"],
  },
  "Affiliate adjustment call": { routes: ["POST affiliate-programme /accounts/{affiliate}/adjustments"], pages: ["areas/affiliates/account.tsx"] },
};

interface ParityRow {
  capability: string;
  owner?: string;
  status: string;
}

/** parity.md's table rows (a capability, its owner where the table has one, its status), headers left out. */
function parity(): ParityRow[] {
  const text = readFileSync(path.join(repo, "specs/030-backoffice/parity.md"), "utf8");
  const rows: ParityRow[] = [];
  for (const line of text.split("\n")) {
    if (!line.startsWith("|") || /^\|[-| ]+\|$/.test(line)) continue;
    const cells = line.slice(1, -1).split("|").map((c) => c.trim());
    if (cells[0] === "Capability") continue;
    rows.push(cells.length === 3 ? { capability: cells[0]!, owner: cells[1], status: cells[2]! } : { capability: cells[0]!, status: cells.at(-1)! });
  }
  return rows;
}

const counted = (r: ParityRow) => !/^(dropped|moved)\b/.test(r.status);

/** A source with its comments taken out, so a route named in a comment does not count as called. */
const code = (file: string) => readFileSync(file, "utf8").replace(/\/\*[\s\S]*?\*\//g, "").replace(/(^|[^:"'`])\/\/.*$/gm, "$1");

function files(rel: string): string[] {
  const at = path.join(web, rel);
  if (!existsSync(at)) return [];
  if (!statSync(at).isDirectory()) return [at];
  return readdirSync(at, { recursive: true, encoding: "utf8" }).map((f) => path.join(at, f)).filter((f) => /\.tsx?$/.test(f));
}

/** The owners' namespaces a file imports, by the name it binds them to: `{ responsibleGambling as rg }` is `rg`. */
const Namespaces: Record<string, string> = Object.fromEntries(
  [...readFileSync(path.join(service, "src/shared/owners/index.ts"), "utf8").matchAll(/export \* as (\w+) from "\.\/([\w-]+)\.ts"/g)].map((m) => [m[1]!, m[2]!]),
);
function bindings(source: string): Map<string, string> {
  const out = new Map<string, string>();
  for (const m of source.matchAll(/import\s*\{([^}]*)\}\s*from\s*"[./]*shared\/owners\/index\.ts"/g)) {
    for (const part of m[1]!.split(",").map((p) => p.trim()).filter(Boolean)) {
      const [name, alias] = part.split(/\s+as\s+/);
      const svc = Namespaces[name!];
      if (svc) out.set(alias ?? name!, svc);
    }
  }
  return out;
}

/** The server's own routes, as `METHOD path` (Hono's `app.get("/api/…"`). */
const served = new Set(
  readdirSync(server)
    .filter((f) => f.endsWith(".ts"))
    .flatMap((f) => [...readFileSync(path.join(server, f), "utf8").matchAll(/app\.(get|post|put|patch|delete)\("([^"]+)"/g)])
    .map((m) => `${m[1]!.toUpperCase()} ${m[2]}`),
);

/** The literal text before a path's first parameter and after its last: what a page's string must hold. */
function ends(p: string, param: RegExp): [string, string] {
  const at = p.search(param);
  if (at < 0) return [p, ""];
  const tail = p.replace(new RegExp(`^.*${param.source}`), "");
  return [p.slice(0, at), tail];
}

type Resolved = { kind: "table"; row: Row } | { kind: "server"; method: string; path: string };

function resolve(route: string): Resolved | string {
  const parts = route.split(" ");
  if (parts.length === 2) {
    const [method, p] = parts as [string, string];
    return served.has(route) ? { kind: "server", method, path: p } : `${route}: the server serves no such route`;
  }
  const [method, svc, p] = parts as [string, string, string];
  const row = table.find((r) => r.method === method && r.service === svc && r.path === p);
  return row ? { kind: "table", row } : `${route}: no row of src/server/routes.ts`;
}

/** Whether the page's sources call the route: its typed client, or its path as a string. */
function calls(sources: string[], r: Resolved): boolean {
  if (r.kind === "server") {
    const [head, tail] = ends(r.path, /:\w+/);
    return sources.some((s) => s.includes(head)) && (tail === "" || sources.some((s) => s.includes(tail)));
  }
  const name = nameOf(r.row);
  const byClient = sources.some((s) => {
    for (const [alias, svc] of bindings(s)) if (svc === r.row.service && new RegExp(`\\b${alias}\\.${name}\\b`).test(s)) return true;
    return false;
  });
  if (byClient) return true;
  const [head, tail] = ends(r.row.path, /\{\w+\}/);
  return sources.some((s) => s.includes(`/api/s/${r.row.service}`)) && sources.some((s) => s.includes(head)) && (tail === "" || sources.some((s) => s.includes(tail)));
}

/** The services an Owner cell names (the programme is `affiliate-programme`). */
const Services = new Set(table.map((r) => r.service));
function named(owner: string): Set<string> {
  const out = new Set<string>();
  for (const word of owner.replace(/`[^`]*`/g, " ").split(/[\s,()]+/)) {
    if (Services.has(word)) out.add(word);
    if (word === "programme") out.add("affiliate-programme");
  }
  return out;
}

describe("parity (FR-030, SC-001)", () => {
  const rows = parity();

  test("parity.md is read: its rows, each capability once", () => {
    expect(rows.length).toBeGreaterThan(80);
    const names = rows.map((r) => r.capability);
    expect(names.filter((n, i) => names.indexOf(n) !== i)).toEqual([]);
  });

  test("every entry here is a row of parity.md still counted", () => {
    const live = new Set(rows.filter(counted).map((r) => r.capability));
    expect(Object.keys(Reached).filter((k) => !live.has(k))).toEqual([]);
  });

  test.each(rows.filter(counted).map((r) => [r.capability, r] as const))("%s", (_, row) => {
    const reach = Reached[row.capability];
    expect(reach, "no route and page named for this row").toBeDefined();
    const problems: string[] = [];
    if (reach!.routes.length === 0) problems.push("no route");
    if (!reach!.pages.some((p) => p.startsWith("areas/"))) problems.push("no page under src/web/areas/");
    const sources: string[] = [];
    for (const p of reach!.pages) {
      const found = files(p);
      if (found.length === 0) problems.push(`${p}: not there`);
      sources.push(...found.map(code));
    }
    const resolved = reach!.routes.map(resolve);
    for (const r of resolved) if (typeof r === "string") problems.push(r);
    if (sources.length > 0) {
      reach!.routes.forEach((route, i) => {
        const r = resolved[i]!;
        if (typeof r !== "string" && !calls(sources, r)) problems.push(`${route}: not called by ${reach!.pages.join(", ")}`);
      });
    }
    const owners = row.owner ? named(row.owner) : new Set<string>();
    const tableServices = resolved.flatMap((r) => (typeof r !== "string" && r.kind === "table" ? [r.row.service] : []));
    if (owners.size > 0 && tableServices.length > 0 && !tableServices.some((s) => owners.has(s))) {
      problems.push(`the owner column names ${[...owners].join(", ")}, the routes ${[...new Set(tableServices)].join(", ")}`);
    }
    expect(problems).toEqual([]);
  });
});

describe("the backoffice is required by nothing else (FR-044)", () => {
  const manifests = [
    ...readdirSync(path.join(repo, "modules")).filter((m) => m !== "backoffice").map((m) => path.join(repo, "modules", m, "module.json")),
    ...readdirSync(path.join(repo, "shared")).map((p) => path.join(repo, "shared", p, "manifest.json")),
  ].filter(existsSync);
  const Its = ["backoffice", "backoffice-state"];
  /** The module's own values (`casino-brand` and the like are core's, which every module reads). */
  const keys = (JSON.parse(readFileSync(path.join(repo, "modules/backoffice/module.json"), "utf8")) as { configuration: { key: string }[] }).configuration
    .map((v) => v.key)
    .filter((k) => k.startsWith("backoffice-"));

  test("no other module or shared project depends on the module, runs its services or reads its values", () => {
    expect(manifests.length).toBeGreaterThan(5);
    for (const file of manifests) {
      const m = JSON.parse(readFileSync(file, "utf8")) as { dependencies?: { module: string }[]; services?: { name: string }[] };
      const rel = path.relative(repo, file);
      expect(m.dependencies?.map((d) => d.module) ?? [], rel).not.toContain("backoffice");
      for (const s of m.services ?? []) expect(Its, `${rel}: ${s.name}`).not.toContain(s.name);
      const text = readFileSync(file, "utf8");
      for (const k of keys) expect(text.includes(`"${k}"`), `${rel} reads ${k}`).toBe(false);
    }
  });

  test("no other service's code calls the backoffice's services", () => {
    const others = readdirSync(path.join(repo, "services")).filter((s) => !Its.includes(s));
    const offenders: string[] = [];
    for (const s of others) {
      const main = path.join(repo, "services", s, "src/main");
      if (!existsSync(main)) continue;
      for (const f of readdirSync(main, { recursive: true, encoding: "utf8" })) {
        if (!/\.(scala|java|kt|ts|go|py|rs)$/.test(f)) continue;
        if (/"backoffice(-state)?"/.test(readFileSync(path.join(main, f), "utf8"))) offenders.push(`services/${s}/src/main/${f}`);
      }
    }
    expect(offenders).toEqual([]);
  });
});

describe("the original is left alone (FR-044)", () => {
  test("the backoffice reads nothing of the original's repository", () => {
    const offenders = ["src", "scripts"].flatMap((d) =>
      readdirSync(path.join(service, d), { recursive: true, encoding: "utf8" })
        .filter((f) => /\.(ts|tsx|json|css|html)$/.test(f))
        .filter((f) => readFileSync(path.join(service, d, f), "utf8").includes("eitheror-platform"))
        .map((f) => `${d}/${f}`),
    );
    expect(offenders).toEqual([]);
  });

  /** The commit this branch left main at, when the history is there to say (a shallow clone has none). */
  function base(): string | undefined {
    for (const ref of ["origin/main", "main"]) {
      try {
        return execFileSync("git", ["merge-base", ref, "HEAD"], { cwd: repo, encoding: "utf8", stdio: ["ignore", "pipe", "ignore"] }).trim();
      } catch {
        /* the next ref, or none */
      }
    }
    return undefined;
  }

  // The record of the original is its recordings: this branch may add recordings (T131's screens) and may
  // tell a driver which new scenarios it does not drive, but changes or removes no recording.
  test("this branch changes or removes no recording of the original under tests/original/", () => {
    const from = base();
    if (!from) return;
    const changed = execFileSync("git", ["diff", "--diff-filter=MDR", "--name-only", from, "HEAD", "--", "tests/original"], { cwd: repo, encoding: "utf8" })
      .split("\n")
      .filter((f) => f !== "" && !/^tests\/original\/[^/]+-driver\//.test(f));
    expect(changed).toEqual([]);
  });
});

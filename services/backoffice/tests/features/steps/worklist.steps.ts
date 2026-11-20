// features/backoffice/worklist.feature (T056): an operator starts from the open work their roles let them
// act on, oldest first, each kind counted by its owner; acting keeps their place; an item someone else
// acted on says who and what and offers nothing; a kind whose owner cannot be read is unavailable, not
// none. The owners are the doubles: payouts, kyc, players' fraud cases and responsible-gambling's cases.
import type { Browser, Locator, Page } from "@playwright/test";
import { t } from "../../../src/shared/messages/index.ts";
import { describe } from "../../../src/web/actions/index.ts";
import type { Entry } from "../../doubles/owner.ts";
import { operator } from "../../doubles/env.ts";
import type { Identity } from "../../doubles/realm.ts";
import { signIn } from "../../doubles/sign-in.ts";
import { expect, Given, Then, When, type World } from "../world.ts";

interface Scenario {
  request?: string;
  document?: string;
  fraudCase?: string;
  requests?: Req[];
  /** The row key the operator acted on, and the one that took its place. */
  acted?: string;
  next?: string;
  other?: Page;
  approver?: Identity;
}
const scenarios = new WeakMap<World, Scenario>();
const of = (w: World): Scenario => scenarios.get(w) ?? (scenarios.set(w, {}), scenarios.get(w)!);

const main = (page: Page): Locator => page.getByRole("main");
const rowsOf = (page: Page) => main(page).locator("tbody tr[data-row]");
const keysOf = (page: Page) => rowsOf(page).evaluateAll((trs) => trs.map((tr) => tr.getAttribute("data-row")));
const kinds = (page: Page) => main(page).getByRole("list", { name: t("worklist.kinds") });

const P = "/v1/casino/backoffice/{brand}";
const B = "/v1/casino/brands/{brand}";

interface Req {
  requestId: string;
  playerId: string;
  amount: number;
  currency: string;
  createdAt: string;
  status: string;
  payoutStatus: string;
  kycStatus: string;
  decisions: { approved: boolean; operatorId: string; at: string }[];
}

const uuid = (n: number) => `5e0c${String(n).padStart(4, "0")}-7c1a-4d2b-9f3e-${String(n).padStart(12, "0")}`;
const request = (n: number, playerId: string, createdAt: string): Req => ({
  requestId: uuid(n), playerId, amount: 30000 + n, currency: "JPY", createdAt, status: "PENDING_REVIEW", payoutStatus: "NONE", kycStatus: "verified", decisions: [],
});

/** A request as payouts' queue lists it: without its decisions. */
const rowOf = (r: Req) => Object.fromEntries(Object.entries(r).filter(([k]) => k !== "decisions"));

/** payouts' queue (pending review, and nothing needing review) and each request's own read. */
function payoutsFixtures(pending: readonly Req[], every: readonly Req[] = pending): Record<string, Entry> {
  return {
    [`GET ${B}/withdrawal-requests`]: {
      by: "?payoutStatus",
      answers: {
        "": { body: { requests: pending.map(rowOf), total: pending.length, page: 1, pageSize: 50 } },
        NEEDS_REVIEW: { body: { requests: [], total: 0, page: 1, pageSize: 50 } },
      },
    },
    [`GET ${B}/withdrawal-requests/{requestId}`]: { by: "requestId", answers: Object.fromEntries(every.map((r) => [r.requestId, { body: r }])) },
    [`POST ${B}/withdrawal-requests/{requestId}/approve`]: { body: { requestId: "", status: "APPROVED", payoutStatus: "INITIATED" } },
  };
}

const empty = {
  [`GET ${P}/rg/cases`]: { body: { cases: [], total: 0, page: 1, pageSize: 50 } },
};

async function openWorklist(page: Page) {
  await expect(kinds(page)).toBeVisible();
}

// --- an operator's worklist ------------------------------------------------------------------------

Given("a brand with a withdrawal request pending review, an identity document awaiting review and an open fraud case", async ({ world }) => {
  const [ana, kenji, fahad] = world.brand.players;
  const s = of(world);
  const pending = request(1, kenji!.playerId, "2026-11-14T22:15:00Z");
  s.request = `withdrawals_pending:${pending.requestId}`;
  s.document = `documents_awaiting:${ana!.playerId}`;
  const caseId = "9a3c2b1d-4e5f-4a6b-8c7d-0e1f2a3b4c5d";
  s.fraudCase = `fraud_cases:${caseId}`;
  await world.doubles.fixtures("payouts", payoutsFixtures([pending]));
  await world.doubles.fixtures("kyc", {
    [`GET ${P}/kyc/queue`]: {
      by: "?filter",
      answers: {
        awaiting: {
          body: {
            rows: [{ playerId: ana!.playerId, status: "pending", mode: "automatic", awaiting: 1, oldestAwaitingAt: "2026-11-10T09:15:00Z", flags: [], updatedAt: "2026-11-10T09:15:00Z" }],
            counts: { awaiting: 1, ready: 0, unverified: 0, pending: 1, verified: 0, rejected: 0, sourceOfFunds: 0 },
          },
        },
        flagged: { body: { rows: [], counts: { awaiting: 1 } } },
      },
    },
  });
  await world.doubles.fixtures("players", {
    [`GET ${P}/fraud-cases`]: { body: { cases: [{ caseId, playerIds: [fahad!.playerId, kenji!.playerId], signals: ["shared_device"], status: "OPEN", detectedAt: "2026-11-12T08:40:00Z", reviewedBy: null, reviewedAt: null, reviewNotes: null }] } },
  });
  await world.doubles.fixtures("responsible-gambling", empty);
});

When("a finance operator opens their worklist", async ({ world }) => {
  await world.signInAs("brand_finance", { to: "/" });
  await openWorklist(world.page);
});

Then("the worklist holds the withdrawal request and nothing else", async ({ world }) => {
  await expect(rowsOf(world.page)).toHaveCount(1);
  expect(await keysOf(world.page)).toEqual([of(world).request]);
  // only payouts was asked for work: the finance roles admit no other kind
  const asked = (await world.doubles.calls()).filter((c) => ["kyc", "players", "responsible-gambling", "game-provider"].includes(c.owner) && /queue|fraud-cases|cases|unacknowledged/.test(c.path));
  expect(asked.map((c) => `${c.owner} ${c.path}`)).toEqual([]);
});

When("a compliance operator opens their worklist", async ({ world }) => {
  await world.signInAs("brand_compliance", { to: "/" });
  await openWorklist(world.page);
});

Then("the worklist holds the identity document and the fraud case, oldest first", async ({ world }) => {
  const s = of(world);
  await expect(rowsOf(world.page)).toHaveCount(2);
  expect(await keysOf(world.page)).toEqual([s.document, s.fraudCase]);
  await expect(kinds(world.page)).toContainText(t("worklist.kindCount", { kind: t("kind.documents_awaiting"), count: 1 }));
  await expect(kinds(world.page)).toContainText(t("worklist.kindCount", { kind: t("kind.fraud_cases"), count: 1 }));
  await expect(kinds(world.page)).not.toContainText(t("kind.withdrawals_pending"));
});

// --- acting keeps the operator's place -----------------------------------------------------------

/** Opens the item's panel and approves the request through its confirmation, the doubles moved on first. */
async function approve(world: World, page: Page, key: string, after: () => Promise<void>) {
  await main(page).locator(`tr[data-row="${key}"]`).click();
  const panel = main(page).locator(`[data-item="${key}"]`);
  const button = panel.getByRole("button", { name: t(describe("withdrawal.approve").label), exact: true });
  await button.click();
  const dialog = page.getByRole("dialog");
  await expect(dialog).toContainText(t(describe("withdrawal.approve").effect));
  await after();
  await dialog.getByRole("button", { name: t("action.confirm") }).click();
  await expect(dialog).toHaveCount(0);
}

Given("a finance operator at the tenth item of their worklist", async ({ world }) => {
  const players = world.brand.players;
  const requests = Array.from({ length: 12 }, (_, i) => request(i + 1, players[i % players.length]!.playerId, new Date(Date.UTC(2026, 10, 1 + i, 9)).toISOString()));
  const s = of(world);
  s.requests = requests;
  s.acted = `withdrawals_pending:${requests[9]!.requestId}`;
  s.next = `withdrawals_pending:${requests[10]!.requestId}`;
  await world.doubles.fixtures("payouts", payoutsFixtures(requests));
  await world.signInAs("brand_finance", { to: "/" });
  await expect(rowsOf(world.page)).toHaveCount(12);
  await rowsOf(world.page).nth(9).scrollIntoViewIfNeeded();
  expect(await rowsOf(world.page).nth(9).getAttribute("data-row")).toBe(s.acted);
});

When("the operator approves that withdrawal request", async ({ world }) => {
  const s = of(world);
  const requests = s.requests!;
  await approve(world, world.page, s.acted!, async () => {
    const approved = { ...requests[9]!, status: "APPROVED", payoutStatus: "INITIATED", decisions: [{ approved: true, operatorId: operator("brand_finance").sub, at: "2026-11-15T12:00:00Z" }] };
    await world.doubles.fixtures("payouts", payoutsFixtures(requests.filter((_, i) => i !== 9), requests.map((r, i) => (i === 9 ? approved : r))));
  });
});

Then("the operator is back at the worklist at the same place", async ({ world }) => {
  const s = of(world);
  await expect(world.page).toHaveURL((u) => u.pathname === "/");
  await expect(rowsOf(world.page)).toHaveCount(11);
  const tenth = rowsOf(world.page).nth(9);
  await expect(tenth).toHaveAttribute("data-row", s.next!);
  await expect(tenth).toHaveAttribute("aria-current", "true");
  await expect(tenth).toBeFocused();
});

Then("the withdrawal request is no longer in the worklist", async ({ world }) => {
  await expect(main(world.page).locator(`tr[data-row="${of(world).acted}"]`)).toHaveCount(0);
  const approvals = await world.doubles.calls("payouts", { method: "POST", path: /\/approve$/ });
  expect(approvals).toHaveLength(1);
});

// --- an item someone else acted on ---------------------------------------------------------------

async function second(world: World, browser: Browser, who: Identity): Promise<Page> {
  const page = await (await browser.newContext()).newPage();
  await signIn(page, world.doubles, who, { to: "/" });
  return page;
}

Given("two finance operators each with the same withdrawal request in their worklist", async ({ world, browser }) => {
  const s = of(world);
  const pending = request(31, world.brand.players[1]!.playerId, "2026-11-14T22:15:00Z");
  s.requests = [pending];
  s.request = `withdrawals_pending:${pending.requestId}`;
  await world.doubles.fixtures("payouts", payoutsFixtures([pending]));
  await world.signInAs("brand_finance", { to: "/" });
  s.approver = { sub: "op-finance-two", name: "Fiona Second", roles: [`brand_${world.brand.slug}`, `brand_finance@${world.brand.slug}`] };
  s.other = await second(world, browser, s.approver);
  for (const page of [world.page, s.other]) {
    await expect(rowsOf(page)).toHaveCount(1);
    await main(page).locator(`tr[data-row="${s.request}"]`).click();
    await expect(main(page).locator(`[data-item="${s.request}"]`).getByRole("button", { name: t(describe("withdrawal.approve").label), exact: true })).toBeVisible();
  }
});

When("one operator approves it", async ({ world }) => {
  const s = of(world);
  const [pending] = s.requests!;
  await approve(world, s.other!, s.request!, async () => {
    const approved = { ...pending!, status: "APPROVED", payoutStatus: "INITIATED", decisions: [{ approved: true, operatorId: s.approver!.sub, at: "2026-11-15T12:00:00Z" }] };
    await world.doubles.fixtures("payouts", payoutsFixtures([], [approved]));
  });
});

Then("the other operator sees who approved it and when", async ({ world }) => {
  const s = of(world);
  const panel = main(world.page).locator(`[data-item="${s.request}"]`);
  // the item opened is read again from payouts every few seconds
  await expect(panel.locator("[data-acted]")).toContainText(t("worklist.acted.approved", { operator: s.approver!.sub }), { timeout: 15_000 });
  await expect(panel.locator("[data-acted] time")).toHaveCount(1);
});

Then("the other operator is offered no action on it", async ({ world }) => {
  const panel = main(world.page).locator(`[data-item="${of(world).request}"]`);
  await expect(panel.getByRole("button", { name: t(describe("withdrawal.approve").label), exact: true })).toHaveCount(0);
  await expect(panel.getByRole("button", { name: t(describe("withdrawal.reject").label), exact: true })).toHaveCount(0);
  await expect(panel).toContainText(t("worklist.noAction"));
  await of(world).other?.context().close();
});

// --- a source that cannot be read ----------------------------------------------------------------

Given("a brand whose identity queue cannot be read", async ({ world }) => {
  await world.doubles.fail("kyc", 500, { method: "GET", path: `/v1/casino/backoffice/${world.brand.slug}/kyc/queue` });
  await world.doubles.fixtures("players", { [`GET ${P}/fraud-cases`]: { body: { cases: [] } } });
  await world.doubles.fixtures("responsible-gambling", empty);
});

Then("identity documents are shown as unavailable", async ({ world }) => {
  const kind = t("kind.documents_awaiting");
  await expect(kinds(world.page).locator('li[data-kind="documents_awaiting"]')).toHaveText(t("worklist.kindUnavailable", { kind }));
  await expect(main(world.page).locator('[data-unavailable="documents_awaiting"]')).toContainText(kind);
});

Then("they are not shown as none", async ({ world }) => {
  const kind = t("kind.documents_awaiting");
  await expect(kinds(world.page)).not.toContainText(t("worklist.kindCount", { kind, count: 0 }));
  await expect(main(world.page).locator('tr[data-row^="documents_awaiting:"]')).toHaveCount(0);
  // the others are shown as read: fraud cases counted, and none
  await expect(kinds(world.page)).toContainText(t("worklist.kindCount", { kind: t("kind.fraud_cases"), count: 0 }));
});

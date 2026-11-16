// features/backoffice/withdrawals.feature (T078; US4, FR-014, FR-026, SC-003): a withdrawal request's
// page tells its whole story (the amounts requested, held, paid and voided and why; every state with
// who moved it) and offers exactly the actions its state allows, each saying what it does before it is
// done; a player's withdrawals list holds every request in every state. payouts is the double, with the
// neighbours the request's page reads (the wallet's balance and deposits, kyc's status).
import type { Locator } from "@playwright/test";
import { t, type MessageKey } from "../../../src/shared/messages/index.ts";
import { formatMoney, parseMoney } from "../../../src/shared/money.ts";
import { describe, type ActionId } from "../../../src/web/actions/index.ts";
import { offeringOf, type Offering } from "../offered.ts";
import { expect, Given, Then, When, type World } from "../world.ts";

const B = "/v1/casino/brands/{brand}";
const P = "/v1/casino/backoffice/{brand}";

interface Scenario {
  request?: Record<string, unknown>;
  player?: string;
  history?: Record<string, unknown>[];
}
const scenarios = new WeakMap<World, Scenario>();
const of = (w: World): Scenario => scenarios.get(w) ?? (scenarios.set(w, {}), scenarios.get(w)!);

const main = (w: World): Locator => w.page.getByRole("main");

function amount(text: string): { minor: bigint; currency: string } {
  const [figure, currency] = text.trim().split(/\s+/) as [string, string];
  const minor = parseMoney(figure, currency);
  if (minor === undefined) throw new Error(`"${text}" is not an amount`);
  return { minor, currency };
}
const shown = (text: string) => formatMoney(amount(text));

const RequestId = "7d1e4c2a-5b3f-4e6d-8a9c-1f2e3d4c5b6a";

/** A request as payouts answers it (OperatorEndpoint.detail), pending review unless told otherwise. */
function request(player: string, over: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    requestId: RequestId,
    playerId: player,
    kycStatus: "verified",
    amount: 50000,
    paidAmount: 50000,
    voidedAmount: 0,
    currency: "EUR",
    status: "PENDING_REVIEW",
    payoutStatus: "NONE",
    terminal: { id: "t-sepa", name: "SEPA out", gateway: "paysafe", scheme: "bank_transfer" },
    destination: { destinationId: "d-1", firstName: "Lena", lastName: "M", ibanLastFour: "7710" },
    bonusForfeited: false,
    forfeitedAmount: 0,
    clawbackAmount: 0,
    providerReference: null,
    balancesAtRequest: 120000,
    restrictedAtRequest: 0,
    decisions: [],
    outcomes: [],
    transitions: [{ status: "pending_review", at: "2026-11-14T10:00:00Z", by: "player" }],
    createdAt: "2026-11-14T10:00:00Z",
    initiatedAt: null,
    settledAt: null,
    ...over,
  };
}

/** payouts' request, and what the page reads beside it: the wallet's balance and deposits, kyc's status. */
async function given(world: World, r: Record<string, unknown>) {
  of(world).request = r;
  const player = String(r.playerId);
  await world.doubles.fixtures("payouts", {
    [`GET ${B}/withdrawal-requests/{requestId}`]: { by: "requestId", answers: { [RequestId]: { body: r } } },
  });
  await world.doubles.fixtures("wallet", {
    [`GET ${P}/wallets/{player}/{currency}/balance`]: {
      body: { realBalance: "700.00", bonusBalance: "0.00", currency: "EUR", primary: null, depositPoolBalance: "700.00", convertedPoolBalance: "0.00", reserved: "0.00", available: "700.00" },
    },
    [`GET ${P}/wallets/{player}/{currency}/transactions`]: { body: { transactions: [], page: 0, pageSize: 5, total: 0 } },
  });
  await world.doubles.fixtures("kyc", {
    [`GET ${P}/kyc/{playerId}`]: { body: { playerId: player, status: "verified", mode: "automatic", categories: [], missingProfileFields: [], readyAt: null, sof: "unverified", requiredOfPlayer: [], deadline: null, depositsByCurrency: {}, flags: [] } },
  });
}

const lena = (w: World) => w.brand.players.find((p) => p.currency === "EUR")!.playerId;

// --- the amounts and why ---------------------------------------------------------------------------

Given("a withdrawal request of {string} of which {string} was voided by a reward's cap", async ({ world }, asked: string, voided: string) => {
  const a = amount(asked);
  const v = amount(voided);
  await given(world, request(lena(world), { amount: Number(a.minor), paidAmount: Number(a.minor - v.minor), voidedAmount: Number(v.minor), currency: a.currency }));
});

When("a finance operator opens the withdrawal request", async ({ world }) => {
  await world.signInAs("brand_finance", { to: `/withdrawals/${RequestId}` });
  await expect(main(world).getByRole("heading", { level: 1 })).toBeVisible();
});

const figure = (w: World, which: string) => main(w).locator(`[data-amount="${which}"]`);

Then("the operator sees {string} requested, {string} held and {string} voided", async ({ world }, requested: string, held: string, voided: string) => {
  await expect(figure(world, "requested")).toContainText(shown(requested));
  await expect(figure(world, "held")).toContainText(shown(held));
  await expect(figure(world, "voided")).toContainText(shown(voided));
});

Then("the operator sees that the reward's cap voided it", async ({ world }) => {
  await expect(figure(world, "voided")).toContainText(t("withdrawals.voided.cap"));
});

// --- the state's actions ---------------------------------------------------------------------------

/** The feature's state, as payouts answers it. */
const States: Record<string, { status: string; payoutStatus: string }> = {
  "pending review": { status: "PENDING_REVIEW", payoutStatus: "NONE" },
  "awaiting manual payment": { status: "AWAITING_MANUAL_PAYOUT", payoutStatus: "NONE" },
  "needs review": { status: "APPROVED", payoutStatus: "NEEDS_REVIEW" },
  completed: { status: "COMPLETED", payoutStatus: "RESOLVED" },
  rejected: { status: "REJECTED", payoutStatus: "NONE" },
};

/** The feature's words for an action, as the actions name them. */
const Named: Record<string, ActionId> = {
  approve: "withdrawal.approve",
  reject: "withdrawal.reject",
  "confirm the payment": "withdrawal.confirmPayout",
  "mark the payment failed": "withdrawal.markPayoutFailed",
};

Given("a withdrawal request that is {string}", async ({ world }, state: string) => {
  const s = States[state];
  if (!s) throw new Error(`no such state: ${state}`);
  await given(world, request(lena(world), s));
});

const actions = (w: World) => main(w).getByRole("region", { name: t("withdrawals.request.actions") });

/** A withdrawal request's actions; another feature's record sets its own (`offerIn`, rewards.steps.ts). */
const RequestActions: Offering = { region: actions, named: Named, none: "withdrawals.request.noActions" };

Then("the operator is offered {string}", async ({ world }, words: string) => {
  const { region: of, named, none } = offeringOf(world, RequestActions);
  const wanted = words === "nothing" ? [] : words.split(",").map((w) => describe(named[w.trim()] ?? (() => { throw new Error(`no such action: ${w}`); })()).label);
  const region = of(world);
  await expect(region).toBeVisible();
  const buttons = region.getByRole("button");
  await expect(buttons).toHaveCount(wanted.length);
  expect(await buttons.allTextContents()).toEqual(wanted.map((l) => t(l)));
  if (wanted.length === 0) await expect(region).toContainText(t(none));
});

Then("each action says what it will do before it is done", async ({ world }) => {
  const region = actions(world);
  const labels = await region.getByRole("button").allTextContents();
  for (const label of labels) {
    const id = (Object.keys(Named).map((w) => Named[w]!) as ActionId[]).find((a) => t(describe(a).label) === label)!;
    // the effect is stated on the page beside the action, and again in its confirmation
    await expect(region.locator(`[data-effect-of="${id}"]`)).toHaveText(t(describe(id).effect as MessageKey));
    await region.getByRole("button", { name: label, exact: true }).click();
    const dialog = world.page.getByRole("dialog");
    await expect(dialog.locator("[data-effect]")).toHaveText(t(describe(id).effect as MessageKey));
    await dialog.getByRole("button", { name: t("action.cancel") }).click();
    await expect(dialog).toHaveCount(0);
  }
  expect(await world.doubles.calls("payouts", { method: "POST" }), "nothing was done").toHaveLength(0);
});

// --- every state with who moved it -----------------------------------------------------------------

Given("a withdrawal request approved by one operator and confirmed paid by another", async ({ world }) => {
  await given(
    world,
    request(lena(world), {
      status: "COMPLETED",
      payoutStatus: "RESOLVED",
      decisions: [{ approved: true, operatorId: "op-alice", at: "2026-11-14T11:00:00Z" }],
      outcomes: [{ source: "operator", answer: "completed", reasonCode: null, reference: null, operatorId: "op-bob", at: "2026-11-15T09:30:00Z" }],
      transitions: [
        { status: "pending_review", at: "2026-11-14T10:00:00Z", by: "player" },
        { status: "approved", at: "2026-11-14T11:00:00Z", by: "operator", operatorId: "op-alice" },
        { status: "awaiting_manual_payout", at: "2026-11-14T11:00:01Z", by: "system", reason: "manual_terminal" },
        { status: "completed", at: "2026-11-15T09:30:00Z", by: "operator", operatorId: "op-bob" },
      ],
      settledAt: "2026-11-15T09:30:00Z",
    }),
  );
});

Then("each state it passed through is shown with the time and the operator or system that moved it", async ({ world }) => {
  const history = main(world).getByRole("list", { name: t("withdrawals.request.history") });
  const items = history.getByRole("listitem");
  await expect(items).toHaveCount(4);
  const expected: [MessageKey, string][] = [
    ["status.pending_review", t("withdrawals.actor.player")],
    ["status.approved", "op-alice"],
    ["status.awaiting_manual_payout", t("withdrawals.actor.system")],
    ["status.completed", "op-bob"],
  ];
  for (const [i, [status, actor]] of expected.entries()) {
    const item = items.nth(i);
    await expect(item).toContainText(t(status));
    await expect(item).toContainText(actor);
    await expect(item.locator("time")).toHaveCount(1);
  }
});

// --- a player's withdrawals ------------------------------------------------------------------------

const row = (player: string, n: number, status: string) => ({
  requestId: `7d1e4c2a-5b3f-4e6d-8a9c-00000000000${n}`,
  playerId: player,
  kycStatus: "verified",
  amount: 10000 * n,
  currency: "EUR",
  terminalName: "SEPA out",
  method: "bank_transfer",
  destinationLastFour: "7710",
  createdAt: `2026-11-1${n}T10:00:00Z`,
  status,
  payoutStatus: status === "COMPLETED" ? "RESOLVED" : "NONE",
});

Given("a player with withdrawal requests that are pending review, completed, rejected and cancelled", async ({ world }) => {
  const player = lena(world);
  const history = [row(player, 4, "PENDING_REVIEW"), row(player, 3, "COMPLETED"), row(player, 2, "REJECTED"), row(player, 1, "CANCELLED")];
  Object.assign(of(world), { player, history });
  await world.doubles.fixtures("payouts", {
    [`GET ${B}/players/{playerId}/withdrawal-history`]: { body: { requests: history, total: history.length, page: 1, pageSize: 50 } },
  });
});

When("an operator lists the player's withdrawal requests", async ({ world }) => {
  await world.signInAs("brand_support", { to: `/players/${of(world).player}/withdrawals` });
});

Then("all four are listed", async ({ world }) => {
  const rows = main(world).locator("tbody tr[data-row]");
  await expect(rows).toHaveCount(4);
  for (const [i, status] of ["status.pending_review", "status.completed", "status.rejected", "status.cancelled"].entries())
    await expect(rows.nth(i)).toContainText(t(status as MessageKey));
  const asked = await world.doubles.calls("payouts", { path: /withdrawal-history$/ });
  expect(asked.length).toBeGreaterThan(0);
  expect(asked.every((c) => !new URLSearchParams(c.query).has("status")), "every state is asked for").toBe(true);
});

// features/backoffice/configuration.feature (T103; US9, FR-034, FR-035): a configuration value that
// differs from the brand's default is marked; a routing rule is simulated, deployed and rolled back;
// an invitation above the inviter's payments role is refused. The owners are the doubles: the wallet and
// payouts for the withdrawal settings, merchant (shared `payments`) for the routing rules and operators.
//
// The first scenario's value is the wallet's withdrawal turnover multiplier, whose default is 1
// (TurnoverTerms.Default), on the brand's withdrawal settings (research, *Configuration*: no owner keeps a
// minimum withdrawal, which the scenario named before Trevor's decision).
// The second and third scenarios wait on the payments pages (T113) and
// the operators page (T114): the actions and messages they name are looked up here and fail by name until
// those tasks define them.
import { has, render, t, type MessageKey } from "../../../src/shared/messages/index.ts";
import { explain } from "../../../src/shared/refusals.ts";
import { Actions, type ActionDescription } from "../../../src/web/actions/index.ts";
import type { Identity } from "../../doubles/realm.ts";
import { expect, Given, Then, When, type World } from "../world.ts";

const P = "/v1/casino/backoffice/{brand}";
const B = "/v1/casino/brands/{brand}";
const main = (w: World) => w.page.getByRole("main");

/** An action a later task defines, by its id; fails naming the task until it does. */
function action(id: string, task: string): ActionDescription {
  const a = (Actions as Record<string, ActionDescription>)[id];
  if (!a) throw new Error(`no action "${id}" yet (${task})`);
  return a;
}
/** A message a later task defines, by its key; fails naming the task until it does. */
function message(key: string, task: string): string {
  if (!has(key)) throw new Error(`no message "${key}" yet (${task})`);
  return t(key as MessageKey);
}

// --- a value that differs from the default --------------------------------------------------------------

Given("a brand whose withdrawal turnover multiplier is set to 3, where its default is 1", async ({ world }) => {
  await world.doubles.fixtures("wallet", {
    [`GET ${P}/wallet-config`]: {
      body: {
        contributionRateByGameType: {},
        bonusWageringCounts: true,
        partialSplitsAllowed: true,
        maxConcurrentAwards: null,
        turnover: { multiplier: 3, definition: "gross_wagered" },
        updatedBy: "op-brand-admin",
        updatedAt: "2026-11-10T09:00:00Z",
      },
    },
  });
  await world.doubles.fixtures("payouts", { [`GET ${B}/withdrawal-config`]: { body: { limits: {} } } });
});

When("a brand administrator opens the brand's withdrawal settings", async ({ world }) => {
  await world.signInAs("brand_admin", { to: "/configuration/withdrawals" });
  await expect(main(world).getByRole("heading", { level: 1, name: message("config.withdrawals.title", "T109") })).toBeVisible();
});

Then("the withdrawal turnover multiplier is marked as differing from the default", async ({ world }) => {
  const field = main(world).getByLabel(message("config.withdrawals.multiplier", "T109"));
  await expect(field).toHaveValue("3");
  await expect(field).toHaveAccessibleDescription(new RegExp(t("config.form.differsFromDefault", { value: "1" }).replace(/[()]/g, "\\$&")));
});

// --- a routing rule: draft, simulate, deploy, roll back --------------------------------------------------

const SubBrand = "0b6f2a4e-3c1d-4e8f-9a7b-5c6d7e8f9a0b";
const Draft = {
  rootNodeId: "r",
  nodes: {
    r: { nodeId: "r", branches: [{ conditions: [{ conditionType: "COUNTRY", operator: "IN", value: "BR" }], nextNodeId: "t" }] },
    t: { nodeId: "t", terminals: ["pix-main"] },
  },
};
const Live = { rootNodeId: "r", nodes: { r: { nodeId: "r", terminals: ["card-main"] } } };
const paymentsAdmin: Identity = { sub: "op-payments-admin", name: "Paulo Payments", roles: ["brand_goldvibe", "resource_admin"] };

Given("a draft routing rule", async ({ world }) => {
  await world.doubles.fixtures("merchant.payments", {
    "GET /v1/sub-brands": { body: { subBrands: [{ id: SubBrand, name: "goldvibe", brand: "goldvibe", status: "active" }] } },
    "GET /v1/payments/routing-rules/state": {
      body: {
        liveVersion: 2,
        liveDeployedAt: "2026-11-01T10:00:00Z",
        history: [
          { version: 1, deployedAt: "2026-10-01T10:00:00Z" },
          { version: 2, deployedAt: "2026-11-01T10:00:00Z" },
        ],
        draft: Draft,
        liveTree: Live,
      },
    },
    "POST /v1/payments/routing-rules/simulate": { body: { outcome: "MATCH", terminals: ["pix-main"] } },
    "POST /v1/payments/routing-rules/deploy": { body: { version: 3, deployedAt: "2026-11-16T10:00:00Z" } },
    "POST /v1/payments/routing-rules/rollback/{version}": { status: 204, body: null },
  });
});

/** Takes the action and confirms it, as the payments page offers it (T113). */
async function take(w: World, a: ActionDescription) {
  await main(w).getByRole("button", { name: t(a.label) }).first().click();
  const dialog = w.page.getByRole("dialog");
  await expect(dialog).toContainText(t(a.effect));
  await dialog.getByRole("button", { name: t("action.confirm") }).click();
  await expect(dialog).toBeHidden();
}

When("a payments administrator simulates it, deploys it and rolls it back", async ({ world }) => {
  await world.signInAs(paymentsAdmin, { to: "/payments/routing" });
  await expect(main(world).getByRole("heading", { level: 1 })).toBeVisible();
  await take(world, action("routing.simulate", "T113"));
  await take(world, action("routing.deploy", "T113"));
  await take(world, action("routing.rollback", "T113"));
});

Then("simulating it showed which terminal each deposit it was tried with would reach", async ({ world }) => {
  const simulated = await world.doubles.calls("merchant.payments", { method: "POST", path: "/v1/payments/routing-rules/simulate" });
  expect(simulated.length).toBeGreaterThan(0);
  for (const c of simulated) expect((JSON.parse(c.body) as { tree?: unknown }).tree).toEqual(Draft);
  await expect(main(world)).toContainText("pix-main");
});

Then("the rule in force is the one before it", async ({ world }) => {
  await world.doubles.expectAuthorizedAs("merchant.payments", paymentsAdmin.sub, { method: "POST", path: /^\/v1\/payments\/routing-rules\/(deploy|rollback)/ });
  // deployed as version 3; rolled back to the version in force before it, 2
  const rolledBack = await world.doubles.calls("merchant.payments", { method: "POST", path: /^\/v1\/payments\/routing-rules\/rollback\// });
  expect(rolledBack.map((c) => c.path)).toEqual(["/v1/payments/routing-rules/rollback/2"]);
});

// --- an invitation above the inviter's role ---------------------------------------------------------------

/** merchant's refusal of a role the inviter does not strictly outrank (OperatorsEndpoint: `Privilege.canAssignRole`). */
const Escalation = '{"error":"PRIVILEGE_ESCALATION","message":"PRIVILEGE_ESCALATION"}';

Given("an operator holding the {string} role", async ({ world }, role: string) => {
  const who: Identity = { sub: `op-${role.replace(/_/g, "-")}`, name: `${role} operator`, roles: ["brand_goldvibe", role] };
  await world.doubles.fixtures("merchant.payments", {
    "GET /v1/operators": { body: { operators: [], total: 0 } },
    "POST /v1/operators": { status: 403, body: JSON.parse(Escalation) as unknown },
  });
  await world.signInAs(who, { to: "/team" });
});

When("the operator invites a person with the {string} role", async ({ world }, role: string) => {
  const invite = action("operator.invite", "T114");
  await main(world).getByRole("button", { name: t(invite.label) }).first().click();
  const dialog = world.page.getByRole("dialog");
  await expect(dialog).toContainText(t(invite.effect));
  await dialog.getByLabel(message("team.invite.email", "T114")).fill("new.operator@example.test");
  await dialog.getByLabel(message("team.invite.role", "T114")).selectOption(role);
  await dialog.getByRole("button", { name: t("action.confirm") }).click();
});

Then("the invitation is refused because the role is above the operator's own", async ({ world }) => {
  const dialog = world.page.getByRole("dialog");
  await expect(dialog).toContainText(render(explain(403, Escalation).message));
  const asked = await world.doubles.calls("merchant.payments", { method: "POST", path: "/v1/operators" });
  expect(asked.map((c) => (JSON.parse(c.body) as { role?: string }).role)).toEqual(["resource_admin"]);
});

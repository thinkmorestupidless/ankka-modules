// @vitest-environment jsdom
import { afterEach, describe, expect, test, vi } from "vitest";
import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import { Action, RefusalNotice } from "../../src/web/components/action/index.ts";
import { Actions, offeredIn, reasonGiven, withReason, type ActionDescription } from "../../src/web/actions/index.ts";
import { table } from "../../src/server/routes.ts";
import { has, render as text, t } from "../../src/shared/messages/index.ts";
import { explain } from "../../src/shared/refusals.ts";
import { OperatorBags } from "./bags.ts";

afterEach(cleanup);

const finance = { permissions: OperatorBags.brand_finance! };
const support = { permissions: OperatorBags.brand_support! };
const ok = () => Promise.resolve(new Response("{}", { status: 200 }));

const openDialog = (name: string) => act(() => fireEvent.click(screen.getByRole("button", { name })));
const confirmButton = () => screen.getByRole("button", { name: "Confirm" }) as HTMLButtonElement;

describe("the actions' descriptions", () => {
  test("each names a permission an operator route requires, and messages the catalogue has", () => {
    const required = new Set(table.map((r) => r.permission));
    for (const [id, a] of Object.entries(Actions) as [string, ActionDescription][]) {
      expect(required.has(a.permission), id).toBe(true);
      expect(has(a.label) && has(a.effect), id).toBe(true);
    }
  });

  test("an action is offered only in the states its record allows", () => {
    expect(offeredIn(Actions["withdrawal.approve"], "pending_review")).toBe(true);
    expect(offeredIn(Actions["withdrawal.approve"], "PENDING_REVIEW")).toBe(true);
    expect(offeredIn(Actions["withdrawal.approve"], "approved")).toBe(false);
    expect(offeredIn(Actions["withdrawal.approve"], undefined)).toBe(false);
    expect(offeredIn(Actions["wallet.adjust"], undefined)).toBe(true);
    // a payment settled by hand only while it waits for one (WithdrawalWorkflow.manual), never while in flight
    expect(offeredIn(Actions["withdrawal.confirmPayout"], "needs_review")).toBe(true);
    expect(offeredIn(Actions["withdrawal.markPayoutFailed"], "awaiting_manual_payout")).toBe(true);
    expect(offeredIn(Actions["withdrawal.confirmPayout"], "approved")).toBe(false);
  });

  test("a required reason must be more than blanks; it goes in the owner's field", () => {
    expect(reasonGiven(Actions["withdrawal.reject"], "  ")).toBe(false);
    expect(reasonGiven(Actions["withdrawal.reject"], "duplicate")).toBe(true);
    expect(reasonGiven(Actions["withdrawal.confirmPayout"], "")).toBe(true);
    expect(withReason(Actions["withdrawal.confirmPayout"], "paid by bank", { reference: "x" })).toEqual({ reference: "x", note: "paid by bank" });
    expect(withReason(Actions["withdrawal.reject"], "duplicate")).toEqual({ reason: "duplicate" });
    expect(withReason(Actions["withdrawal.approve"], "ignored")).toEqual({});
  });
});

describe("an action offered", () => {
  test("states its effect before it is confirmed", async () => {
    const run = vi.fn(ok);
    render(<Action action={Actions["withdrawal.approve"]} me={finance} bags={OperatorBags} state="pending_review" run={run} subject={<span>{"BRL 100,00"}</span>} />);
    await openDialog("Approve");
    const dialog = screen.getByRole("dialog", { name: "Approve" });
    expect(dialog.textContent).toContain(t("action.withdrawal.approve.effect"));
    expect(dialog.textContent).toContain("BRL 100,00");
    expect(run).not.toHaveBeenCalled();
    expect(screen.queryByRole("textbox")).toBeNull(); // approve records no reason
    await act(async () => fireEvent.click(confirmButton()));
    expect(run).toHaveBeenCalledWith(undefined);
    expect(screen.queryByRole("dialog")).toBeNull();
  });

  test("requires a reason where the owner records one, and sends it trimmed", async () => {
    const run = vi.fn(ok);
    const onDone = vi.fn();
    render(<Action action={Actions["withdrawal.reject"]} me={finance} bags={OperatorBags} state="pending_review" run={run} onDone={onDone} />);
    await openDialog("Reject");
    const reason = screen.getByLabelText("Reason (recorded with the action)") as HTMLTextAreaElement;
    expect(reason.required).toBe(true);
    expect(confirmButton().disabled).toBe(true);
    fireEvent.change(reason, { target: { value: "   " } });
    expect(confirmButton().disabled).toBe(true);
    fireEvent.change(reason, { target: { value: " duplicate request " } });
    expect(confirmButton().disabled).toBe(false);
    await act(async () => fireEvent.click(confirmButton()));
    expect(run).toHaveBeenCalledWith("duplicate request");
    expect(onDone).toHaveBeenCalledOnce();
  });

  test("an optional note may be left empty", async () => {
    render(<Action action={Actions["withdrawal.confirmPayout"]} me={finance} bags={OperatorBags} state="awaiting_manual_payout" run={ok} />);
    await openDialog("Confirm payout");
    expect(screen.getByLabelText("Note (optional)")).toBeTruthy();
    expect(confirmButton().disabled).toBe(false);
  });

  test("is not shown in a state that does not allow it", () => {
    const { container } = render(<Action action={Actions["withdrawal.approve"]} me={finance} bags={OperatorBags} state="completed" run={ok} />);
    expect(container.innerHTML).toBe("");
  });
});

describe("an action the operator may not take", () => {
  test("is shown disabled, with the roles that give it (rolesGiving)", () => {
    render(<Action action={Actions["withdrawal.approve"]} me={support} bags={OperatorBags} state="pending_review" run={ok} />);
    const button = screen.getByRole("button", { name: "Approve" }) as HTMLButtonElement;
    expect(button.disabled).toBe(true);
    const needs = document.getElementById(button.getAttribute("aria-describedby")!)!;
    expect(needs.textContent).toBe("Needs a role that gives it: brand_finance, brand_admin");
    expect(needs.querySelector("svg[data-glyph='lock']")).not.toBeNull();
  });

  test("names the brand's own role when it gives the permission", () => {
    const bags = { ...OperatorBags, payouts_desk: ["withdrawal.read", "withdrawal.review"] };
    render(<Action action={Actions["withdrawal.approve"]} me={support} bags={bags} state="pending_review" run={ok} />);
    expect(screen.getByText("Needs a role that gives it: brand_finance, brand_admin, payouts_desk")).toBeTruthy();
  });

  test("names the permission while the brand's roles are not yet read", () => {
    render(<Action action={Actions["withdrawal.approve"]} me={support} bags={undefined} state="pending_review" run={ok} />);
    expect(screen.getByText("Needs the withdrawal.review permission")).toBeTruthy();
  });

  test("is not shown at all when no role of the brand gives it", () => {
    const { container } = render(<Action action={Actions["withdrawal.approve"]} me={support} bags={{ brand_support: ["player.read"] }} state="pending_review" run={ok} />);
    expect(container.innerHTML).toBe("");
  });
});

describe("a refusal", () => {
  test("is shown with its reason and next step, and the dialog stays open", async () => {
    const body = '{"error":"reason_required","message":"a reason is needed"}';
    const run = vi.fn(() => Promise.resolve(new Response(body, { status: 400 })));
    render(<Action action={Actions["player.suspend"]} me={{ permissions: ["standing.change"] }} bags={OperatorBags} state="active" run={run} />);
    await openDialog("Suspend");
    fireEvent.change(screen.getByRole("textbox"), { target: { value: "chargebacks" } });
    await act(async () => fireEvent.click(confirmButton()));
    const expected = explain(400, body, OperatorBags);
    const alert = screen.getByRole("alert");
    expect(alert.textContent).toBe(text(expected.message) + text(expected.next!));
    expect(alert.textContent).toBe("A reason is needed.Write why, then confirm again.");
    expect(screen.getByRole("dialog")).toBeTruthy();
  });

  test("forbidden names the roles that would give the permission", async () => {
    const body = '{"error":"forbidden","message":"forbidden: the standing.change permission is needed"}';
    render(<Action action={Actions["player.suspend"]} me={{ permissions: ["standing.change"] }} bags={OperatorBags} state="active" run={() => Promise.resolve(new Response(body, { status: 403 }))} />);
    await openDialog("Suspend");
    fireEvent.change(screen.getByRole("textbox"), { target: { value: "x" } });
    await act(async () => fireEvent.click(confirmButton()));
    expect(screen.getByRole("alert").textContent).toBe("You need the standing.change permission for this.A role that gives it: brand_compliance, brand_admin.");
  });

  test("a code the backoffice does not know is shown as given", () => {
    render(<RefusalNotice refusal={explain(409, '{"error":"turnover_outstanding","message":"the bonus still needs 3,000 of turnover"}')} />);
    expect(screen.getByRole("alert").textContent).toBe("turnover_outstanding: the bonus still needs 3,000 of turnover");
  });

  test("an unreachable service is said so, with what to do", async () => {
    render(<Action action={Actions["withdrawal.approve"]} me={finance} bags={OperatorBags} state="pending_review" run={() => Promise.reject(new TypeError("fetch failed"))} />);
    await openDialog("Approve");
    await act(async () => fireEvent.click(confirmButton()));
    expect(screen.getByRole("alert").textContent).toBe(t("action.unreachable") + t("action.unreachable.next"));
  });
});

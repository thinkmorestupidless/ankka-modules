// T081, T082: what the withdrawals area makes of payouts' answers. The amounts a request shows by its state
// (FR-014), the state its actions are offered in (FR-026), the wallet's major units back to minor units,
// and the queue's place as payouts' query.
import { describe, expect, test } from "vitest";
import { Actions, offeredIn } from "../../src/web/actions/index.ts";
import { actionStateOf, amountsOf, minorOf, type Request } from "../../src/web/areas/withdrawals/read.ts";
import { queryOf } from "../../src/web/areas/withdrawals/queue.tsx";

const request = (over: Partial<Request>): Request => ({
  requestId: "r-1", playerId: "p-1", kycStatus: "verified", amount: 50000, paidAmount: 30000, voidedAmount: 20000, currency: "EUR",
  status: "PENDING_REVIEW", payoutStatus: "NONE", createdAt: "2026-11-14T10:00:00Z", ...over,
});

describe("a request's amounts", () => {
  test("pending: requested, held and voided", () => {
    expect(amountsOf(request({}))).toEqual([{ which: "requested", minor: 50000 }, { which: "held", minor: 30000 }, { which: "voided", minor: 20000 }]);
  });
  test("completed: paid in place of held", () => {
    expect(amountsOf(request({ status: "COMPLETED", voidedAmount: 0 }))).toEqual([{ which: "requested", minor: 50000 }, { which: "paid", minor: 30000 }]);
  });
  test("rejected: the whole hold returned, the voided part with it", () => {
    expect(amountsOf(request({ status: "REJECTED" })).map((a) => [a.which, a.minor])).toEqual([["requested", 50000], ["returned", 50000], ["voided", 20000]]);
  });
});

describe("the state an action is offered in", () => {
  const offered = (r: Partial<Request>) =>
    (["withdrawal.approve", "withdrawal.reject", "withdrawal.confirmPayout", "withdrawal.markPayoutFailed"] as const).filter((a) => offeredIn(Actions[a], actionStateOf(request(r))));
  test.each([
    [{ status: "PENDING_REVIEW" }, ["withdrawal.approve", "withdrawal.reject"]],
    [{ status: "AWAITING_MANUAL_PAYOUT" }, ["withdrawal.confirmPayout", "withdrawal.markPayoutFailed"]],
    [{ status: "APPROVED", payoutStatus: "NEEDS_REVIEW" }, ["withdrawal.confirmPayout", "withdrawal.markPayoutFailed"]],
    [{ status: "APPROVED", payoutStatus: "INITIATED" }, []],
    [{ status: "COMPLETED", payoutStatus: "RESOLVED" }, []],
    [{ status: "REJECTED" }, []],
  ])("%j offers %j", (r, actions) => expect(offered(r)).toEqual(actions));
});

describe("the wallet's major units", () => {
  test.each([
    ["700.00", "70000"],
    ["0.05", "5"],
    ["-12.34", "-1234"],
    ["0.00", "0"],
  ])("%s is %s minor units", (major, minor) => expect(minorOf(major)).toBe(minor));
  test("anything else is unknown", () => expect([minorOf("7"), minorOf(undefined), minorOf("1.5")]).toEqual([undefined, undefined, undefined]));
});

describe("the queue's place as payouts' query", () => {
  test("pending review newest first by default", () => {
    expect(queryOf({ filters: {}, page: 1, scroll: 0 })).toEqual({ status: "PENDING_REVIEW", page: "1", pageSize: "50" });
  });
  test("payments needing review, oldest first, filtered and searched", () => {
    expect(queryOf({ filters: { payout: "needs_review", status: "COMPLETED", kyc: "verified", band: "gt5000", method: "CARD", q: "ana" }, sort: "created", page: 2, scroll: 0 })).toEqual({
      status: "APPROVED", payoutStatus: "NEEDS_REVIEW", kyc: "verified", amountRange: "gt5000", method: "CARD", q: "ana", sort: "oldest", page: "2", pageSize: "50",
    });
  });
});

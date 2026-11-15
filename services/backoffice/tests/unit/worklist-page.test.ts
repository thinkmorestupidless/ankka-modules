// T064: what the worklist page makes of the server's answer: every kind's items merged oldest first, the
// place an operator is put at once an item is gone, and who acted on a request no longer waiting.
import { describe, expect, test } from "vitest";
import { actedOn, investigationOf, merged, placeAfter, stillOpen, type Item, type Request } from "../../src/web/areas/worklist/read.ts";

const item = (kind: string, id: string, since: string, player?: string): Item => ({ kind, id, since, subject: { player }, detail: {} });

describe("the worklist's rows", () => {
  test("every kind read, oldest first; an unavailable kind adds none", () => {
    const rows = merged([
      { kind: "fraud_cases", state: "ok", total: 1, page: 1, items: [item("fraud_cases", "c-1", "2026-11-12T00:00:00Z")] },
      { kind: "documents_awaiting", state: "ok", total: 2, page: 1, items: [item("documents_awaiting", "p-a", "2026-11-10T00:00:00Z"), item("documents_awaiting", "p-b", "2026-11-13T00:00:00Z")] },
      { kind: "players_flagged", state: "unavailable", reason: "timeout" },
    ]);
    expect(rows.map((r) => r.id)).toEqual(["p-a", "c-1", "p-b"]);
  });

  test("once an item is gone the operator is at the one that took its place, or the one before at the end", () => {
    const rows = ["a", "b", "c"].map((id, i) => item("withdrawals_pending", id, `2026-11-1${i}T00:00:00Z`));
    expect(placeAfter(rows, "withdrawals_pending:b")).toBe("withdrawals_pending:c");
    expect(placeAfter(rows, "withdrawals_pending:c")).toBe("withdrawals_pending:b");
    expect(placeAfter(rows.slice(0, 1), "withdrawals_pending:a")).toBeUndefined();
  });

  test("an item's investigation: the request, the player's identity, the fraud case", () => {
    expect(investigationOf(item("withdrawals_pending", "w-1", "2026-11-10T00:00:00Z", "p-a"))).toBe("/withdrawals/w-1");
    expect(investigationOf(item("documents_awaiting", "p-a", "2026-11-10T00:00:00Z", "p-a"))).toBe("/players/p-a/identity");
    expect(investigationOf(item("fraud_cases", "c-1", "2026-11-10T00:00:00Z", "p-a"))).toBe("/fraud?case=c-1");
  });
});

describe("a request someone acted on", () => {
  const r = (over: Partial<Request>): Request => ({ requestId: "w-1", playerId: "p-a", amount: 1, currency: "EUR", status: "PENDING_REVIEW", payoutStatus: "NONE", createdAt: "2026-11-10T00:00:00Z", ...over });

  test("waits while pending review, for its kind", () => {
    expect(stillOpen(r({}), "withdrawals_pending")).toBe(true);
    expect(stillOpen(r({ status: "APPROVED" }), "withdrawals_pending")).toBe(false);
    expect(stillOpen(r({ status: "APPROVED", payoutStatus: "NEEDS_REVIEW" }), "payouts_needing_review")).toBe(true);
  });

  test("names the operator of its last decision, what they decided and when", () => {
    expect(actedOn(r({ status: "REJECTED", decisions: [{ approved: false, operatorId: "op-2", at: "2026-11-15T12:00:00Z" }] }))).toEqual({ what: "rejected", operator: "op-2", at: "2026-11-15T12:00:00Z" });
    expect(actedOn(r({ status: "CANCELLED" }))).toBeUndefined();
  });
});

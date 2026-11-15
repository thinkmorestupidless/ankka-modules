// @vitest-environment jsdom
// T095 to T097: the identity queue paged in kyc's order with its count; a document's review offered
// only while it is pending and a rejection only with one of kyc's reasons; a limit's value as
// responsible-gambling takes it; a return offered only once a time-limited exclusion has ended.
import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { ReactNode } from "react";
import type { Me } from "../../src/shared/access.ts";
import { t } from "../../src/shared/messages/index.ts";
import { pageOf, PageSize, waitingSince } from "../../src/web/areas/identity/queue.tsx";
import type { QueueRow } from "../../src/web/areas/identity/kyc.ts";
import { PlayerIdentity } from "../../src/web/areas/players/identity.tsx";
import { exclusionState, valueOf } from "../../src/web/areas/players/protection.tsx";
import { MeContext, ReadOnlyContext } from "../../src/web/frame/context.ts";

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

const row = (playerId: string, at: string): QueueRow => ({
  playerId, status: "pending", mode: "automatic", awaiting: 1, oldestAwaitingAt: at, readyAt: null, flags: [], flaggedAt: null, sof: "unverified", depositsByCurrency: {}, updatedAt: at, sinceSeconds: 10,
});

describe("the identity queue", () => {
  it("is paged in kyc's order, totalled by kyc's count, and not complete while kyc holds more", () => {
    const rows = Array.from({ length: 120 }, (_, i) => row(`p-${i}`, new Date(Date.UTC(2026, 10, 1, 0, i)).toISOString()));
    const first = pageOf(rows, { awaiting: 120 }, "awaiting", 1);
    expect(first.shown.map((r) => r.playerId)).toEqual(rows.slice(0, PageSize).map((r) => r.playerId));
    expect(first).toMatchObject({ total: 120, complete: false, capped: false });
    expect(pageOf(rows, { awaiting: 120 }, "awaiting", 3)).toMatchObject({ complete: true });
    // kyc answers at most 1,000 rows; a count beyond what it gave is not complete
    expect(pageOf(rows, { awaiting: 1500 }, "awaiting", 3)).toMatchObject({ complete: false, capped: true });
    // the flagged filter has no single count
    expect(pageOf(rows, {}, "flagged", 1).total).toBeUndefined();
  });

  it("waits from the filter's own time, as kyc orders by it", () => {
    const r = { ...row("p", "2026-11-01T00:00:00Z"), readyAt: "2026-11-02T00:00:00Z", flaggedAt: "2026-11-03T00:00:00Z", updatedAt: "2026-11-04T00:00:00Z" };
    expect(waitingSince("awaiting", r)).toBe("2026-11-01T00:00:00Z");
    expect(waitingSince("ready", r)).toBe("2026-11-02T00:00:00Z");
    expect(waitingSince("sof", r)).toBe("2026-11-03T00:00:00Z");
    expect(waitingSince("verified", r)).toBe("2026-11-04T00:00:00Z");
  });
});

describe("a limit's value", () => {
  it("is minor units of its currency, or whole minutes", () => {
    expect(valueOf("DEPOSIT_DAILY", "200.00", "EUR")).toBe("20000");
    expect(valueOf("DEPOSIT_DAILY", "5000", "JPY")).toBe("5000");
    expect(valueOf("LOSS_WEEKLY", "1.500", "KWD")).toBe("1500");
    expect(valueOf("DEPOSIT_DAILY", "1.234", "EUR")).toBeUndefined();
    expect(valueOf("DEPOSIT_DAILY", "0", "EUR")).toBeUndefined();
    expect(valueOf("DEPOSIT_DAILY", "100", null)).toBeUndefined();
    expect(valueOf("SESSION_MINUTES", "90", null)).toBe("90");
    expect(valueOf("SESSION_MINUTES", "1.5", null)).toBeUndefined();
  });
});

describe("the return", () => {
  const now = Date.parse("2026-11-15T12:00:00Z");
  const excluded = (until: string | null, permanent = false) => ({ exclusion: { permanent, until, since: "2026-01-01T00:00:00Z", trigger: "player" }, returnRequest: null });
  it("is offered only once a time-limited exclusion has ended, and once", () => {
    expect(exclusionState({ exclusion: null, returnRequest: null }, now)).toBe("not_excluded");
    expect(exclusionState(excluded("2026-12-01T00:00:00Z"), now)).toBe("running");
    expect(exclusionState(excluded("2026-11-01T00:00:00Z"), now)).toBe("ended");
    expect(exclusionState(excluded(null, true), now)).toBe("permanent");
    expect(exclusionState({ ...excluded("2026-11-01T00:00:00Z"), returnRequest: { requestedAt: "x", reinstatesAt: "y", actor: "operator" } }, now)).toBe("requested");
  });
});

const compliance: Me = {
  subject: "op-c",
  name: "Compliance",
  roles: ["brand_goldvibe", "brand_compliance"],
  brand: { slug: "goldvibe", name: "goldvibe", modules: null },
  preferences: null,
  permissions: ["player.read", "identity.read", "identity.review", "protection.read", "protection.change", "fraud.review"],
};

function wrap(children: ReactNode, me: Me = compliance) {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MeContext.Provider value={me}>
        <ReadOnlyContext.Provider value={false}>{children}</ReadOnlyContext.Provider>
      </MeContext.Provider>
    </QueryClientProvider>,
  );
}

const identity = {
  playerId: "p-ana", status: "pending", mode: "automatic", readyAt: null, sof: "unverified", requiredOfPlayer: [], deadline: null, depositsByCurrency: { BRL: 150000 }, flags: [], missingProfileFields: [], other: [],
  categories: [
    { category: "identity_front", requiredByPolicy: true, requiredOfPlayer: false, document: { documentId: "d-1", category: "identity_front", status: "pending", uploadedAt: "2026-11-10T09:00:00Z", reviewedAt: null, reasonCode: null, submittedBy: "player", reviewedBy: null } },
    { category: "proof_of_address", requiredByPolicy: true, requiredOfPlayer: false, document: { documentId: "d-2", category: "proof_of_address", status: "approved", uploadedAt: "2026-11-09T09:00:00Z", reviewedAt: "2026-11-09T10:00:00Z", reasonCode: null, submittedBy: "player", reviewedBy: "op-x" } },
  ],
};

function kyc() {
  const calls: { method: string; url: string; body: unknown }[] = [];
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: string, init: RequestInit = {}) => {
      const method = init.method ?? "GET";
      calls.push({ method, url, body: init.body ? JSON.parse(String(init.body)) : undefined });
      if (url === "/api/permissions") return Response.json({ roles: { brand_compliance: compliance.permissions } });
      if (method === "GET" && url === "/api/s/kyc/p-ana") return Response.json(identity);
      return Response.json({ status: "pending" });
    }),
  );
  return calls;
}

describe("a player's documents", () => {
  it("are viewed through the server's document route by their ids alone", async () => {
    kyc();
    wrap(<PlayerIdentity player="p-ana" />);
    const front = (await screen.findAllByRole("row")).find((r) => r.getAttribute("data-document") === "d-1")!;
    expect(within(front).getByRole("link", { name: t("identity.document.view") }).getAttribute("href")).toBe("/api/kyc/p-ana/documents/d-1/view");
  });

  it("offer a review only while pending, and a rejection only with one of kyc's reasons", async () => {
    const calls = kyc();
    wrap(<PlayerIdentity player="p-ana" />);
    const rows = await screen.findAllByRole("row");
    const approved = rows.find((r) => r.getAttribute("data-document") === "d-2")!;
    expect(within(approved).queryByRole("button", { name: t("action.identity.approveDocument.label") })).toBeNull();
    const pending = rows.find((r) => r.getAttribute("data-document") === "d-1")!;
    fireEvent.click(within(pending).getByRole("button", { name: t("action.identity.rejectDocument.label") }));
    const dialog = await screen.findByRole("dialog");
    expect(dialog.textContent).toContain(t("action.identity.rejectDocument.effect"));
    const confirm = within(dialog).getByRole("button", { name: t("action.confirm") });
    expect((confirm as HTMLButtonElement).disabled).toBe(true);
    fireEvent.change(within(dialog).getByLabelText(t("identity.document.rejectReason")), { target: { value: "document_expired" } });
    expect((confirm as HTMLButtonElement).disabled).toBe(false);
    fireEvent.click(confirm);
    await waitFor(() => expect(calls.some((c) => c.method === "POST")).toBe(true));
    expect(calls.find((c) => c.method === "POST")).toMatchObject({ url: "/api/s/kyc/p-ana/reviews/d-1", body: { outcome: "rejected", reasonCode: "document_expired" } });
  });
});

// T073: the player's timeline and standing. Lines from every source merged newest first; a source that
// holds more than it gave holds the merge back to its oldest line, and `next` starts the next page
// there; a source that fails or is late is a gap with its period, the others shown; actors named.
import { describe, expect, test } from "vitest";
import { actorOf, operatorNames, timeline } from "../../src/server/composite/timeline.ts";
import { minorOf, standing } from "../../src/server/composite/standing.ts";
import { table } from "../../src/server/routes.ts";

const P = "/v1/casino/backoffice/goldvibe";
const B = "/v1/casino/brands/goldvibe";
const Now = Date.parse("2026-11-16T12:00:00Z");

type Answer = () => Response | Promise<Response>;

/** The owners by the path called (without the query); every call kept. */
function owners(answers: Record<string, Answer>) {
  const calls: string[] = [];
  const fetchFn = (async (url: string, init: RequestInit = {}) => {
    calls.push(url);
    const path = new URL(url).pathname;
    const signal = init.signal;
    const answer = answers[path] ?? (() => Response.json({ error: "not_found" }, { status: 404 }));
    return Promise.race([Promise.resolve().then(answer), new Promise<Response>((_, reject) => signal?.addEventListener("abort", () => reject(signal.reason), { once: true }))]);
  }) as typeof fetch;
  return { calls, fetchFn };
}

const deps = (fetchFn: typeof fetch) => ({
  brand: "goldvibe",
  servicesUrl: "http://services.local",
  sharedProjects: { payments: "payments", programme: "programme", reasoner: "reasoner", games: "games" },
  fetch: fetchFn,
  timeoutMs: 100,
  table,
  now: () => Now,
});
const all = () => true;

const empty = {
  [`/players${P}/players/p-1/activity`]: () => Response.json({ events: [], hasMore: false, next: null, total: 0 }),
  [`/players${P}/players/p-1/notes`]: () => Response.json({ notes: [], total: 0 }),
  [`/players${P}/players/p-1/messages`]: () => Response.json({ items: [], total: 0 }),
  [`/players${P}/players/p-1/profile`]: () => Response.json({ player: { playerId: "p-1", status: "active", currency: "EUR" }, wallet: null, activeSessions: [], pinnedNotes: [] }),
  [`/wallet${P}/wallets/p-1/EUR/transactions`]: () => Response.json({ transactions: [], total: 0 }),
  [`/payouts${B}/players/p-1/withdrawal-history`]: () => Response.json({ requests: [], total: 0 }),
  [`/kyc${P}/kyc/p-1/decisions`]: () => Response.json({ decisions: [] }),
  [`/responsible-gambling${P}/rg/p-1/history`]: () => Response.json([]),
} satisfies Record<string, Answer>;

describe("the timeline", () => {
  test("lines of every source, newest first, each with its actor and its words as a key", async () => {
    const { fetchFn } = owners({
      ...empty,
      [`/players${P}/players/p-1/notes`]: () => Response.json({ notes: [{ noteId: "n-1", text: "called", author: "op-1", pinned: false, createdAt: "2026-11-15T09:00:00Z" }], total: 1 }),
      [`/wallet${P}/wallets/p-1/EUR/transactions`]: () =>
        Response.json({ transactions: [{ transactionId: "t-1", type: "admin_adjust", walletType: "real", amount: "12.50", createdAt: "2026-11-15T10:00:00Z", reason: "goodwill", category: "compensation", cause: { kind: "round", ref: "r-9" }, actorId: "op-2" }], total: 1 }),
      [`/payouts${B}/players/p-1/withdrawal-history`]: () => Response.json({ requests: [{ requestId: "w-1", amount: 5000, currency: "EUR", createdAt: "2026-11-15T11:00:00Z", status: "PENDING_REVIEW", payoutStatus: "NONE" }], total: 1 }),
      [`/kyc${P}/kyc/p-1/decisions`]: () => Response.json({ decisions: [{ kind: "status_set", actor: { kind: "operator", id: "op-1" }, at: "2026-11-14T08:00:00Z", to: "verified" }] }),
    });
    const page = await timeline({ ...deps(fetchFn), names: async () => new Map([["op-2", "Olga Ops"]]) }, "p-1", {}, "tok", all);
    expect(page.lines.map((l) => l.source)).toEqual(["withdrawals", "adjustments", "notes", "identity"]);
    expect(page.gaps).toEqual([]);
    expect(page.next).toBeNull();
    const adjustment = page.lines[1]!;
    expect(adjustment.actor).toEqual({ kind: "operator", id: "op-2", name: "Olga Ops" });
    expect(adjustment.amount).toEqual({ minor: 1250, currency: "EUR" });
    expect(adjustment.cause).toEqual({ kind: "round", ref: "r-9" });
    expect(adjustment.summary.key).toBe("timeline.adjustments.made");
    expect(page.lines[0]!.actor).toEqual({ kind: "player" });
  });

  test("a source that holds more holds the merge back to its oldest line, and the next page starts there", async () => {
    const { calls, fetchFn } = owners({
      ...empty,
      [`/players${P}/players/p-1/activity`]: () =>
        Response.json({
          events: [
            { eventId: "e-1", category: "Games", eventType: "round_settled", timestamp: "2026-11-15T12:00:00Z", actor: "player", amountMinorUnits: -100, currency: "EUR" },
            { eventId: "e-0", category: "Games", eventType: "round_settled", timestamp: "2026-11-15T11:00:00Z", actor: "player", amountMinorUnits: -100, currency: "EUR" },
          ],
          hasMore: true,
          next: "c-2",
        }),
      [`/players${P}/players/p-1/notes`]: () => Response.json({ notes: [{ noteId: "n-1", text: "x", author: "op-1", pinned: false, createdAt: "2026-11-10T09:00:00Z" }] }),
    });
    const page = await timeline(deps(fetchFn), "p-1", { kinds: ["rounds", "notes"] }, "tok", all);
    // the line at the cut waits for the next page, which takes that instant too
    expect(page.lines.map((l) => l.id)).toEqual(["rounds/e-1"]);
    expect(page.next).toBe("2026-11-15T11:00:00.000Z");
    expect(calls.some((u) => u.includes("category=games"))).toBe(true);

    const second = await timeline(deps(owners({ ...empty, [`/players${P}/players/p-1/notes`]: () => Response.json({ notes: [{ noteId: "n-1", text: "x", author: "op-1", pinned: false, createdAt: "2026-11-10T09:00:00Z" }] }) }).fetchFn), "p-1", { kinds: ["rounds", "notes"], after: Date.parse(page.next!) }, "tok", all);
    expect(second.lines.map((l) => l.id)).toEqual(["notes/n-1"]);
    expect(second.to).toBe(page.next);
  });

  test("a line at the cut's very instant, of the holding source or another, is on the next page, not lost", async () => {
    const T = "2026-11-15T11:00:00Z";
    const rounds = (events: object[], hasMore: boolean) => () => Response.json({ events, hasMore });
    const round = (id: string, at: string) => ({ eventId: id, category: "Games", eventType: "round_settled", timestamp: at, actor: "player" });
    const note = { noteId: "n-1", text: "x", author: "op-1", pinned: false, createdAt: T };
    const first = await timeline(
      deps(owners({ ...empty, [`/players${P}/players/p-1/activity`]: rounds([round("e-2", "2026-11-15T12:00:00Z"), round("e-1", T)], true), [`/players${P}/players/p-1/notes`]: () => Response.json({ notes: [note] }) }).fetchFn),
      "p-1", { kinds: ["rounds", "notes"] }, "tok", all,
    );
    expect(first.lines.map((l) => l.id)).toEqual(["rounds/e-2"]);
    expect(first.next).toBe("2026-11-15T11:00:00.000Z");
    // players holds another round at that instant, which it gives the next page
    const second = owners({ ...empty, [`/players${P}/players/p-1/activity`]: rounds([round("e-1", T), round("e-0", T)], false), [`/players${P}/players/p-1/notes`]: () => Response.json({ notes: [note] }) });
    const page = await timeline(deps(second.fetchFn), "p-1", { kinds: ["rounds", "notes"], after: Date.parse(first.next!) }, "tok", all);
    expect(page.lines.map((l) => l.id).sort()).toEqual(["notes/n-1", "rounds/e-0", "rounds/e-1"]);
    expect(page.to).toBe(first.next);
    expect(second.calls.find((u) => u.includes("/activity"))).toContain(`to=${encodeURIComponent("2026-11-15T11:00:00.001Z")}`);
  });

  test("a source that takes no period and whose newest page is all after it is a gap, not a complete period", async () => {
    const messages = Array.from({ length: 100 }, (_, i) => ({ notificationId: `m-${i}`, createdAt: new Date(Now - i * 60_000).toISOString(), subject: "s", state: "sent" }));
    const { fetchFn } = owners({ ...empty, [`/players${P}/players/p-1/messages`]: () => Response.json({ items: messages, total: 400 }) });
    // a period ending a week ago: every message given is newer
    const to = Now - 7 * 86_400_000;
    const page = await timeline(deps(fetchFn), "p-1", { kinds: ["messages"], from: to - 86_400_000, to }, "tok", all);
    expect(page.lines).toEqual([]);
    expect(page.next).toBeNull();
    expect(page.gaps).toEqual([{ source: "messages", from: new Date(to - 86_400_000).toISOString(), to: new Date(to).toISOString(), reason: "beyond_page" }]);
  });

  test("a failed source and a late one are gaps with their period; the rest is shown", async () => {
    const { fetchFn } = owners({
      ...empty,
      [`/players${P}/players/p-1/activity`]: () => Response.json({ error: "boom" }, { status: 503 }),
      [`/kyc${P}/kyc/p-1/decisions`]: () => new Promise<Response>(() => undefined),
      [`/players${P}/players/p-1/notes`]: () => Response.json({ notes: [{ noteId: "n-1", text: "x", author: "op-1", pinned: true, createdAt: "2026-11-15T09:00:00Z" }] }),
    });
    const page2 = await timeline(deps(fetchFn), "p-1", {}, "tok", all);
    expect(page2.lines.map((l) => l.id)).toEqual(["notes/n-1"]);
    expect(page2.gaps.map((g) => g.source).sort()).toEqual(["account", "identity", "payments", "rewards", "rounds"]);
    const rounds = page2.gaps.find((g) => g.source === "rounds")!;
    expect(rounds).toEqual({ source: "rounds", from: "2026-10-17T12:00:00.000Z", to: "2026-11-16T12:00:00.000Z", reason: "unavailable" });
    expect(page2.gaps.find((g) => g.source === "identity")!.reason).toBe("late");
  });

  test("a source the operator's roles do not open is not asked and is no gap", async () => {
    const { calls, fetchFn } = owners(empty);
    const page = await timeline(deps(fetchFn), "p-1", {}, "tok", (p) => p !== "identity.read");
    expect(page.gaps).toEqual([]);
    expect(calls.some((u) => u.includes("/kyc/"))).toBe(false);
  });

  test.each([
    ["player", { kind: "player" }],
    ["system", { kind: "system" }],
    ["operator", { kind: "operator" }],
    ["operator:op-7", { kind: "operator", id: "op-7" }],
    ["op-7", { kind: "operator", id: "op-7" }],
    [{ kind: "provider" }, { kind: "provider" }],
    [{ kind: "operator", id: "op-1" }, { kind: "operator", id: "op-1" }],
  ])("the actor %j is %j", (given, expected) => expect(actorOf(given)).toEqual(expected));
});

describe("operators' names", () => {
  const Operators = "/merchant.payments/v1/operators";
  /** merchant as it lists operators: to a payments operator's token only. */
  function merchant() {
    let down = false;
    const { calls, fetchFn } = owners({
      [Operators]: () => (down ? Response.json({ error: "boom" }, { status: 503 }) : Response.json({ error: "forbidden" }, { status: 403 })),
    });
    const admitting = (async (url: string, init: RequestInit = {}) => {
      const auth = new Headers(init.headers).get("authorization");
      if (!down && auth === "Bearer payments-token" && new URL(url).pathname === Operators) {
        calls.push(url);
        return Response.json({ operators: [{ operatorId: "op-2", firstName: "Olga", lastName: "Ops" }] });
      }
      return fetchFn(url, init);
    }) as typeof fetch;
    return { calls, fetchFn: admitting, down: (d: boolean) => void (down = d) };
  }

  test("are each operator's own answer from merchant: a refused one never blanks, nor is shown, another's", async () => {
    const m = merchant();
    const names = operatorNames(deps(m.fetchFn), () => Now);
    expect(await names(["op-2"], "brand-token", { sub: "op-1" }, all)).toEqual(new Map());
    expect(await names(["op-2"], "payments-token", { sub: "op-9" }, all)).toEqual(new Map([["op-2", "Olga Ops"]]));
    expect(await names(["op-2"], "brand-token", { sub: "op-1" }, all)).toEqual(new Map());
    // each kept for its operator: no further ask within five minutes
    expect(m.calls).toHaveLength(2);
  });

  test("a failure is not kept: the next timeline asks merchant again", async () => {
    const m = merchant();
    const names = operatorNames(deps(m.fetchFn), () => Now);
    m.down(true);
    expect(await names(["op-2"], "payments-token", { sub: "op-9", name: "Nina" }, all)).toEqual(new Map());
    m.down(false);
    expect(await names(["op-2", "op-9"], "payments-token", { sub: "op-9", name: "Nina" }, all)).toEqual(new Map([["op-2", "Olga Ops"], ["op-9", "Nina"]]));
  });
});

describe("the standing", () => {
  test("each part with its own state: one owner down leaves the others shown", async () => {
    const { fetchFn } = owners({
      [`/players${P}/players/p-1/profile`]: () =>
        Response.json({ player: { playerId: "p-1", status: "active", email: "a@example.test", firstName: "Ana", currency: "EUR", suspectedDuplicate: true }, activeSessions: [{ sessionId: "s-1", startedAt: "2026-11-16T11:00:00Z" }], pinnedNotes: [{ noteId: "n-1", text: "VIP", author: "op-1", createdAt: "2026-11-01T00:00:00Z" }] }),
      [`/kyc${P}/kyc/p-1`]: () => Response.json({ error: "x" }, { status: 503 }),
      [`/responsible-gambling${P}/rg/p-1`]: () => Response.json({ limits: [{ limitType: "DEPOSIT_DAILY", value: 5000, currency: "EUR" }], cases: [] }),
      [`/wallet${P}/wallets/p-1/EUR/balance`]: () => Response.json({ realBalance: "100.00", bonusBalance: "5.00", currency: "EUR" }),
      [`/wallet${P}/wallets/p-1/EUR/restricted-funds`]: () => Response.json({ records: [{}], restrictedBalance: 500 }),
      [`/wallet${P}/wallets/p-1/EUR/lifetime-totals`]: () => Response.json({ totalDeposited: "250.00", totalWagered: "900.00", totalWon: "820.50" }),
    });
    const { parts } = await standing(deps(fetchFn), "p-1", "tok", all);
    expect(parts.identity).toEqual({ state: "unavailable", reason: "unavailable" });
    expect(parts.profile.state).toBe("ok");
    expect(parts.balances).toMatchObject({ state: "ok", data: [{ currency: "EUR", real: "10000", bonus: "500", restricted: 500 }] });
    expect(parts.lifetime).toMatchObject({ state: "ok", data: [{ deposited: "25000", won: "82050" }] });
    expect(parts.presence).toMatchObject({ state: "ok", data: { online: true } });
    expect(parts.flags).toMatchObject({ state: "ok", data: { suspectedDuplicate: true } });
    expect(parts.notes).toMatchObject({ state: "ok", data: [{ text: "VIP" }] });
  });

  test("an erased player is answered under the pseudonym only", async () => {
    const { fetchFn } = owners({
      [`/players${P}/players/p-1/profile`]: () => Response.json({ player: { playerId: "p-1", status: "erased", email: "", firstName: "Ana", currency: "EUR" }, pinnedNotes: [{ noteId: "n-1", text: "Ana's note" }] }),
    });
    const { parts } = await standing(deps(fetchFn), "p-1", "tok", all);
    const profile = (parts.profile as { data: Record<string, unknown> }).data;
    expect(profile.erased).toBe(true);
    expect(profile.pseudonym).toBe("p-1");
    expect(profile.firstName).toBeUndefined();
    expect(profile.email).toBeUndefined();
    expect(parts.notes).toEqual({ state: "ok", data: [] });
  });

  // The wallet writes major units by the currency's exponent (Wire.major, Amounts.render; spec 027 FR-058).
  test.each([
    ["12.50", "EUR", "1250"],
    ["480", "JPY", "480"],
    ["480.00", "JPY", undefined],
    ["150000", "CLP", "150000"],
    ["1.250", "KWD", "1250"],
    ["12.50", "KWD", undefined],
    ["-3.50", "EUR", "-350"],
    ["-3.5", "EUR", undefined],
    ["0.00", "EUR", "0"],
    ["1.255", "EUR", undefined],
    ["x", "EUR", undefined],
  ])("%s %s is %s minor units", (text, currency, minor) => expect(minorOf(text, currency)).toBe(minor));
});

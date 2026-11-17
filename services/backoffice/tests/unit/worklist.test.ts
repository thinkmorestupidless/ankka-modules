// T063: the worklist (contracts/sources.md, *Worklist*; FR-016, FR-017). Only the kinds the operator's
// roles admit are asked, in parallel, each within its bound, as the operator; a kind whose source fails
// or is late is unavailable with its reason, never empty, and the others are shown; each count is the
// source's own total.
import { describe, expect, test } from "vitest";
import { readKind, Sources, wanted, worklist } from "../../src/server/composite/worklist.ts";
import { match, table, type Row } from "../../src/server/routes.ts";
import { Kinds, kindsFor } from "../../src/shared/worklist.ts";
import { permissionsOf } from "../../src/server/roles.ts";
import { OperatorBags } from "./bags.ts";
import { Brand, harness } from "./support.ts";

type Answer = () => Response | Promise<Response>;

/** The owners, by the start of the path called: what each answers; every call, and when it started. */
function owners(answers: Record<string, Answer>) {
  const calls: { url: string; auth: string | null }[] = [];
  const fetchFn = (async (url: string, init: RequestInit = {}) => {
    calls.push({ url: url.replace("http://services.local", ""), auth: new Headers(init.headers).get("authorization") });
    const path = url.replace("http://services.local", "");
    const signal = init.signal;
    const answered = (async () => {
      for (const [prefix, answer] of Object.entries(answers)) if (path.startsWith(prefix)) return answer();
      return Response.json({ error: "not_found" }, { status: 404 });
    })();
    return Promise.race([answered, new Promise<Response>((_, reject) => signal?.addEventListener("abort", () => reject(signal.reason), { once: true }))]);
  }) as typeof fetch;
  return { calls, fetchFn };
}

const deps = (fetchFn: typeof fetch, rows: readonly Row[] = table, timeoutMs = 200) => ({
  brand: Brand,
  servicesUrl: "http://services.local",
  sharedProjects: { payments: "payments", programme: "programme", reasoner: "reasoner", games: "games" },
  fetch: fetchFn,
  timeoutMs,
  table: rows,
});

const admitted = (role: string, modules: string[] | null = ["core", "casino", "engagement"]) =>
  kindsFor({ brand: { modules }, permissions: permissionsOf([`brand_${Brand}`, role], OperatorBags) });
const kind = (id: string) => Kinds.find((k) => k.id === id)!;
const never: Answer = () => new Promise<Response>(() => {});
/** What psp-gateway answers a brand operator who holds no payments role. */
const notPayments = { "/psp-gateway.payments/": () => Response.json({ error: "not_payments_operator" }, { status: 403 }) };
const psp = (c: { url: string }) => c.url.startsWith("/psp-gateway.payments/");

const payouts = "/payouts/v1/casino/brands/goldvibe/withdrawal-requests";
const kycQueue = "/kyc/v1/casino/backoffice/goldvibe/kyc/queue";
const fraud = "/players/v1/casino/backoffice/goldvibe/fraud-cases";
const request = (requestId: string, createdAt: string, extra: Record<string, unknown> = {}) => ({
  requestId, playerId: "p-kenji", amount: 30000, currency: "JPY", createdAt, status: "PENDING_REVIEW", payoutStatus: "NONE", kycStatus: "verified", ...extra,
});

describe("the kinds asked", () => {
  test("a finance operator is admitted the withdrawal kinds and nothing else", () => {
    expect(admitted("brand_finance").map((k) => k.id)).toEqual(["withdrawals_pending", "payouts_needing_review", "undelivered_outcomes"]);
  });

  test("a compliance operator, identity, protection and fraud", () => {
    expect(admitted("brand_compliance").map((k) => k.id)).toEqual(["documents_awaiting", "players_flagged", "protection_cases", "fraud_cases", "undelivered_outcomes"]);
  });

  test("the kinds named are asked only where the roles admit them", () => {
    expect(wanted("withdrawals_pending,fraud_cases", admitted("brand_finance")).map((k) => k.id)).toEqual(["withdrawals_pending"]);
    expect(wanted(undefined, admitted("brand_finance")).map((k) => k.id)).toHaveLength(3);
  });

  test("a kind whose module the brand does not run is not admitted", () => {
    expect(admitted("brand_admin", ["core"]).map((k) => k.id)).not.toContain("unacknowledged_calls");
    expect(admitted("brand_admin", ["core"]).map((k) => k.id)).not.toContain("parked_sends");
  });

  test("only the admitted kinds' sources are called", async () => {
    const { calls, fetchFn } = owners({ [payouts]: () => Response.json({ requests: [], total: 0 }), ...notPayments });
    const kinds = await worklist(deps(fetchFn), admitted("brand_finance"), "tok");
    expect(calls.filter((c) => !psp(c)).map((c) => c.url.split("?")[0])).toEqual([payouts, payouts]);
    expect(kinds.map((k) => k.kind)).toEqual(["withdrawals_pending", "payouts_needing_review"]);
    expect(calls.every((c) => c.auth === "Bearer tok")).toBe(true);
  });
});

describe("a kind's source", () => {
  test("withdrawals pending review are asked oldest first, paged, and their count is payouts' total", async () => {
    const { calls, fetchFn } = owners({
      [payouts]: () => Response.json({ requests: [request("w-1", "2026-11-13T10:00:00Z"), request("w-2", "2026-11-14T10:00:00Z")], total: 37 }),
    });
    const k = await readKind(deps(fetchFn), kind("withdrawals_pending"), 1, "tok");
    const q = new URLSearchParams(calls[0]!.url.split("?")[1]);
    expect(Object.fromEntries(q)).toEqual({ status: "PENDING_REVIEW", sort: "oldest", page: "1", pageSize: "50" });
    expect(k).toEqual({
      kind: "withdrawals_pending",
      state: "ok",
      total: 37,
      page: 1,
      items: [
        { kind: "withdrawals_pending", id: "w-1", subject: { player: "p-kenji" }, since: "2026-11-13T10:00:00Z", amount: { minor: 30000, currency: "JPY" }, detail: { status: "PENDING_REVIEW", payoutStatus: "NONE", kycStatus: "verified" } },
        { kind: "withdrawals_pending", id: "w-2", subject: { player: "p-kenji" }, since: "2026-11-14T10:00:00Z", amount: { minor: 30000, currency: "JPY" }, detail: { status: "PENDING_REVIEW", payoutStatus: "NONE", kycStatus: "verified" } },
      ],
    });
  });

  test("payments needing review are asked across every request status", async () => {
    const { calls, fetchFn } = owners({ [payouts]: () => Response.json({ requests: [], total: 0 }) });
    await readKind(deps(fetchFn), kind("payouts_needing_review"), 2, "tok");
    expect(Object.fromEntries(new URLSearchParams(calls[0]!.url.split("?")[1]))).toEqual({ status: "ALL", payoutStatus: "NEEDS_REVIEW", sort: "oldest", page: "2", pageSize: "50" });
  });

  test("identity documents are kyc's awaiting filter, counted by kyc", async () => {
    const { calls, fetchFn } = owners({
      [kycQueue]: () => Response.json({ rows: [{ playerId: "p-ana", oldestAwaitingAt: "2026-11-10T09:00:00Z", status: "pending", awaiting: 2 }], counts: { awaiting: 12 } }),
    });
    const k = await readKind(deps(fetchFn), kind("documents_awaiting"), 1, "tok");
    expect(new URLSearchParams(calls[0]!.url.split("?")[1]).get("filter")).toBe("awaiting");
    expect(k).toMatchObject({ state: "ok", total: 12, items: [{ id: "p-ana", subject: { player: "p-ana" }, since: "2026-11-10T09:00:00Z" }] });
  });

  test("open fraud cases are players' OPEN cases, oldest first", async () => {
    const { fetchFn } = owners({
      [fraud]: () =>
        Response.json({
          cases: [
            { caseId: "c-2", playerIds: ["p-b"], signals: ["shared_device"], detectedAt: "2026-11-12T00:00:00Z" },
            { caseId: "c-1", playerIds: ["p-a", "p-c"], signals: ["shared_iban"], detectedAt: "2026-11-11T00:00:00Z" },
          ],
        }),
    });
    const k = await readKind(deps(fetchFn), kind("fraud_cases"), 1, "tok");
    expect(k).toMatchObject({ state: "ok", total: 2, items: [{ id: "c-1", subject: { player: "p-a" } }, { id: "c-2" }] });
  });

  test("a source that fails is unavailable with the reason, not empty", async () => {
    const { fetchFn } = owners({ [fraud]: () => Response.json({ error: "boom" }, { status: 500 }) });
    expect(await readKind(deps(fetchFn), kind("fraud_cases"), 1, "tok")).toEqual({ kind: "fraud_cases", state: "unavailable", reason: "status_500" });
  });

  test("a source that answers no queue it can read is unavailable", async () => {
    const { fetchFn } = owners({ [fraud]: () => new Response("<html>", { status: 200, headers: { "Content-Type": "text/html" } }) });
    expect(await readKind(deps(fetchFn), kind("fraud_cases"), 1, "tok")).toMatchObject({ state: "unavailable", reason: "unreadable" });
  });

  test("a kind whose source the route table does not hold is unavailable, no_source", async () => {
    const { calls, fetchFn } = owners({});
    expect(await readKind(deps(fetchFn), kind("undelivered_facts"), 1, "tok")).toEqual({ kind: "undelivered_facts", state: "unavailable", reason: "no_source" });
    expect(calls).toHaveLength(0);
  });

  test("undelivered outcomes are left out when the payments project cannot be asked, or refuses the operator", async () => {
    const { fetchFn } = owners({});
    const noPayments = table.filter((r) => r.service !== "psp-gateway");
    expect(await readKind(deps(fetchFn, noPayments), kind("undelivered_outcomes"), 1, "tok")).toBeUndefined();
    const rows: readonly Row[] = table;
    const refused = owners({ "/psp-gateway.payments/": () => Response.json({ error: "not_payments_operator" }, { status: 403 }) });
    expect(await readKind(deps(refused.fetchFn, rows), kind("undelivered_outcomes"), 1, "tok")).toBeUndefined();
    const answered = owners({
      "/psp-gateway.payments/v1/psp/outcomes/undelivered": () => Response.json({ outcomes: [{ reference: "o-2", since: "2026-11-12T00:00:00Z", provider: "pix" }], total: 4 }),
      "/psp-gateway.payments/v1/psp/outcomes/refused": () => Response.json({ outcomes: [{ reference: "o-1", since: "2026-11-11T00:00:00Z", provider: "pix" }], total: 1 }),
    });
    expect(await readKind(deps(answered.fetchFn, rows), kind("undelivered_outcomes"), 1, "tok")).toMatchObject({ state: "ok", total: 5, items: [{ id: "o-1" }, { id: "o-2" }] });
  });
});

describe("the worklist's sources", () => {
  test("every source a kind asks is a row of the route table, but attribution's, whose queue it has no route for yet", () => {
    const unmatched = Sources.filter((s) => s.kind !== "undelivered_facts").flatMap((s) => s.asks.filter((a) => match(table, "GET", a.service, a.path) === undefined).map((a) => `${s.kind} ${a.service} ${a.path}`));
    expect(unmatched).toEqual([]);
  });
});

describe("the worklist", () => {
  test("the sources are asked in parallel; a late one is unavailable within its bound and the others are shown", async () => {
    const { calls, fetchFn } = owners({
      [kycQueue]: never,
      [fraud]: () => Response.json({ cases: [{ caseId: "c-1", playerIds: ["p-a"], signals: [], detectedAt: "2026-11-11T00:00:00Z" }] }),
      "/responsible-gambling/": () => Response.json({ cases: [], total: 0 }),
      ...notPayments,
    });
    const started = Date.now();
    const kinds = await worklist(deps(fetchFn, table, 150), admitted("brand_compliance"), "tok");
    const took = Date.now() - started;
    expect(took).toBeLessThan(600);
    // two kyc kinds, rg, players: all asked before any bound ran out
    expect(calls.filter((c) => !psp(c))).toHaveLength(4);
    expect(kinds.map((k) => [k.kind, k.state, k.state === "unavailable" ? k.reason : k.total])).toEqual([
      ["documents_awaiting", "unavailable", "timeout"],
      ["players_flagged", "unavailable", "timeout"],
      ["protection_cases", "ok", 0],
      ["fraud_cases", "ok", 1],
    ]);
  });
});

describe("GET /api/worklist", () => {
  test("answers the kinds a finance operator's roles admit, asked as them, and nothing for the others", async () => {
    const h = harness({ table });
    h.state.sessions.set("s-fin", { accessToken: "fin-token", expiresAt: Date.now() + 60_000, roles: [`brand_${Brand}`, "brand_finance"] });
    h.answer((call) =>
      call.url.includes("/withdrawal-requests") ? Response.json({ requests: [request("w-1", "2026-11-13T10:00:00Z")], total: 1 })
      : call.url.includes("/psp/outcomes/") ? Response.json({ error: "not_payments_operator" }, { status: 403 })
      : Response.json({}, { status: 404 }));
    const r = await h.ask("GET", "/api/worklist", { session: "s-fin" });
    expect(r.status).toBe(200);
    const body = (await r.json()) as { kinds: { kind: string; state: string }[] };
    expect(body.kinds.map((k) => [k.kind, k.state])).toEqual([["withdrawals_pending", "ok"], ["payouts_needing_review", "ok"]]);
    expect(h.calls.every((c) => c.headers.get("authorization") === "Bearer fin-token")).toBe(true);
    expect(h.calls.every((c) => c.url.includes("/payouts/") || c.url.includes("/psp/outcomes/"))).toBe(true);
  });

  test("one kind's page; a kind the roles do not admit is no such kind", async () => {
    const h = harness({ table });
    h.state.sessions.set("s-fin", { accessToken: "fin-token", expiresAt: Date.now() + 60_000, roles: [`brand_${Brand}`, "brand_finance"] });
    h.answer(() => Response.json({ requests: [], total: 0 }));
    const page = await h.ask("GET", "/api/worklist/withdrawals_pending?page=3", { session: "s-fin" });
    expect(await page.json()).toMatchObject({ kind: "withdrawals_pending", state: "ok", page: 3, pageSize: 50 });
    expect(h.calls[0]!.url).toContain("page=3");
    expect((await h.ask("GET", "/api/worklist/fraud_cases", { session: "s-fin" })).status).toBe(404);
    expect((await h.ask("GET", "/api/worklist/withdrawals_pending?page=0", { session: "s-fin" })).status).toBe(400);
  });

  test("needs a session", async () => {
    expect((await harness().ask("GET", "/api/worklist")).status).toBe(401);
  });
});

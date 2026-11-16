// T072: the one search. Free text is never sent as an id; every match is listed by kind with its
// player; a value of two shapes is asked of both; a resolver the table does not hold, or whose
// permission the operator lacks, is not asked; a late or failed resolver is named, the others shown.
import { describe, expect, test } from "vitest";
import { search, shapes } from "../../src/server/composite/search.ts";
import { table } from "../../src/server/routes.ts";
import type { Row } from "../../src/server/routes.ts";

const Id = "1de8118c-ca16-49f7-99b5-ae7322e0ed1f";

/** The owners, by the path called: what each answers, and every call made. */
function owners(answers: Record<string, () => Response | Promise<Response>>) {
  const calls: { url: string; auth: string | null }[] = [];
  const fetchFn = (async (url: string, init: RequestInit = {}) => {
    calls.push({ url, auth: new Headers(init.headers).get("authorization") });
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

const deps = (fetchFn: typeof fetch, rows: readonly Row[] = table) => ({
  brand: "goldvibe",
  servicesUrl: "http://services.local",
  sharedProjects: { payments: "payments", programme: "programme", reasoner: "reasoner", games: "games" },
  fetch: fetchFn,
  timeoutMs: 100,
  table: rows,
});
const all = () => true;

describe("what was pasted, by shape", () => {
  test.each([
    [Id, ["uuid", "reference"]],
    ["ana@example.test", ["email"]],
    ["R-2026-11-0042", ["document", "reference"]],
    ["PSP_7781234", ["reference", "text"]],
    ["ana souza", ["text"]],
    ["ana", ["text"]],
    ["  ", []],
  ])("%s is %j", (q, expected) => expect(shapes(q)).toEqual(expected));
});

describe("the search", () => {
  test("free text is sent only as players' search, never as an id", async () => {
    const { calls, fetchFn } = owners({ "/players/": () => Response.json({ items: [] }) });
    await search(deps(fetchFn), "ana souza", "tok", all);
    expect(calls).toHaveLength(1);
    expect(calls[0]!.url).toBe("http://services.local/players/v1/casino/backoffice/goldvibe/players?search=ana%20souza");
  });

  test("a UUID is asked of every resolver that takes one and the table holds, as the operator", async () => {
    const { calls, fetchFn } = owners({
      [`/players/v1/casino/backoffice/goldvibe/players/${Id}/profile`]: () => Response.json({ playerId: Id, email: "ana@example.test", status: "active" }),
      [`/payouts/v1/casino/brands/goldvibe/withdrawal-requests/${Id}`]: () => Response.json({ error: "not_found" }, { status: 404 }),
    });
    const a = await search(deps(fetchFn), Id, "tok", all);
    const asked = calls.map((c) => c.url.replace("http://services.local", ""));
    expect(asked).toContain(`/players/v1/casino/backoffice/goldvibe/players/${Id}/profile`);
    expect(asked).toContain(`/payouts/v1/casino/brands/goldvibe/withdrawal-requests/${Id}`);
    expect(asked).toContain(`/rewards/v1/casino/brands/goldvibe/reward-grants/${Id}`);
    expect(asked.some((u) => u.startsWith("/merchant.payments/v1/payments/cashier?reference="))).toBe(true);
    expect(calls.every((c) => c.auth === "Bearer tok")).toBe(true);
    expect(a.matches).toEqual([{ kind: "player", id: Id, player: Id, summary: { email: "ana@example.test", status: "active" } }]);
    expect(asked).toContain(`/game-provider/v1/casino/backoffice/goldvibe/game-provider/rounds/by-round/${Id}`);
    expect(asked).toContain(`/game-provider/v1/casino/backoffice/goldvibe/game-provider/rounds/by-transaction/${Id}`);
    expect(a.asked).toEqual(["payment", "player", "reward", "round", "withdrawal"]);
  });

  test("lists every match with its kind and its player", async () => {
    const { fetchFn } = owners({
      "/payouts/": () => Response.json({ requestId: Id, playerId: "p-1", status: "pending_review", amount: 30000, currency: "JPY" }),
      "/rewards/": () => Response.json({ rewardId: Id, playerId: "p-2", status: "active" }),
    });
    const a = await search(deps(fetchFn), Id, "tok", all);
    expect(a.matches.map((m) => [m.kind, m.player])).toEqual([
      ["withdrawal", "p-1"],
      ["reward", "p-2"],
    ]);
  });

  test("a payment found by its reference is listed with the player it belongs to (merchant's answer)", async () => {
    const { fetchFn } = owners({ "/merchant.payments/v1/payments/cashier": () => Response.json({ payments: [{ paymentId: "pay-1", customerRef: "p-9", status: "captured", amountCents: 5000, currency: "BRL" }], total: 1 }) });
    const a = await search(deps(fetchFn), "PSP_7781234", "tok", all);
    expect(a.matches).toEqual([{ kind: "payment", id: "pay-1", player: "p-9", summary: { status: "captured", amountCents: 5000, currency: "BRL" } }]);
  });

  test("an email is asked of players' search only", async () => {
    const { calls, fetchFn } = owners({ "/players/": () => Response.json({ items: [{ playerId: "p-1", email: "ana@example.test" }] }) });
    const a = await search(deps(fetchFn), "ana@example.test", "tok", all);
    expect(calls.map((c) => c.url)).toEqual(["http://services.local/players/v1/casino/backoffice/goldvibe/players?search=ana%40example.test"]);
    expect(a.matches).toEqual([{ kind: "player", id: "p-1", player: "p-1", summary: { email: "ana@example.test" } }]);
  });

  test("a value of two shapes is asked of both, players' search once", async () => {
    const { calls, fetchFn } = owners({});
    await search(deps(fetchFn), "PSP_7781234", "tok", all);
    const asked = calls.map((c) => c.url.replace("http://services.local", "").split("?")[0]);
    expect(asked.sort()).toEqual(["/merchant.payments/v1/payments/cashier", "/players/v1/casino/backoffice/goldvibe/players"]);
  });

  test("a resolver whose permission the operator lacks is not asked", async () => {
    const { calls, fetchFn } = owners({});
    await search(deps(fetchFn), Id, "tok", (p) => p === "identity.review");
    expect(calls).toEqual([]);
    await search(deps(fetchFn), Id, "tok", (p) => p === "reward.read");
    expect(calls.map((c) => c.url)).toEqual([`http://services.local/rewards/v1/casino/brands/goldvibe/reward-grants/${Id}`]);
  });

  test("a row of another shape that happens to match is not taken for a resolver", async () => {
    const { calls, fetchFn } = owners({});
    // without GP2's rows, `/rounds/{provider}/{roundId}` would fit `/rounds/by-round/…`: it is not taken for the resolver
    await search(deps(fetchFn, table.filter((r) => !r.path.startsWith("/rounds/by-"))), Id, "tok", all);
    expect(calls.some((c) => c.url.includes("/rounds/by-round/") || c.url.includes("/rounds/by-transaction/"))).toBe(false);
  });

  test("a resolver the table does not hold is not asked", async () => {
    const { calls, fetchFn } = owners({});
    await search(deps(fetchFn, table.filter((r) => r.service !== "payouts")), Id, "tok", all);
    expect(calls.some((c) => c.url.includes("/payouts/"))).toBe(false);
  });

  test("a late or failed resolver is named and the others are shown", async () => {
    const { fetchFn } = owners({
      "/players/": () => Response.json({ playerId: Id }),
      "/payouts/": () => new Promise<Response>(() => undefined),
      "/rewards/": () => Response.json({ error: "boom" }, { status: 500 }),
      "/merchant.payments/": () => new Response("", { status: 503, headers: { "X-Ankka-Answered-By": "proxy" } }),
    });
    const a = await search(deps(fetchFn), Id, "tok", all);
    expect(a.matches.map((m) => m.kind)).toEqual(["player"]);
    expect(a.unavailable).toEqual(["merchant", "payouts", "rewards"]);
  });
});

describe("GET /api/search", () => {
  test("answers nothing for nothing, refuses an overlong value, and needs a session", async () => {
    const { harness } = await import("./support.ts");
    const h = harness();
    expect(await (await h.ask("GET", "/api/search?q=%20", { session: "s-1" })).json()).toEqual({ matches: [], asked: [], unavailable: [] });
    expect((await h.ask("GET", `/api/search?q=${"a".repeat(300)}`, { session: "s-1" })).status).toBe(400);
    expect((await h.ask("GET", "/api/search?q=ana")).status).toBe(401);
  });

  test("asks as the operator and names its route in the log, never the value", async () => {
    const { harness } = await import("./support.ts");
    const h = harness({ table });
    h.answer(() => Response.json({ items: [{ playerId: "p-1", email: "ana@example.test" }] }));
    const a = (await (await h.ask("GET", "/api/search?q=ana.secret.surname", { session: "s-1" })).json()) as { matches: unknown[] };
    expect(a.matches).toHaveLength(1);
    expect(h.logs.join("\n")).toContain('"route":"/api/search"');
    expect(h.logs.join("\n")).not.toContain("ana.secret.surname");
  });
});

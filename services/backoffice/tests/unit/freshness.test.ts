// T052: /api/freshness asks each named service's status as the operator and says when what a list
// shows is provisional: a view rebuilding, or a status that cannot be read; never by asOf's age.
import { describe, expect, test } from "vitest";
import { freshness, named } from "../../src/server/composite/freshness.ts";
import { table } from "../../src/server/routes.ts";
import { combine } from "../../src/web/components/freshness.tsx";
import { harness } from "./support.ts";

function deps(answer: (url: string) => Response | Promise<Response>) {
  const calls: { url: string; auth: string | null }[] = [];
  const fetchFn = (async (url: string, init: RequestInit = {}) => {
    calls.push({ url, auth: new Headers(init.headers).get("authorization") });
    return answer(url);
  }) as typeof fetch;
  return { calls, deps: { brand: "goldvibe", servicesUrl: "http://s", sharedProjects: {}, fetch: fetchFn, timeoutMs: 100, table } };
}

describe("the services named", () => {
  test("are those the table holds a status row for, each once", () => {
    expect(named("players,wallet,players,audit", table)).toEqual(["players", "wallet"]);
  });
  test("are refused when empty, malformed or too many", () => {
    expect(named("", table)).toBeUndefined();
    expect(named("players,../x", table)).toBeUndefined();
    expect(named(Array.from({ length: 13 }, (_, i) => `s${i}`).join(","), table)).toBeUndefined();
  });
});

describe("a service's freshness", () => {
  test("is its views, the oldest asOf, and provisional while a view rebuilds, asked as the operator", async () => {
    const { calls, deps: d } = deps(() =>
      Response.json({ views: [{ name: "players", asOf: "2026-11-15T10:05:00Z", rebuilding: true }, { name: "sessions", asOf: "2026-11-15T10:00:00Z", rebuilding: false }] }),
    );
    const [p] = await freshness(d, ["players"], "tok");
    expect(p).toEqual({ service: "players", state: "ok", views: expect.any(Array), asOf: "2026-11-15T10:00:00Z", provisional: true });
    expect(calls).toEqual([{ url: "http://s/players/v1/casino/brands/goldvibe/players/status", auth: "Bearer tok" }]);
  });

  test("is not provisional for an old asOf alone: a quiet brand's views are old and right", async () => {
    const { deps: d } = deps(() => Response.json({ views: [{ name: "players", asOf: "2020-01-01T00:00:00Z", rebuilding: false }] }));
    expect((await freshness(d, ["players"], "tok"))[0]).toMatchObject({ state: "ok", provisional: false });
  });

  test("is reporting's in its own shape: no asOf, the latest day its views are complete from, provisional while rebuilding (T101)", async () => {
    const { calls, deps: d } = deps(() =>
      Response.json({ generation: 2, views: [{ name: "days", completeFrom: "2026-10-03", rebuilding: true }, { name: "players", completeFrom: "2026-10-12", rebuilding: true }, { name: "grants", completeFrom: null, rebuilding: true }], roles: "2026-11-15T10:00:00Z" }),
    );
    const [r] = await freshness(d, ["reporting"], "tok");
    expect(r).toMatchObject({ service: "reporting", state: "ok", asOf: null, completeFrom: "2026-10-12", provisional: true });
    expect(r!.state === "ok" && r!.views[2]).toEqual({ name: "grants", asOf: null, rebuilding: true, completeFrom: null });
    expect(calls[0]!.url).toBe("http://s/reporting/v1/casino/brands/goldvibe/reporting/status");
  });

  test("is unavailable, and so provisional, when the status cannot be read", async () => {
    const { deps: d } = deps((url) =>
      url.includes("/wallet/") ? new Response("", { status: 503, headers: { "X-Ankka-Answered-By": "proxy" } }) : url.includes("/payouts/") ? Response.json({ error: "forbidden" }, { status: 403 }) : Response.json({ nope: 1 }),
    );
    const got = await freshness(d, ["wallet", "payouts", "kyc"], "tok");
    expect(got.map((g) => [g.service, g.state, g.state === "unavailable" ? g.reason : ""])).toEqual([
      ["wallet", "unavailable", "source_unavailable"],
      ["payouts", "unavailable", "forbidden"],
      ["kyc", "unavailable", "unreadable"],
    ]);
    expect(got.every((g) => g.provisional)).toBe(true);
  });
});

describe("what the page shows", () => {
  test("is the oldest time, provisional when any is", () => {
    expect(
      combine({ services: [{ service: "a", state: "ok", asOf: "2026-11-15T10:05:00Z", provisional: false }, { service: "b", state: "unavailable", reason: "x", provisional: true }] }),
    ).toEqual({ at: "2026-11-15T10:05:00Z", provisional: true });
    expect(combine({ services: [{ service: "a", state: "ok", asOf: null, provisional: false }] })).toBeUndefined();
  });
});

describe("GET /api/freshness", () => {
  test("answers the services and refuses a bad list", async () => {
    const h = harness({ table });
    h.answer(() => Response.json({ views: [{ name: "players", asOf: null, rebuilding: false }] }));
    const r = await h.ask("GET", "/api/freshness?services=players", { session: "s-1" });
    expect(await r.json()).toEqual({ services: [{ service: "players", state: "ok", views: [{ name: "players", asOf: null, rebuilding: false }], asOf: null, provisional: false }] });
    expect((await h.ask("GET", "/api/freshness", { session: "s-1" })).status).toBe(400);
  });
});

import { describe, expect, test } from "vitest";
import { Brand, harness, SecretToken, whole, write } from "./support.ts";

describe("calling the owner", () => {
  test("a brand's own service is called at the calling address, with the brand put in and the query kept", async () => {
    const h = harness();
    await h.ask("GET", "/api/s/players/players/p-1/profile?fields=all", { session: "s-1" });
    expect(h.calls[0]!.url).toBe(`http://services.local/players/v1/casino/backoffice/${Brand}/players/p-1/profile?fields=all`);
  });

  test("a shared project's service is called at that project's address", async () => {
    const h = harness();
    await h.ask("GET", "/api/s/merchant/deposits?page=2", { session: "s-1" });
    expect(h.calls[0]!.url).toBe(`http://services.local/merchant.payments/v1/merchants/${Brand}/deposits?page=2`);
  });

  test("with the operator's token, and never the browser's cookie", async () => {
    const h = harness();
    await h.ask("GET", "/api/s/players/players/p-1/profile", { session: "s-1" });
    expect(h.calls[0]!.headers.get("authorization")).toBe(`Bearer ${SecretToken}`);
    expect(h.calls[0]!.headers.get("cookie")).toBeNull();
  });

  test("a write's body is passed as the page sent it", async () => {
    const h = harness();
    await h.ask("PATCH", "/api/s/players/players/p-1/suspend", { session: "s-1", headers: write, body: '{"reason":"kit"}' });
    expect(h.calls[0]!.method).toBe("PATCH");
    expect(h.calls[0]!.body).toBe('{"reason":"kit"}');
    expect(h.calls[0]!.headers.get("content-type")).toBe("application/json");
  });
});

describe("the owner's answer", () => {
  test("is returned as given: status, body and its type", async () => {
    const h = harness();
    const refusal = '{"error":"forbidden","message":"the standing.change permission is needed"}';
    h.answer(() => new Response(refusal, { status: 403, headers: { "Content-Type": "application/json" } }));
    const r = await h.ask("PATCH", "/api/s/players/players/p-1/suspend", { session: "s-1", headers: write, body: "{}" });
    expect(r.status).toBe(403);
    expect(r.headers.get("content-type")).toBe("application/json");
    expect(await r.text()).toBe(refusal);
  });

  test("keeps its Content-Disposition", async () => {
    const h = harness();
    h.answer(() => new Response("a,b\n1,2\n", { headers: { "Content-Type": "text/csv", "Content-Disposition": 'attachment; filename="day-end.csv"' } }));
    const r = await h.ask("GET", "/api/s/reporting/exports/e-1", { session: "s-1" });
    expect(r.headers.get("content-disposition")).toBe('attachment; filename="day-end.csv"');
    expect(await r.text()).toBe("a,b\n1,2\n");
  });

  test("a proxy's own answer becomes 503 source_unavailable naming the service", async () => {
    const h = harness();
    h.answer(() => new Response('{"error":"no_ready_instance"}', { status: 502, headers: { "X-Ankka-Answered-By": "proxy" } }));
    const r = await h.ask("GET", "/api/s/players/players/p-1/profile", { session: "s-1" });
    expect(r.status).toBe(503);
    expect(await r.json()).toEqual({ error: "source_unavailable", source: "players" });
  });

  test("an owner that cannot be reached is the same", async () => {
    const h = harness();
    h.answer(() => {
      throw new TypeError("fetch failed");
    });
    const r = await h.ask("GET", "/api/s/players/players/p-1/profile", { session: "s-1" });
    expect(r.status).toBe(503);
    expect(await r.json()).toEqual({ error: "source_unavailable", source: "players" });
  });

  test("an owner slower than the bound is the same", async () => {
    const h = harness({ timeoutMs: 20 });
    h.answer(({ url }) => new Promise((resolve) => setTimeout(() => resolve(Response.json({ url })), 200)));
    const r = await h.ask("GET", "/api/s/players/players/p-1/profile", { session: "s-1" });
    expect(r.status).toBe(503);
  });
});

describe("the token", () => {
  test("is in no answer and no log line, whatever the owner answered", async () => {
    const h = harness();
    const answers = [
      () => Response.json({ ok: true }),
      () => new Response("{}", { status: 500 }),
      () => new Response("{}", { status: 502, headers: { "X-Ankka-Answered-By": "proxy" } }),
      () => {
        throw new TypeError(`failed with ${SecretToken}`);
      },
    ];
    for (const a of answers) {
      h.answer(a);
      const r = await h.ask("GET", "/api/s/players/players/p-1/profile", { session: "s-1" });
      expect(await whole(r)).not.toContain(SecretToken);
    }
    expect(h.logs.length).toBeGreaterThan(0);
    for (const line of h.logs) expect(line).not.toContain(SecretToken);
  });

  test("a log line names the route's template, not the player", async () => {
    const h = harness();
    await h.ask("GET", "/api/s/players/players/p-42/profile", { session: "s-1" });
    expect(h.logs.join("\n")).toContain("/players/{p}/profile");
    expect(h.logs.join("\n")).not.toContain("p-42");
  });
});

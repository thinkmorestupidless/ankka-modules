import { describe, expect, test } from "vitest";
import { Brand, harness, SecretToken, whole, write } from "./support.ts";
import { table } from "../../src/server/routes.ts";
import { BodyMax } from "../../src/server/main.ts";

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

  test("the affiliate programme is called where the brand's values put it, for this brand only (FR-037)", async () => {
    const h = harness({ table, sharedProjects: { programme: "affiliates-eu" } });
    await h.ask("GET", "/api/s/affiliate-programme/accounts?page=2", { session: "s-1" });
    await h.ask("GET", "/api/s/affiliate-programme/accounts/a-1/portal/dashboard?period=2026-10", { session: "s-1" });
    expect(h.calls.map((c) => c.url)).toEqual([
      `http://services.local/affiliate-programme.affiliates-eu/v1/affiliates/${Brand}/accounts?page=2`,
      `http://services.local/affiliate-programme.affiliates-eu/v1/affiliates/${Brand}/accounts/a-1/portal/dashboard?period=2026-10`,
    ]);
    for (const row of table.filter((r) => r.service === "affiliate-programme")) {
      expect(row.where, row.path).toBe("programme");
      expect(row.upstream.startsWith("/v1/affiliates/{brand}/"), row.path).toBe(true);
    }
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

describe("a request's body", () => {
  const path = "/api/s/players/players/p-1/suspend";

  test("of more than 1 MiB is refused 413 and asks no one, by its length or as it streams", async () => {
    const h = harness();
    const big = "x".repeat(BodyMax + 1);
    const streamed = await h.ask("PATCH", path, { session: "s-1", headers: write, body: big });
    expect(streamed.status).toBe(413);
    expect(await streamed.json()).toMatchObject({ error: "body_too_large" });
    const stated = await h.ask("PATCH", path, { session: "s-1", headers: { ...write, "Content-Length": String(BodyMax + 1) }, body: big });
    expect(stated.status).toBe(413);
    expect(h.calls).toHaveLength(0);
  });

  test("of 1 MiB is passed on whole", async () => {
    const h = harness();
    const body = "x".repeat(BodyMax);
    const r = await h.ask("PATCH", path, { session: "s-1", headers: write, body });
    expect(r.status).toBe(200);
    expect(h.calls[0]!.body).toBe(body);
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

  test("keeps the owner's length of a body sent as it is, and drops it for an encoded one, which fetch decodes", async () => {
    const h = harness();
    h.answer(() => new Response("{}", { headers: { "Content-Type": "application/json", "Content-Length": "2" } }));
    const plain = await h.ask("GET", "/api/s/players/players/p-1/profile", { session: "s-1" });
    expect(plain.headers.get("content-length")).toBe("2");
    h.answer(() => new Response('{"a":1}', { headers: { "Content-Type": "application/json", "Content-Encoding": "gzip", "Content-Length": "3" } }));
    const encoded = await h.ask("GET", "/api/s/players/players/p-1/profile", { session: "s-1" });
    expect(encoded.headers.get("content-length")).toBeNull();
    expect(encoded.headers.get("content-encoding")).toBeNull();
    expect(await encoded.text()).toBe('{"a":1}');
  });

  test("is nosniff, whatever the owner said", async () => {
    const h = harness();
    h.answer(() => new Response("<b>x</b>", { headers: { "Content-Type": "text/html" } }));
    const r = await h.ask("GET", "/api/s/players/players/p-1/profile", { session: "s-1" });
    expect(r.headers.get("x-content-type-options")).toBe("nosniff");
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

describe("the Idempotency-Key", () => {
  const programme = () => harness({ table, sharedProjects: { programme: "affiliates-eu" } });
  const adjust = (h: ReturnType<typeof harness>, key?: string) =>
    h.ask("POST", "/api/s/affiliate-programme/accounts/a-1/adjustments", {
      session: "s-1",
      headers: { ...write, ...(key === undefined ? {} : { "Idempotency-Key": key }) },
      body: '{"type":"CREDIT","amount":1000,"currency":"EUR","reason":"kit"}',
    });

  test("is passed on to the owner as the page sent it", async () => {
    const h = programme();
    const r = await adjust(h, "programme-adjust/0b6f2a4e-3c1d-4e8f-9a7b-5c6d7e8f9a0b");
    expect(r.status).toBe(200);
    expect(h.calls[0]!.url).toBe(`http://services.local/affiliate-programme.affiliates-eu/v1/affiliates/${Brand}/accounts/a-1/adjustments`);
    expect(h.calls[0]!.headers.get("idempotency-key")).toBe("programme-adjust/0b6f2a4e-3c1d-4e8f-9a7b-5c6d7e8f9a0b");
  });

  test("is not invented when the page sent none", async () => {
    const h = programme();
    await adjust(h);
    expect(h.calls[0]!.headers.get("idempotency-key")).toBeNull();
  });

  test("of 200 printable characters is passed on", async () => {
    const h = programme();
    const key = `${"k".repeat(199)}~`;
    await adjust(h, key);
    expect(h.calls[0]!.headers.get("idempotency-key")).toBe(key);
  });

  test.each([
    ["longer than 200 characters", "k".repeat(201)],
    ["empty", ""],
    ["not ASCII", "clé"],
    ["holding a control character", "a\tb"],
  ])("%s is refused with 400, and the owner is not called", async (_, key) => {
    const h = programme();
    const r = await adjust(h, key);
    expect(r.status).toBe(400);
    expect(await r.json()).toMatchObject({ error: "invalid_idempotency_key" });
    expect(h.calls).toEqual([]);
  });

  test("is checked on any write it comes with", async () => {
    const h = harness();
    const r = await h.ask("PATCH", "/api/s/players/players/p-1/suspend", { session: "s-1", headers: { ...write, "Idempotency-Key": "x".repeat(201) }, body: "{}" });
    expect(r.status).toBe(400);
    expect(h.calls).toEqual([]);
  });
});

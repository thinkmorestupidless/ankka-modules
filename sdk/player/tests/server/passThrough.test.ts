import { afterEach, describe, expect, it } from "vitest";
import { channel } from "../../src/testing/doubles.ts";
import { wholeSite } from "./helpers.ts";

let close: (() => Promise<void>) | undefined;
afterEach(async () => {
  await close?.();
  close = undefined;
});
const site = async (options: Parameters<typeof wholeSite>[0] = {}) => {
  const s = await wholeSite(options);
  close = s.close;
  return s;
};

describe("the pass-through (FR-001, FR-004, FR-008)", () => {
  it("calls the service of the row with the server's brand and the sign-in it holds, and returns its answer unchanged", async () => {
    const s = await site();
    await s.signIn();
    const r = await s.request("GET", "/api/c/core/balance?currency=EUR", { headers: { traceparent: "00-abc-def-01", authorization: "Bearer forged" } });
    expect(r.status).toBe(200);
    expect(((await r.json()) as { realBalance: string }).realBalance).toBe("50.00");
    const asked = s.doubles.askedOf("balance")[0]!;
    expect(asked.path).toEqual({ brand: "guyanabet", currency: "EUR" });
    expect(asked.headers.authorization).toMatch(/^Bearer [^f]/);
    expect(asked.headers.traceparent).toBe("00-abc-def-01");
    expect(asked.headers.cookie).toBeUndefined();
  });

  it("a route no module names, a wrong method, or the server's own row is not named and asks nothing", async () => {
    const s = await site();
    await s.signIn();
    const before = s.doubles.asked.length;
    for (const [method, path] of [
      ["GET", "/api/c/core/withdraw"],
      ["POST", "/api/c/core/balance?currency=EUR"],
      ["POST", "/api/c/core/signIn"],
      ["GET", "/api/c/payments/balance"],
      ["GET", "/api/other"],
      ["GET", "/api/c/constructor/x"],
      ["GET", "/api/c/toString/x"],
    ] as const) {
      const r = await s.request(method, path);
      expect(r.status, path).toBe(404);
      expect(await r.json(), path).toEqual({ error: "not_named" });
    }
    expect(s.doubles.asked.length).toBe(before);
  });

  it("a module the brand does not run is refused and asks nothing", async () => {
    const s = await site({ config: { modules: ["core", "casino"] } });
    await s.signIn();
    const r = await s.request("POST", "/api/c/support/turn", { body: { message: "salut", session_id: "s-1" } });
    expect(r.status).toBe(404);
    expect(await r.json()).toEqual({ error: "module_not_run" });
    expect(s.doubles.askedOf("turn")).toEqual([]);
  });

  it("another brand is refused", async () => {
    const s = await site();
    const r = await s.request("GET", "/api/c/casino/games?brand=goldvibe");
    expect(r.status).toBe(403);
    expect(await r.json()).toMatchObject({ error: "wrong_brand" });
  });

  it("the catalogue is asked with the brand in its query, and a visitor reaches it", async () => {
    const s = await site();
    const r = await s.request("GET", "/api/c/casino/games?search=pool&secret=1");
    expect(r.status).toBe(200);
    expect(s.doubles.askedOf("games")[0]!.query).toEqual({ search: "pool", brand: "guyanabet" });
    expect(s.doubles.askedOf("games")[0]!.headers.authorization).toBeUndefined();
  });

  it("a player route with nobody signed in is signed out, and so is a service's 401", async () => {
    const s = await site();
    expect(await (await s.request("GET", "/api/c/core/balance?currency=EUR")).json()).toEqual({ error: "signed_out" });
    await s.signIn();
    s.doubles.script("balance", { status: 401, body: { error: "unauthenticated" } });
    const r = await s.request("GET", "/api/c/core/balance?currency=EUR");
    expect(r.status).toBe(401);
    expect(await r.json()).toEqual({ error: "signed_out" });
  });

  it("a request about another player is refused before any service is asked", async () => {
    const s = await site();
    await s.signIn();
    const r = await s.request("GET", "/api/c/core/profile?player=p-other");
    expect(r.status).toBe(403);
    expect(s.doubles.askedOf("profile")).toEqual([]);
  });

  it("a cashier route takes the ticket from its header alone, adds no sign-in, and returns the cashier's 401 as it is", async () => {
    const s = await site();
    await s.signIn();
    s.doubles.brand.cashierSessions.set("ticket-9", { requestId: "r", amount: 5000, currency: "EUR" });
    const r = await s.request("POST", "/api/c/core/submitCashier", { headers: { cashierTicket: "ticket-9" }, body: { selectedTerminalEntityId: "t-card" } });
    expect(r.status).toBe(200);
    const asked = s.doubles.askedOf("submitCashier")[0]!;
    expect(asked.path.ticket).toBe("ticket-9");
    expect(asked.headers.cashierticket).toBe("ticket-9");
    expect(asked.headers.authorization).toBeUndefined();
    s.doubles.script("payment", { status: 401, body: { error: "UNAUTHENTICATED" } });
    const p = await s.request("GET", "/api/c/core/payment?paymentId=pay-1", { headers: { cashierTicket: "ticket-9" } });
    expect(p.status).toBe(401);
    expect(await p.json()).toEqual({ error: "UNAUTHENTICATED" });
  });

  it("a ticket in a query is refused, and a visitor cannot reach a cashier route", async () => {
    const s = await site();
    const visitor = await s.request("GET", "/api/c/core/payment?paymentId=pay-1", { headers: { cashierTicket: "ticket-9" } });
    expect(visitor.status).toBe(401);
    expect(await visitor.json()).toEqual({ error: "signed_out" });
    await s.signIn();
    const q = await s.request("POST", "/api/c/core/submitCashier?ticket=ticket-9", { body: {} });
    expect(q.status).toBe(400);
    expect(s.doubles.askedOf("submitCashier")).toEqual([]);
  });

  it("a service that does not answer within the bound is unavailable, naming it", async () => {
    const s = await site({ boundMs: 150 });
    await s.signIn();
    s.doubles.script("balance", { status: 200, silent: true });
    const r = await s.request("GET", "/api/c/core/balance?currency=EUR");
    expect(r.status).toBe(503);
    expect(await r.json()).toEqual({ error: "service_unavailable", service: "wallet" });
  });

  it("a stream is piped event by event, unbuffered and never kept", async () => {
    const s = await site();
    await s.signIn();
    const c = channel();
    s.doubles.script("push", { status: 200, stream: c });
    const r = await s.request("GET", "/api/c/engagement/push");
    expect(r.status).toBe(200);
    expect(r.headers.get("content-type")).toBe("text/event-stream");
    expect(r.headers.get("cache-control")).toBe("no-store");
    const reader = r.body!.getReader();
    c.push("ready", { subjectId: "p-ana" });
    const first = new TextDecoder().decode((await reader.read()).value);
    expect(first).toContain("event: ready");
    c.push("signal", { kind: "inbox.changed" });
    const second = new TextDecoder().decode((await reader.read()).value);
    expect(second).toContain("event: signal");
    c.end();
    await reader.cancel();
  });

  it("logs the route id, the method, the status and the time, and nothing of the query", async () => {
    const s = await site();
    await s.signIn();
    await s.request("GET", "/api/c/core/balance?currency=EUR");
    expect(s.logs.at(-1)).toMatchObject({ method: "GET", route: "core/balance", status: 200 });
    expect(JSON.stringify(s.logs)).not.toContain("EUR");
  });
});

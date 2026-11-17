// T050: `/api/events` tells the page when what the operator may do changed, and when the session ended.
import { describe, expect, test } from "vitest";
import { Kept, type Bags } from "../../src/server/roles.ts";
import { OperatorBags } from "./bags.ts";
import { Brand, harness, rows, type Call } from "./support.ts";
import type { Row } from "../../src/server/routes.ts";
import { relayOnline } from "../../src/server/events.ts";

/** A harness whose roles are read at every comparison, and whose token is never held (it expires within the margin). */
function live() {
  let bags: Bags | Error = OperatorBags;
  const h = harness({
    eventsEveryMs: 20,
    reads: { bags: new Kept<Bags>(async () => { if (bags instanceof Error) throw bags; return bags; }, 0, () => Date.now()), modules: new Kept<string[]>(async () => ["core"], 0, () => Date.now()), zone: new Kept<string>(async () => "America/Sao_Paulo", 0, () => Date.now()) },
  });
  h.state.sessions.set("s-ev", { accessToken: "a.e30.s", expiresAt: Date.now() + 10_000, roles: [`brand_${Brand}`, "brand_support"] });
  return { ...h, bags: (b: Bags | Error) => void (bags = b) };
}

/** Reads the stream until `event` arrives, or gives up after `ms`. */
async function until(r: Response, event: string, ms = 2_000): Promise<string> {
  const reader = r.body!.getReader();
  const text = new TextDecoder();
  let seen = "";
  const deadline = Date.now() + ms;
  try {
    while (Date.now() < deadline) {
      const next = await Promise.race([reader.read(), new Promise<undefined>((res) => setTimeout(() => res(undefined), deadline - Date.now()))]);
      if (next === undefined || next.done) break;
      seen += text.decode(next.value);
      if (seen.includes(`event: ${event}`)) return seen;
    }
    return seen;
  } finally {
    await reader.cancel().catch(() => undefined);
  }
}

describe("/api/events", () => {
  test("is a stream of server-sent events, unbuffered, for a signed-in operator", async () => {
    const h = live();
    const r = await h.ask("GET", "/api/events", { session: "s-ev" });
    expect(r.status).toBe(200);
    expect(r.headers.get("content-type")).toMatch(/^text\/event-stream/);
    expect(r.headers.get("x-accel-buffering")).toBe("no");
    expect(await until(r, "nothing", 100)).toMatch(/^: open/);
  });

  test("needs a session", async () => {
    const r = await live().ask("GET", "/api/events");
    expect(r.status).toBe(401);
  });

  test("says permissions_changed when what the operator's role carries changes", async () => {
    const h = live();
    const r = await h.ask("GET", "/api/events", { session: "s-ev" });
    setTimeout(() => h.bags({ ...OperatorBags, brand_support: [...OperatorBags.brand_support!, "adjustment.make"] }), 50);
    expect(await until(r, "permissions_changed")).toContain("event: permissions_changed");
  });

  test("says permissions_changed when the operator is given a role", async () => {
    const h = live();
    const r = await h.ask("GET", "/api/events", { session: "s-ev" });
    setTimeout(() => h.state.sessions.set("s-ev", { accessToken: "a.e30.s", expiresAt: Date.now() + 10_000, roles: [`brand_${Brand}`, "brand_support", "brand_finance"] }), 50);
    expect(await until(r, "permissions_changed")).toContain("event: permissions_changed");
  });

  test("says permissions_changed when the operator loses the brand, so the page learns it from /api/me", async () => {
    const h = live();
    const r = await h.ask("GET", "/api/events", { session: "s-ev" });
    setTimeout(() => h.state.sessions.set("s-ev", { accessToken: "a.e30.s", expiresAt: Date.now() + 10_000, roles: ["brand_support"] }), 50);
    expect(await until(r, "permissions_changed")).toContain("event: permissions_changed");
  });

  test("says nothing while nothing changes, and nothing for an unreachable players", async () => {
    const h = live();
    const r = await h.ask("GET", "/api/events", { session: "s-ev" });
    setTimeout(() => h.bags(new Error("players answered 503")), 30);
    expect(await until(r, "permissions_changed", 200)).not.toContain("event:");
  });

  test("says signed_out when the session ends", async () => {
    const h = live();
    const r = await h.ask("GET", "/api/events", { session: "s-ev" });
    setTimeout(() => h.state.sessions.delete("s-ev"), 50);
    expect(await until(r, "signed_out")).toContain("event: signed_out");
  });

  test("ends when the instance is stopping, without waiting for its next comparison", async () => {
    const stopping = new AbortController();
    const h = harness({ eventsEveryMs: 60_000, shutdown: stopping.signal });
    h.state.sessions.set("s-ev", { accessToken: "a.e30.s", expiresAt: Date.now() + 120_000, roles: [`brand_${Brand}`, "brand_support"] });
    const r = await h.ask("GET", "/api/events", { session: "s-ev" });
    const reader = r.body!.getReader();
    expect(new TextDecoder().decode((await reader.read()).value)).toMatch(/^: open/);
    const started = Date.now();
    stopping.abort();
    let done = false;
    while (!done) done = (await reader.read()).done;
    expect(Date.now() - started).toBeLessThan(1_000);
  });

  test("holds at most eight streams open for one sign-in; a ninth is refused until one ends", async () => {
    const h = live();
    const streams = await Promise.all(Array.from({ length: 8 }, () => h.ask("GET", "/api/events", { session: "s-ev" })));
    expect(streams.map((r) => r.status)).toEqual(Array(8).fill(200));
    const ninth = await h.ask("GET", "/api/events", { session: "s-ev" });
    expect(ninth.status).toBe(429);
    expect(await ninth.json()).toMatchObject({ error: "too_many_streams" });
    await streams[0]!.body!.cancel();
    let again: Response | undefined;
    for (let i = 0; i < 50 && again?.status !== 200; i++) {
      await new Promise((r) => setTimeout(r, 20));
      again = await h.ask("GET", "/api/events", { session: "s-ev" });
      if (again.status === 200) break;
    }
    expect(again!.status).toBe(200);
    for (const r of [...streams.slice(1), again!]) await r.body!.cancel();
  });

  test("names its route in the log, never the session", async () => {
    const h = live();
    await until(await h.ask("GET", "/api/events", { session: "s-ev" }), "nothing", 50);
    expect(h.logs.some((l) => l.includes('"route":"/api/events"'))).toBe(true);
    expect(h.logs.join("\n")).not.toContain("s-ev");
  });
});

describe("the page's listener", () => {
  class FakeSource {
    listeners = new Map<string, (e?: { data?: unknown }) => void>();
    closed = false;
    addEventListener(type: string, l: (e?: { data?: unknown }) => void) {
      this.listeners.set(type, l);
    }
    close() {
      this.closed = true;
    }
    emit(type: string, data?: unknown) {
      this.listeners.get(type)?.(data === undefined ? undefined : { data });
    }
  }

  test("leaves a notice that opens the player on player_online, and re-reads who is online", async () => {
    const { listen } = await import("../../src/web/frame/events.ts");
    const notices = await import("../../src/web/areas/outreach/notices.tsx");
    const src = new FakeSource();
    const invalidated: unknown[] = [];
    listen(() => src, { invalidateQueries: async (q?: unknown) => void invalidated.push(q) } as never, () => undefined);
    src.emit("player_online", JSON.stringify({ playerId: "p-ana", emailPrefix: "ana.souza", sessionId: "s1", occurredAt: "2026-11-16T10:00:00Z" }));
    src.emit("player_online", "not json");
    src.emit("presence", JSON.stringify({ online: [], count: 0 }));
    expect(notices.onlineNoticesNow().map((n) => n.playerId)).toEqual(["p-ana"]);
    notices.dismissOnline("p-ana");
    expect(notices.onlineNoticesNow()).toEqual([]);
    expect(invalidated).toEqual([{ queryKey: ["presence"] }, { queryKey: ["presence"] }, { queryKey: ["presence"] }]);
    expect(notices.onlineNoticeOf('{"playerId":"p-ana","emailPrefix":"ana.souza"}')).toEqual({ playerId: "p-ana", emailPrefix: "ana.souza", sessionId: "", occurredAt: "" });
    expect(notices.onlineNoticeOf('{"emailPrefix":"x"}')).toBeUndefined();
    expect(notices.playerHref("p ana")).toBe("/players/p%20ana");
  });

  test("re-reads the operator and the brand's roles on permissions_changed", async () => {
    const { listen } = await import("../../src/web/frame/events.ts");
    const src = new FakeSource();
    const invalidated: unknown[] = [];
    listen(() => src, { invalidateQueries: async (q?: unknown) => void invalidated.push(q) } as never, () => undefined);
    src.emit("permissions_changed");
    expect(invalidated).toEqual([{ queryKey: ["me"] }, { queryKey: ["permissions"] }]);
  });

  test("goes to the sign-in on signed_out, and stops listening", async () => {
    const { listen } = await import("../../src/web/frame/events.ts");
    const src = new FakeSource();
    const went: string[] = [];
    (globalThis as { window?: unknown }).window ??= { location: { pathname: "/players", search: "" } };
    listen(() => src, { invalidateQueries: async () => undefined } as never, (h) => void went.push(h));
    src.emit("signed_out");
    expect(src.closed).toBe(true);
    expect(went[0]).toMatch(/^\/auth\/sign-in\?return=/);
  });
});

// T121: for an operator who asked, engagement's agent stream relayed as `player_online`.
describe("online notices", () => {
  const P = "/v1/casino/backoffice/{brand}";
  const Engagement: Row[] = [
    { method: "GET", service: "engagement", path: "/presence", upstream: `${P}/presence`, where: "brand", permission: "presence.read" },
    { method: "POST", service: "engagement", path: "/agent-stream/ticket", upstream: `${P}/agent-stream/ticket`, where: "brand", permission: "presence.read" },
  ];
  const Token = `h.${Buffer.from(JSON.stringify({ sub: "op-ana" })).toString("base64url")}.s`;
  const frame = (data: Record<string, unknown>) => `event: player_online\ndata: ${JSON.stringify(data)}\n\n`;
  const ana = { player_id: "p-ana", player_email_prefix: "ana.souza", session_id: "s1", occurred_at: "2026-11-16T10:00:00Z" };

  /** engagement's agent stream: each opening a stream the test writes to and can end; `cancelled` counts the streams the relay closed. */
  function engagement() {
    const streams: ReadableStreamDefaultController<Uint8Array>[] = [];
    let cancelled = 0;
    let presence = 0;
    const encoder = new TextEncoder();
    const answer = (call: Call): Response => {
      const path = new URL(call.url).pathname;
      if (call.method === "POST" && path === `/engagement/v1/casino/backoffice/${Brand}/agent-stream/ticket`) return Response.json({ ticket: `tk-${streams.length}`, expires_in: 30 });
      if (call.method === "GET" && path === `/engagement/v1/casino/backoffice/${Brand}/agent-stream`)
        return new Response(
          new ReadableStream<Uint8Array>({
            start: (c) => void streams.push(c),
            cancel: () => void cancelled++,
          }),
          { headers: { "Content-Type": "text/event-stream" } },
        );
      if (call.method === "GET" && path === `/engagement/v1/casino/backoffice/${Brand}/presence`) {
        presence++;
        return Response.json({ online: [{ playerId: "p-ana", since: "2026-11-16T10:00:00Z" }], count: 1 });
      }
      return Response.json({ error: "no_such_route" }, { status: 404 });
    };
    return {
      answer,
      streams,
      write: (i: number, text: string) => streams[i]!.enqueue(encoder.encode(text)),
      cancelled: () => cancelled,
      presence: () => presence,
    };
  }

  function asking(asked: boolean) {
    const e = engagement();
    const h = harness({ eventsEveryMs: 20, table: [...rows, ...Engagement] });
    h.state.sessions.set("s-on", { accessToken: Token, expiresAt: Date.now() + 10_000, roles: [`brand_${Brand}`, "brand_support"] });
    h.state.prefs = { ...h.state.prefs, onlineNotices: asked };
    h.answer(e.answer);
    return { ...h, e };
  }

  const tickets = (calls: Call[]) => calls.filter((c) => c.method === "POST" && c.url.endsWith("/agent-stream/ticket"));

  test("takes no ticket for an operator who did not ask", async () => {
    const h = asking(false);
    await until(await h.ask("GET", "/api/events", { session: "s-on" }), "player_online", 150);
    expect(tickets(h.calls)).toHaveLength(0);
  });

  test("for an operator who asked, takes the ticket with their token, opens the stream with it, and relays player_online", async () => {
    const h = asking(true);
    const r = await h.ask("GET", "/api/events", { session: "s-on" });
    await expect.poll(() => h.e.streams.length).toBe(1);
    h.e.write(0, ": ping\n\nevent: ping\ndata: {}\n\n" + frame(ana));
    const seen = await until(r, "player_online");
    expect(seen).toContain("event: player_online");
    expect(seen).toContain(JSON.stringify({ playerId: "p-ana", emailPrefix: "ana.souza", sessionId: "s1", occurredAt: "2026-11-16T10:00:00Z" }));
    const [ticket] = tickets(h.calls);
    expect(ticket!.headers.get("authorization")).toBe(`Bearer ${Token}`);
    const opened = h.calls.find((c) => c.method === "GET" && new URL(c.url).pathname.endsWith("/agent-stream"))!;
    expect(new URL(opened.url).searchParams.get("ticket")).toBe("tk-0");
    expect(opened.headers.get("authorization")).toBeNull();
  });

  test("closes engagement's stream when the operator's stream ends", async () => {
    const h = asking(true);
    const r = await h.ask("GET", "/api/events", { session: "s-on" });
    await expect.poll(() => h.e.streams.length).toBe(1);
    await r.body!.cancel();
    await expect.poll(() => h.e.cancelled()).toBe(1);
    await new Promise((res) => setTimeout(res, 100));
    expect(tickets(h.calls)).toHaveLength(1);
  });

  test("closes engagement's stream when the operator is no longer of the brand", async () => {
    const h = asking(true);
    const r = await h.ask("GET", "/api/events", { session: "s-on" });
    const read = until(r, "nothing", 1_500); // the page reads its stream all the while
    await expect.poll(() => h.e.streams.length).toBe(1);
    h.state.sessions.set("s-on", { accessToken: Token, expiresAt: Date.now() + 10_000, roles: ["brand_support"] });
    await expect.poll(() => h.e.cancelled()).toBe(1);
    expect(await read).not.toContain("event: player_online");
  });

  describe("the relay", () => {
    function relayed(over: Partial<Parameters<typeof relayOnline>[0]> = {}) {
      const e = engagement();
      const calls: Call[] = [];
      const sent: { event: string; data: string }[] = [];
      const stop = new AbortController();
      const fetchFn: typeof fetch = async (input, init) => {
        const req = new Request(input, init);
        const call = { url: req.url, method: req.method, headers: req.headers, body: "" };
        calls.push(call);
        return e.answer(call);
      };
      const done = relayOnline(
        { brand: Brand, servicesUrl: "http://services.local", sharedProjects: {}, table: Engagement, fetch: fetchFn, timeoutMs: 1_000, backoff: { firstMs: 10, mostMs: 40 }, ...over },
        () => Token,
        async (event, data) => void sent.push({ event, data }),
        stop.signal,
      );
      return { e, calls, sent, stop, done };
    }

    test("drops another brand's player and anything that is not a player coming online", async () => {
      const r = relayed();
      await expect.poll(() => r.e.streams.length).toBe(1);
      r.e.write(0, frame({ ...ana, brand: "spinvibe", player_id: "p-other" }) + frame({ nothing: true }) + "event: player_online\ndata: not json\n\n" + frame({ ...ana, brand: Brand }));
      await expect.poll(() => r.sent.length).toBe(1);
      expect(JSON.parse(r.sent[0]!.data)).toMatchObject({ playerId: "p-ana" });
      r.stop.abort();
      await r.done;
    });

    test("after engagement drops the stream on an overflow, takes a new ticket, opens it again, and re-reads presence", async () => {
      const r = relayed();
      await expect.poll(() => r.e.streams.length).toBe(1);
      expect(r.e.presence()).toBe(0);
      r.e.streams[0]!.close(); // engagement's overflow: the connection ends with no frame
      await expect.poll(() => r.e.streams.length).toBe(2);
      await expect.poll(() => r.sent.find((s) => s.event === "presence")).toBeDefined();
      expect(JSON.parse(r.sent.find((s) => s.event === "presence")!.data)).toEqual({ online: [{ playerId: "p-ana", since: "2026-11-16T10:00:00Z" }], count: 1 });
      expect(tickets(r.calls)).toHaveLength(2);
      r.e.write(1, frame(ana));
      await expect.poll(() => r.sent.filter((s) => s.event === "player_online").length).toBe(1);
      r.stop.abort();
      await r.done;
      expect(r.e.cancelled()).toBeGreaterThanOrEqual(1);
    });

    test("waits longer between attempts while engagement refuses the ticket, and stops when the operator goes", async () => {
      let asked = 0;
      const r = relayed({
        fetch: async () => {
          asked++;
          return Response.json({ error: "forbidden" }, { status: 403 });
        },
      });
      await new Promise((res) => setTimeout(res, 200));
      r.stop.abort();
      await r.done;
      const then = asked;
      // the first at once, then one each 40 ms (refused: the longest wait), never one each 10 ms
      expect(then).toBeGreaterThanOrEqual(2);
      expect(then).toBeLessThanOrEqual(7);
      await new Promise((res) => setTimeout(res, 100));
      expect(asked).toBe(then);
    });

    test("a stream that says nothing for too long is opened again", async () => {
      const r = relayed({ idleMs: 30 });
      await expect.poll(() => r.e.streams.length).toBeGreaterThanOrEqual(2);
      r.stop.abort();
      await r.done;
    });
  });
});

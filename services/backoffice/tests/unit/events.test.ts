// T050: `/api/events` tells the page when what the operator may do changed, and when the session ended.
import { describe, expect, test } from "vitest";
import { Kept, type Bags } from "../../src/server/roles.ts";
import { OperatorBags } from "./bags.ts";
import { Brand, harness } from "./support.ts";

/** A harness whose roles are read at every comparison, and whose token is never held (it expires within the margin). */
function live() {
  let bags: Bags | Error = OperatorBags;
  const h = harness({
    eventsEveryMs: 20,
    reads: { bags: new Kept<Bags>(async () => { if (bags instanceof Error) throw bags; return bags; }, 0, () => Date.now()), modules: new Kept<string[]>(async () => ["core"], 0, () => Date.now()) },
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

  test("names its route in the log, never the session", async () => {
    const h = live();
    await until(await h.ask("GET", "/api/events", { session: "s-ev" }), "nothing", 50);
    expect(h.logs.some((l) => l.includes('"route":"/api/events"'))).toBe(true);
    expect(h.logs.join("\n")).not.toContain("s-ev");
  });
});

describe("the page's listener", () => {
  class FakeSource {
    listeners = new Map<string, () => void>();
    closed = false;
    addEventListener(type: string, l: () => void) {
      this.listeners.set(type, l);
    }
    close() {
      this.closed = true;
    }
    emit(type: string) {
      this.listeners.get(type)?.();
    }
  }

  test("re-reads the operator on permissions_changed", async () => {
    const { listen } = await import("../../src/web/frame/events.ts");
    const src = new FakeSource();
    const invalidated: unknown[] = [];
    listen(() => src, { invalidateQueries: async (q?: unknown) => void invalidated.push(q) } as never, () => undefined);
    src.emit("permissions_changed");
    expect(invalidated).toEqual([{ queryKey: ["me"] }]);
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

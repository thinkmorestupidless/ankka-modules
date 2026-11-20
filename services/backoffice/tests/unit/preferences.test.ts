// T053: `PUT /api/preferences` writes the operator's own theme, worklist kinds and online notices.
import { describe, expect, test } from "vitest";
import { Brand, harness, write } from "./support.ts";

const jwt = (claims: object) => `h.${Buffer.from(JSON.stringify(claims)).toString("base64url")}.sig`;

function signedIn() {
  const h = harness();
  h.state.sessions.set("s-p", { accessToken: jwt({ sub: "op-7" }), expiresAt: Date.now() + 300_000, roles: [`brand_${Brand}`, `brand_support@${Brand}`] });
  return h;
}

const put = (h: ReturnType<typeof harness>, body: unknown, headers: Record<string, string> = write) =>
  h.ask("PUT", "/api/preferences", { session: "s-p", headers, body: JSON.stringify(body) });

describe("PUT /api/preferences", () => {
  test("writes the operator's own preferences, named by their token, and answers them all", async () => {
    const h = signedIn();
    const r = await put(h, { theme: "light", onlineNotices: true });
    expect(r.status).toBe(200);
    expect(h.state.written).toEqual([{ subject: "op-7", body: JSON.stringify({ theme: "light", onlineNotices: true }) }]);
    expect(await r.json()).toMatchObject({ theme: "light", onlineNotices: true, jobs: [] });
  });

  test("refuses jobs, which only /api/jobs writes, and any other key, without asking the state service", async () => {
    const h = signedIn();
    for (const body of [{ jobs: [] }, { theme: "dark", player: "p-1" }]) {
      const r = await put(h, body);
      expect(r.status).toBe(400);
      expect(((await r.json()) as { error: string }).error).toBe("unknown_preference");
    }
    expect(h.state.written).toEqual([]);
  });

  test("refuses a body that is not an object", async () => {
    const h = signedIn();
    expect((await put(h, ["light"])).status).toBe(400);
    expect((await h.ask("PUT", "/api/preferences", { session: "s-p", headers: write, body: "{" })).status).toBe(400);
  });

  test("answers the state service's refusal as it gave it", async () => {
    const h = signedIn();
    h.state.writeAnswer = () => ({ status: 400, body: JSON.stringify({ error: "invalid_preference", message: "theme is dark or light" }) });
    const r = await put(h, { theme: "pink" });
    expect(r.status).toBe(400);
    expect(await r.json()).toEqual({ error: "invalid_preference", message: "theme is dark or light" });
  });

  test("is 503 when the state service cannot be reached", async () => {
    const h = signedIn();
    h.state.writeAnswer = new Error("down");
    expect((await put(h, { theme: "light" })).status).toBe(503);
  });

  test("is a write: refused without the page's header and origin", async () => {
    const h = signedIn();
    expect((await put(h, { theme: "light" }, { "Content-Type": "application/json" })).status).toBe(403);
    expect(h.state.written).toEqual([]);
  });

  test("needs a session", async () => {
    const r = await signedIn().ask("PUT", "/api/preferences", { headers: write, body: "{}" });
    expect(r.status).toBe(401);
  });
});

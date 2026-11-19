import { afterEach, describe, expect, it } from "vitest";
import { wholeSite } from "./helpers.ts";

let close: (() => Promise<void>) | undefined;
afterEach(async () => {
  await close?.();
  close = undefined;
});

describe("a change comes only from the site's own pages (research R3)", () => {
  it("refuses a request that is not a GET from another origin, or from none, and asks no service", async () => {
    const s = await wholeSite();
    close = s.close;
    for (const origin of ["https://evil.example", null]) {
      const r = await s.request("POST", "/api/session/sign-in", { origin, body: { email: "ana@example.com", password: "correct horse battery 1!" } });
      expect(r.status).toBe(403);
      expect(await r.json()).toEqual({ error: "wrong_origin" });
    }
    expect(s.doubles.asked).toEqual([]);
  });

  it("answers a GET from anywhere, and a change from its own origin", async () => {
    const s = await wholeSite();
    close = s.close;
    expect((await s.request("GET", "/api/site", { headers: { origin: "https://evil.example" } })).status).toBe(200);
    expect((await s.signIn()).status).toBe(200);
  });
});

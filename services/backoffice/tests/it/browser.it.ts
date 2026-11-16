// FR-002, FR-003 (T127): in a full session in a browser, every request after the sign-in goes to the
// backoffice's own address, and no script can read the sign-in: the session cookie is HttpOnly, no storage
// a script reads holds a token, and no response the page receives holds one.
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { installation, operators, readSeed, seeded } from "./support.ts";
import { openSignedIn, settle, type Signed } from "./page.ts";

/** A JSON Web Token's shape: three base64url parts, the first a JSON object's encoding. */
const Jwt = /eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}/;

describe("the browser talks only to the backoffice and never holds the token", () => {
  const inst = installation();
  let s: Signed;
  const bodies: { url: string; jwt: boolean; headerJwt: boolean }[] = [];

  beforeAll(async () => {
    const seed = readSeed(inst);
    s = await openSignedIn(inst, operators(inst).brand_admin);
    s.page.on("response", async (r) => {
      const headerJwt = Object.values(r.headers()).some((v) => Jwt.test(v));
      let jwt = false;
      try {
        if (/json|text|javascript|html/.test(r.headers()["content-type"] ?? "")) jwt = Jwt.test(await r.text());
      } catch {
        // a body not kept (a redirect, a stream closed): nothing to read
      }
      bodies.push({ url: r.url(), jwt, headerJwt });
    });
    const p = seeded(seed).withdrawals[0]!;
    // a full session: the worklist, search, a player and each of their tabs, a request, the lists, the team
    const routes = [
      "/",
      `/search?q=${encodeURIComponent(p.playerId!)}`,
      `/players/${encodeURIComponent(p.playerId!)}`,
      ...["timeline", "profile", "notes", "wallets", "games", "sessions", "withdrawals", "identity", "protection", "rewards", "messages"].map((t) => `/players/${encodeURIComponent(p.playerId!)}/${t}`),
      `/withdrawals/${encodeURIComponent(p.withdrawal!.requestId)}`,
      "/players",
      "/withdrawals",
      "/rewards",
      "/identity",
      "/dashboard",
      "/reports",
      "/configuration",
      "/payments",
      "/team",
      "/jobs",
      "/preferences",
    ];
    for (const r of routes) {
      await s.page.goto(`${inst.backoffice}${r}`);
      await settle(s.page);
    }
  });
  afterAll(async () => s?.close());

  it("every request after the sign-in goes to the backoffice's address (FR-002)", () => {
    const own = new URL(inst.backoffice).origin;
    const elsewhere = s.seen.filter((r) => !r.url.startsWith("data:") && !r.url.startsWith("blob:") && new URL(r.url).origin !== own);
    expect(s.seen.length).toBeGreaterThan(20);
    expect(elsewhere.map((r) => `${r.method} ${r.url}`)).toEqual([]);
  });

  it("the session cookie is HttpOnly, Secure and the backoffice's alone (FR-003)", async () => {
    const cookies = await s.context.cookies();
    const session = cookies.find((c) => c.name === "__Host-bo");
    expect(session, "the session cookie").toBeDefined();
    expect(session).toMatchObject({ httpOnly: true, secure: true, path: "/" });
    expect(await s.page.evaluate(() => document.cookie), "a script reads no cookie of the sign-in").not.toMatch(/__Host-bo/);
  });

  it("no storage a script reads holds a token (FR-003)", async () => {
    const stored = await s.page.evaluate(async () => {
      const all: string[] = [document.cookie];
      for (const store of [localStorage, sessionStorage]) for (let i = 0; i < store.length; i++) all.push(store.getItem(store.key(i)!) ?? "");
      const dbs = (await indexedDB.databases?.()) ?? [];
      all.push(JSON.stringify(dbs));
      all.push(document.documentElement.outerHTML);
      return all.join("\n");
    });
    expect(Jwt.test(stored)).toBe(false);
  });

  it("no response the page receives holds a token (FR-003)", () => {
    const own = new URL(inst.backoffice).origin;
    const leaking = bodies.filter((b) => new URL(b.url).origin === own && (b.jwt || b.headerJwt)).map((b) => b.url);
    expect(bodies.length).toBeGreaterThan(20);
    expect(leaking).toEqual([]);
  });
});

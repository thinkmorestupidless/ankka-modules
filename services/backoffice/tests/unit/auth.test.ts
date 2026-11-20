import { describe, expect, test } from "vitest";
import { createHash } from "node:crypto";
import { returnPath } from "../../src/server/auth.ts";
import { Brand, harness, Origin, write } from "./support.ts";

const cookieOf = (r: Response, name: string) =>
  r.headers.getSetCookie().find((c) => c.startsWith(`${name}=`));

describe("signing in", () => {
  test("starts the code flow with PKCE at the eitheror realm, as the brand's own client", async () => {
    const h = harness();
    const r = await h.ask("GET", "/auth/sign-in?return=/players/p-1");
    expect(r.status).toBe(302);
    const to = new URL(r.headers.get("location")!);
    expect(`${to.origin}${to.pathname}`).toBe("https://realm.example.test/realms/eitheror/protocol/openid-connect/auth");
    expect(to.searchParams.get("client_id")).toBe(`backoffice-${Brand}`);
    expect(to.searchParams.get("redirect_uri")).toBe(`${Origin}/auth/callback`);
    expect(to.searchParams.get("code_challenge_method")).toBe("S256");
    const kept = cookieOf(r, "__Host-bo-signin")!;
    expect(kept).toMatch(/HttpOnly/);
    expect(kept).toMatch(/Secure/);
    const [state, verifier] = decodeURIComponent(kept.split(";")[0]!.split("=")[1]!).split(".");
    expect(to.searchParams.get("state")).toBe(state);
    expect(to.searchParams.get("code_challenge")).toBe(createHash("sha256").update(verifier!).digest("base64url"));
  });

  test.each([
    ["/players/p-1?tab=wallets", "/players/p-1?tab=wallets"],
    ["https://evil.example/", "/"],
    ["//evil.example/", "/"],
    ["/\\evil.example", "/"],
    ["/auth/sign-out", "/"],
    [undefined, "/"],
  ])("return %s goes back to %s", (asked, back) => expect(returnPath(asked)).toBe(back));
});

describe("the callback", () => {
  async function begun(h: ReturnType<typeof harness>, back = "/withdrawals") {
    const r = await h.ask("GET", `/auth/sign-in?return=${encodeURIComponent(back)}`);
    const kept = cookieOf(r, "__Host-bo-signin")!.split(";")[0]!;
    const state = new URL(r.headers.get("location")!).searchParams.get("state")!;
    return { kept, state };
  }

  test("asks the state service to exchange the code, keeps the session in __Host-bo, and goes back", async () => {
    const h = harness();
    const asked: unknown[] = [];
    h.state.open = async (code, verifier, redirectUri) => {
      asked.push({ code, verifier, redirectUri });
      return { session: "s-new", subject: "op-1", expiresAt: Date.now() + 3_600_000 };
    };
    const { kept, state } = await begun(h);
    const r = await h.ask("GET", `/auth/callback?code=c-1&state=${state}`, { headers: { Cookie: kept } });
    expect(r.status).toBe(302);
    expect(r.headers.get("location")).toBe("/withdrawals");
    const session = cookieOf(r, "__Host-bo")!;
    expect(session).toMatch(/^__Host-bo=s-new;/);
    expect(session).toMatch(/HttpOnly/);
    expect(session).toMatch(/Secure/);
    expect(session).toMatch(/SameSite=Lax/);
    expect(session).toMatch(/Path=\//);
    expect(session).not.toMatch(/Domain=/i);
    expect(asked).toEqual([{ code: "c-1", verifier: expect.stringMatching(/^[A-Za-z0-9_-]{43}$/), redirectUri: `${Origin}/auth/callback` }]);
  });

  test("a state that is not this browser's is refused, and nothing is exchanged", async () => {
    const h = harness();
    let exchanged = false;
    h.state.open = async () => {
      exchanged = true;
      throw new Error("no");
    };
    const { kept } = await begun(h);
    const r = await h.ask("GET", "/auth/callback?code=c-1&state=someone-elses", { headers: { Cookie: kept } });
    expect(r.status).toBe(400);
    expect(exchanged).toBe(false);
  });

  test("without the sign-in's cookie it is refused", async () => {
    const h = harness();
    const r = await h.ask("GET", "/auth/callback?code=c-1&state=x");
    expect(r.status).toBe(400);
  });
});

describe("signing out", () => {
  test("ends the session at the state service and clears the cookie", async () => {
    const h = harness();
    const r = await h.ask("POST", "/auth/sign-out", { session: "s-1", headers: write });
    expect(r.status).toBe(200);
    expect(h.state.ended).toEqual(["s-1"]);
    expect(cookieOf(r, "__Host-bo")).toMatch(/Max-Age=0/);
    const after = await h.ask("GET", "/api/me", { session: "s-1" });
    expect(after.status).toBe(401);
  });
});

// The doubles hold what the browser suites rely on: the server signs in through the double realm and
// `backoffice-state`, passes calls to an owner and a shared project's owner with the operator's token,
// and a scripted failure, delay or proxy answer reaches the page as the server makes it.
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { createApp } from "../../src/server/main.ts";
import { stateClient } from "../../src/server/session.ts";
import { table } from "../../src/server/routes.ts";
import { startDoubles, type Doubles } from "./server.ts";
import { DoublesClient, claimsOf } from "./client.ts";
import { operator } from "./env.ts";
import { IdentityCookie, identityValue } from "./realm.ts";

const Address = "http://localhost:4799";
let d: Doubles;
let client: DoublesClient;
let app: ReturnType<typeof createApp>;

beforeAll(async () => {
  d = await startDoubles(0);
  client = new DoublesClient(d.url);
  app = createApp({
    brand: "goldvibe",
    servicesUrl: d.url,
    sharedProjects: { payments: "payments", programme: "programme", reasoner: "reasoner", games: "games" },
    state: stateClient(d.url, fetch),
    fetch,
    log: () => {},
    now: () => Date.now(),
    table,
    timeoutMs: 1_000,
    realm: { issuer: `${d.url}/realms/eitheror`, clientId: "backoffice-goldvibe" },
    address: Address,
  });
});
afterAll(() => d.close());
beforeEach(() => client.reset());

const cookiesOf = (r: Response) => r.headers.getSetCookie().map((c) => c.split(";")[0]!);

/** The code flow as a browser runs it, through the server and the double realm. */
async function signIn(who = operator("brand_admin")): Promise<string> {
  const start = await app.request(`${Address}/auth/sign-in?return=/players`);
  const signInCookie = cookiesOf(start).join("; ");
  const atRealm = await fetch(start.headers.get("location")!, { redirect: "manual", headers: { Cookie: `${IdentityCookie}=${identityValue(who)}` } });
  expect(atRealm.status).toBe(302);
  const back = new URL(atRealm.headers.get("location")!);
  const done = await app.request(`${Address}${back.pathname}${back.search}`, { headers: { Cookie: signInCookie } });
  expect(done.status).toBe(302);
  expect(done.headers.get("location")).toBe("/players");
  const session = cookiesOf(done).find((c) => c.startsWith("__Host-bo="));
  expect(session).toBeDefined();
  return session!;
}

describe("the doubles", () => {
  it("sign an operator in, and the server knows them from the token", async () => {
    const cookie = await signIn(operator("brand_finance"));
    const me = await app.request(`${Address}/api/me`, { headers: { Cookie: cookie } });
    expect(me.status).toBe(200);
    const body = (await me.json()) as { subject: string; roles: string[]; permissions: string[]; brand: { modules: string[] }; preferences: { theme: string } };
    expect(body.subject).toBe("op-brand-finance");
    expect(body.roles).toEqual(["brand_finance", "brand_goldvibe"]);
    expect(body.permissions).toContain("adjustment.make");
    expect(body.brand.modules).toEqual(["casino", "core"]);
    expect(body.preferences.theme).toBe("dark");
  });

  it("refuse a sign-in when no operator was chosen", async () => {
    const start = await app.request(`${Address}/auth/sign-in`);
    const atRealm = await fetch(start.headers.get("location")!, { redirect: "manual" });
    expect(new URL(atRealm.headers.get("location")!).searchParams.get("error")).toBe("login_required");
  });

  it("answer an owner and a shared project's owner from fixtures, and keep the token each call carried", async () => {
    const cookie = await signIn();
    const profile = await app.request(`${Address}/api/s/players/players/p-kenji/profile`, { headers: { Cookie: cookie } });
    expect(profile.status).toBe(200);
    expect(((await profile.json()) as { player: { currency: string } }).player.currency).toBe("JPY");
    await client.fixtures("merchant.payments", { "GET /v1/payments/cashier": { body: { items: [], total: 0 } } });
    const cashier = await app.request(`${Address}/api/s/merchant/cashier`, { headers: { Cookie: cookie } });
    expect(cashier.status).toBe(200);
    expect(await client.expectAuthorizedAs("players", "op-brand-admin", { path: /\/profile$/ })).toBe(1);
    const [call] = await client.calls("merchant.payments");
    expect(call!.path).toBe("/v1/payments/cashier");
    expect(claimsOf(call!)?.realm_access?.roles).toContain("brand_admin");
  });

  it("refuse an operator route called with no token", async () => {
    const r = await fetch(`${d.url}/players/v1/casino/backoffice/goldvibe/players`);
    expect(r.status).toBe(401);
  });

  it("fail, slow and proxy answers as told", async () => {
    const cookie = await signIn();
    const ask = () => app.request(`${Address}/api/s/players/players/p-ana/profile`, { headers: { Cookie: cookie } });
    await client.fail("players", 409, { path: "/v1/casino/backoffice/{brand}/players/{playerId}/profile", times: 1, body: { error: "suspected_duplicate" } });
    expect((await ask()).status).toBe(409);
    expect((await ask()).status).toBe(200);
    await client.script("players", { proxy: true, status: 503, times: 1 });
    expect(await (await ask()).json()).toEqual({ error: "source_unavailable", source: "players" });
    await client.slow("players", 1_500, { times: 1 });
    expect((await ask()).status).toBe(503);
    await client.answer("players", "players/profile-suspected-duplicate", { times: 1 });
    expect((await ask()).status).toBe(409);
  });

  it("change a subject's roles at its next token", async () => {
    await client.tokenLife(0);
    const cookie = await signIn(operator("brand_support"));
    await client.roles("op-brand-support", ["brand_goldvibe", "brand_finance"]);
    const me = (await (await app.request(`${Address}/api/me`, { headers: { Cookie: cookie } })).json()) as { roles: string[] };
    expect(me.roles).toEqual(["brand_finance", "brand_goldvibe"]);
  });

  it("answer a shared project that is not there as the proxy would", async () => {
    const r = await fetch(`${d.url}/audit.reasoner/v1/x`);
    expect(r.status).toBe(503);
    expect(r.headers.get("x-ankka-answered-by")).toBe("proxy");
  });
});

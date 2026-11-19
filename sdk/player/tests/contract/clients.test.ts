// The module clients against the running brand (spec 027 FR-036, T051): one test per client
// function, in the order a player meets them, each through the site server as a page calls it. An
// `ok` answer has been parsed by the client's own shape, so it is the route's contract that is
// checked: a route that changed what it answers fails here, where its client is.
//
//   PLAYER_SITE_URL   the site (default http://localhost:3310, `local/guyanabet`)
//   PLAYERS_URL       players, for the operator's verification link (default http://127.0.0.1:9202)
//   STAFF_TOKEN       a brand operator's token, for that link alone (`local/guyanabet/operator-token.sh`)
//
// Run by `just player-site-it`. The brand takes no real money: the link is answered only there.
import { readFileSync } from "node:fs";
import path from "node:path";
import { beforeAll, describe, expect, test } from "vitest";
import { amount } from "../../src/amount.ts";
import { casinoClient } from "../../src/casino/client.ts";
import { coreClient } from "../../src/core/client.ts";
import { engagementClient } from "../../src/engagement/client.ts";
import { supportClient } from "../../src/support/client.ts";
import { pageTransport } from "../../src/transport.ts";

const site = (process.env.PLAYER_SITE_URL ?? "http://localhost:3310").replace(/\/+$/, "");
const players = (process.env.PLAYERS_URL ?? "http://127.0.0.1:9202").replace(/\/+$/, "");
const brand = process.env.PLAYER_SITE_BRAND ?? "guyanabet";
const staffToken = () =>
  process.env.STAFF_TOKEN ??
  readFileSync(path.resolve(import.meta.dirname, `../../../../local/${brand}/.ankka/local/staff-token`), "utf8").trim();

/** The browser's part: the site's own origin on every call, and its cookies kept between calls. */
function browser() {
  const jar = new Map<string, string>();
  return async (input: string, init: RequestInit = {}) => {
    const headers = new Headers(init.headers);
    headers.set("origin", site);
    if (jar.size) headers.set("cookie", [...jar].map(([k, v]) => `${k}=${v}`).join("; "));
    const r = await fetch(input.startsWith("http") ? input : site + input, { ...init, headers, redirect: "manual" });
    for (const c of r.headers.getSetCookie()) {
      const [pair] = c.split(";");
      const at = pair!.indexOf("=");
      const name = pair!.slice(0, at);
      const value = pair!.slice(at + 1);
      if (value === "" || /max-age=0/i.test(c)) jar.delete(name);
      else jar.set(name, value);
    }
    return r;
  };
}

const transport = pageTransport(browser(), site);
let playerId: string | null = null;
const core = coreClient(transport, () => playerId);
const casino = casinoClient(transport, () => playerId);
const engagement = engagementClient(transport);
const support = supportClient(transport, () => brand);

const email = `contract-${Date.now()}@${brand}.test`;
const password = "Local-only-pass1!";
const EUR = (minor: string) => amount(BigInt(minor), "EUR");

let verificationToken = "";
let ticket = "";
let terminalId = "";
let paymentId = "";
let gameId = "";
let gameSession = "";

/**
 * What a browser does at a payment's address: load the gateway's relay page and post its form to
 * the provider (the stub's checkout), which then notifies the gateway as the provider would.
 */
async function payAsBrowser(address: string): Promise<void> {
  const page = await fetch(address);
  expect(page.status, "the relay page").toBe(200);
  const html = await page.text();
  const action = /<form[^>]*action="([^"]+)"/i.exec(html)?.[1];
  expect(action, "the relay page posts a form").toBeTruthy();
  const fields = new URLSearchParams();
  for (const m of html.matchAll(/<input[^>]*name="([^"]+)"[^>]*value="([^"]*)"/gi)) fields.set(m[1]!, decode(m[2]!));
  const posted = await fetch(decode(action!), { method: "POST", body: fields, redirect: "manual" });
  expect(posted.status, "the provider's checkout").toBeLessThan(400);
}

const decode = (s: string) => s.replace(/&amp;/g, "&").replace(/&quot;/g, '"').replace(/&#39;/g, "'").replace(/&lt;/g, "<").replace(/&gt;/g, ">");

function ok<T>(r: { ok: true; value: T } | { ok: false; refusal: unknown }): T {
  if (!r.ok) throw new Error(`refused: ${JSON.stringify(r.refusal)}`);
  return r.value;
}

describe(`the module clients against ${brand} at ${site}`, () => {
  beforeAll(async () => {
    const r = await fetch(`${site}/api/site`);
    expect(r.status, "the site answers").toBe(200);
  });

  describe("core, before signing in", () => {
    test("register", async () => {
      const v = ok(await core.register({ email, password, currency: "EUR", country: "GF", dateOfBirth: "1990-01-01",
        firstName: "Contrat", lastName: "Test", language: "fr" }));
      expect(v.status).toBe("pending_email_verification");
      playerId = v.playerId;
      const link = await fetch(`${players}/v1/casino/backoffice/${brand}/players/${v.playerId}/verification-link`,
        { headers: { authorization: `Bearer ${staffToken()}` } });
      expect(link.status, "the operator's verification link").toBe(200);
      verificationToken = new URL(((await link.json()) as { link: string }).link).searchParams.get("token") ?? "";
      expect(verificationToken).not.toBe("");
    });

    test("resendVerification", async () => {
      // Within the resend interval of registering: refused by its code, which is the route's answer too.
      const r = await core.resendVerification(email);
      if (!r.ok) expect(r.refusal.code).toMatch(/too_many|rate|resend/i);
    });

    test("verifyEmail", async () => {
      expect(ok(await core.verifyEmail(verificationToken)).status).toBe("verified");
    });

    test("requestPasswordReset", async () => {
      ok(await core.requestPasswordReset(email));
    });

    test("country", async () => {
      const v = ok(await core.country());
      expect(v).toHaveProperty("country");
    });
  });

  describe("core, signed in", () => {
    beforeAll(async () => {
      const r = await transport.session.signIn({ email, password });
      if (!r.ok) throw new Error(`sign-in refused: ${JSON.stringify(r.refusal)}`);
      expect(r.player.playerId).toBe(playerId);
    });

    test("profile", async () => {
      expect(Object.keys(ok(await core.profile())).length).toBeGreaterThan(0);
    });

    test("changeProfile", async () => {
      // A field a player may edit (players' Profile.editable); the deposit's details step writes these.
      ok(await core.changeProfile({ city: "Cayenne" }));
    });

    test("balance", async () => {
      const v = ok(await core.balance("EUR"));
      expect(v.real.minor).toBe(0n);
      expect(v.real.currency).toBe("EUR");
    });

    test("protection", async () => {
      const v = ok(await core.protection("EUR"));
      expect(v.isCoolingOff).toBe(false);
    });

    test("unread", async () => {
      expect(ok(await core.unread())).toBeGreaterThanOrEqual(0);
    });

    test("startDeposit", async () => {
      const v = ok(await core.startDeposit(EUR("5000"), `contract-${Date.now()}`));
      expect(v.terminals.length).toBeGreaterThan(0);
      ticket = v.cashierTicket;
      terminalId = v.terminals[0]!.id;
    });

    test("submitCashier", async () => {
      const v = ok(await core.submitCashier(ticket, terminalId, {
        details: { firstName: "Contrat", lastName: "Test", phone: "+594694000000", address: "1 rue Test", city: "Cayenne", postalCode: "97300", country: "GF" } as never,
      }));
      paymentId = (v as { paymentId?: string }).paymentId ?? "";
      expect(paymentId).not.toBe("");
    });

    test("payment", async () => {
      const v = ok(await core.payment(ticket, paymentId));
      expect(v).toHaveProperty("status");
      // Then the player's part at the provider, so the money arrives for a real launch below.
      const address = (v as { redirectUrl?: string; address?: string }).redirectUrl ?? (v as { address?: string }).address;
      expect(address, "the payment names where the player goes").toBeTruthy();
      await payAsBrowser(address!);
      const deadline = Date.now() + 30_000;
      for (;;) {
        const b = ok(await core.balance("EUR"));
        if (b.real.minor === 5000n) break;
        if (Date.now() > deadline) throw new Error(`the deposit was not credited: real ${b.real.minor}`);
        await new Promise((r) => setTimeout(r, 500));
      }
    }, 45_000);

    test("abandonCashier", async () => {
      // A second payment, abandoned while it waits at the provider: the only step the deposit sends it
      // from (deposit.ts). Before a submit there is no payment, and the cashier answers PAYMENT_NOT_FOUND.
      const s = ok(await core.startDeposit(EUR("1000"), `contract-abandon-${Date.now()}`));
      ok(await core.submitCashier(s.cashierTicket, s.terminals[0]!.id));
      ok(await core.abandonCashier(s.cashierTicket));
    });
  });

  describe("casino", () => {
    test("games", async () => {
      const v = ok(await casino.games());
      expect(v.games.length).toBeGreaterThan(0);
      gameId = v.games[0]!.gameId;
    });

    test("game", async () => {
      expect(ok(await casino.game(gameId)).gameId).toBe(gameId);
    });

    test("freeRounds", async () => {
      expect(Array.isArray(ok(await casino.freeRounds("EUR")))).toBe(true);
    });

    test("launch", async () => {
      const v = ok(await casino.launch(gameId, { currency: "EUR", funMode: true }));
      expect(v.address).toMatch(/^https?:\/\//);
      gameSession = v.gameSession ?? "";
    });
  });

  describe("core, a game session", () => {
    // A real-money session, which responsible gambling follows; the deposit above funded it.
    beforeAll(async () => {
      gameSession = ok(await casino.launch(gameId, { currency: "EUR" })).gameSession;
    });

    test("gameSession", async () => {
      expect(ok(await core.gameSession(gameSession))).toHaveProperty("status");
    });

    test("acknowledgeRealityCheck", async () => {
      // Responsible gambling learns of the session from casino.sessions, after the launch; a reality
      // check comes minutes into play, so only a test asks this soon. Wait for the fact, not longer.
      const deadline = Date.now() + 20_000;
      for (;;) {
        const r = await core.acknowledgeRealityCheck(gameSession);
        if (r.ok) break;
        if (r.refusal.code !== "session_not_found" || Date.now() > deadline) throw new Error(`refused: ${JSON.stringify(r.refusal)}`);
        await new Promise((res) => setTimeout(res, 500));
      }
    }, 30_000);

    test("endGameSession", async () => {
      ok(await core.endGameSession(gameSession));
    });
  });

  describe("engagement", () => {
    test("push: each event as it is written, through the site's proxy (research V2)", async () => {
      const push = ok(await engagement.push());
      const events = push.events[Symbol.asyncIterator]();
      const next = (ms: number) =>
        Promise.race([events.next(), new Promise<never>((_, no) => setTimeout(() => no(new Error(`no event within ${ms} ms`)), ms))]);
      try {
        const first = await next(5_000);
        expect(first.done).toBe(false);
        expect(first.value?.event).toBe("ready");
        // A message while the stream is open: players' fact becomes engagement's signal frame, read
        // here before the stream ends, so nothing on the way held it back.
        const asked = Date.now();
        const sent = await fetch(`${players}/v1/casino/backoffice/${brand}/players/${playerId}/messages`, {
          method: "POST",
          headers: { authorization: `Bearer ${staffToken()}`, "content-type": "application/json" },
          body: JSON.stringify({ category: "service", subject: "Contrat", body: "Un message pendant le flux" }),
        });
        expect(sent.status, "the operator's message").toBeLessThan(300);
        for (;;) {
          const e = await next(20_000);
          if (e.done) throw new Error("the stream ended before a signal");
          if (e.value.event === "signal") break;
        }
        console.log(`V2: a signal ${Date.now() - asked} ms after the message was sent`);
      } finally {
        push.close();
      }
    }, 45_000);
  });

  describe("support", () => {
    // No support agent runs on one machine (local/generate.py): each answer is refused by a code.
    test("turn", async () => {
      const r = await support.turn(`contract-${Date.now()}`, "Bonjour");
      if (!r.ok) expect(typeof r.refusal.code).toBe("string");
    });

    test("decide", async () => {
      const r = await support.decide(`contract-${Date.now()}`, false);
      if (!r.ok) expect(typeof r.refusal.code).toBe("string");
    });
  });
});

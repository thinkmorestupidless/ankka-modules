import { describe, expect, it } from "vitest";
import { playerSite } from "../../src/site.ts";
import { handClock } from "../../src/testing/clock.ts";
import { doubles, doubleTransport } from "../../src/testing/doubles.ts";

const ana = { email: "ana@example.com", password: "correct horse battery 1!" };

async function setup(options: { signedIn?: boolean } = {}) {
  const clock = handClock();
  const d = doubles({ clock });
  const transport = doubleTransport(d);
  if (options.signedIn) await transport.session.signIn(ana);
  const site = playerSite({ transport, clock });
  await site.ready;
  const s = site.session;
  return { clock, d, transport, site, s };
}

describe("the session behaviour", () => {
  it("signs in: signing_in, then signed_in with the player; the site follows", async () => {
    const { clock, site, s } = await setup();
    const seen: string[] = [];
    s.subscribe((step) => seen.push(step.kind));
    expect(s.actions.signIn(ana)).toEqual({ ok: true });
    expect(s.step().kind).toBe("signing_in");
    expect(s.actions.signIn(ana)).toEqual({ ok: false, notAllowedAt: "signing_in" });
    await clock.settle();
    expect(s.step()).toMatchObject({ kind: "signed_in", player: { playerId: "p-ana", currency: "EUR", country: "GF" } });
    expect(site.player?.playerId).toBe("p-ana");
    expect(seen).toEqual(["signing_in", "signed_in"]);
  });

  it("a refused sign-in stands at refused with the code, and back returns", async () => {
    const { clock, d, s } = await setup();
    d.script("signIn", { status: 403, body: { error: "account_restricted", message: "the account is restricted", reason: "closed" } });
    s.actions.signIn(ana);
    await clock.settle();
    expect(s.step()).toMatchObject({ kind: "refused", during: "signing_in", refusal: { code: "account_restricted", fields: { reason: "closed" } } });
    expect(s.step().kind === "refused" && s.step()).not.toHaveProperty("refusal.words");
    s.actions.back();
    expect(s.step().kind).toBe("signed_out");
  });

  it("registers to awaiting verification, never signed in, and resends from there", async () => {
    const { clock, d, s } = await setup();
    s.actions.register({ email: "new@example.com", password: "correct horse battery 1!", currency: "EUR" });
    expect(s.step().kind).toBe("registering");
    await clock.settle();
    expect(s.step()).toMatchObject({ kind: "awaiting_verification", email: "new@example.com" });
    expect(d.brand.signedIn).toBeNull();
    s.actions.resendVerification();
    await clock.settle();
    expect(s.step()).toMatchObject({ kind: "awaiting_verification", resent: true });
    expect(d.askedOf("resendVerification")[0]?.body).toEqual({ email: "new@example.com" });
  });

  it("a resend refused as rate limited carries the wait, and back returns to awaiting verification", async () => {
    const { clock, d, s } = await setup();
    s.actions.register({ email: "new@example.com", password: "correct horse battery 1!", currency: "EUR" });
    await clock.settle();
    d.script("resendVerification", { status: 429, body: { error: "rate_limited", retry_after_seconds: 60 } });
    s.actions.resendVerification();
    await clock.settle();
    expect(s.step()).toMatchObject({ kind: "refused", during: "resending", refusal: { code: "rate_limited", fields: { retryAfterSeconds: 60 } } });
    s.actions.back();
    expect(s.step()).toMatchObject({ kind: "awaiting_verification", email: "new@example.com" });
  });

  it("verifies to signed out with verified, or refused", async () => {
    const { clock, s } = await setup();
    s.actions.verifyEmail("verify-p-ana");
    expect(s.step().kind).toBe("verifying");
    await clock.settle();
    expect(s.step()).toMatchObject({ kind: "signed_out", verified: true });
    s.actions.verifyEmail("bad");
    await clock.settle();
    expect(s.step()).toMatchObject({ kind: "refused", during: "verifying", refusal: { code: "invalid_or_expired_token" } });
  });

  it("a password reset request says it was asked, whatever the brand answered", async () => {
    const { clock, d, s } = await setup();
    d.script("requestPasswordReset", { status: 503, body: { error: "service_unavailable" } });
    s.actions.requestPasswordReset("who@example.com");
    await clock.settle();
    expect(s.step()).toMatchObject({ kind: "signed_out", resetRequested: true });
  });

  it("hydrates from the session read, and signs out whatever the brand answered", async () => {
    const { clock, d, site, s } = await setup({ signedIn: true });
    expect(s.step()).toMatchObject({ kind: "signed_in", player: { playerId: "p-ana" } });
    d.script("signOut", { status: 503, body: { error: "service_unavailable" } });
    s.actions.signOut();
    expect(s.step().kind).toBe("signing_out");
    await clock.settle();
    expect(s.step()).toEqual({ kind: "signed_out", allowed: expect.any(Array), reason: "signed_out" });
    expect(site.player).toBeNull();
  });

  it("warns an idle player at thirty minutes less thirty seconds and signs them out thirty seconds later", async () => {
    const { clock, d, s } = await setup({ signedIn: true });
    await clock.advance(29 * 60_000 + 29_000);
    expect(s.step().kind).toBe("signed_in");
    await clock.advance(1000);
    expect(s.step()).toMatchObject({ kind: "idle_warning", signsOutAt: clock.now() + 30_000 });
    await clock.advance(30_000);
    expect(s.step()).toMatchObject({ kind: "signed_out", reason: "idle" });
    expect(d.askedOf("signOut")).toHaveLength(1);
  });

  it("activity starts the idle count again; at the warning only stay does", async () => {
    const { clock, s } = await setup({ signedIn: true });
    await clock.advance(20 * 60_000);
    s.activity();
    await clock.advance(20 * 60_000);
    expect(s.step().kind).toBe("signed_in");
    await clock.advance(9 * 60_000 + 40_000); // 49:40, ten seconds into the warning
    expect(s.step().kind).toBe("idle_warning");
    s.activity();
    expect(s.step().kind).toBe("idle_warning");
    s.actions.stay();
    await clock.settle();
    expect(s.step().kind).toBe("signed_in");
    await clock.advance(29 * 60_000);
    expect(s.step().kind).toBe("signed_in");
  });

  it("a recent bet refreshes the sign-in and starts the idle count again", async () => {
    const { clock, s } = await setup({ signedIn: true });
    await clock.advance(25 * 60_000);
    s.notIdle();
    await clock.advance(25 * 60_000);
    expect(s.step().kind).toBe("signed_in");
  });

  /** The transport's refreshes answered in turn, refused as expired past the list; each sign-out counted. */
  function refreshes(transport: ReturnType<typeof doubleTransport>, answers: boolean[]) {
    const seen = { refreshes: 0, signOuts: 0 };
    const signOut = transport.session.signOut.bind(transport.session);
    transport.session.refresh = async () => {
      const ok = answers[seen.refreshes++] ?? false;
      return ok ? { ok: true } : { ok: false, refusal: { status: 401, code: "refresh_expired", fields: {} } };
    };
    transport.session.signOut = async () => {
      seen.signOuts++;
      await signOut();
    };
    return seen;
  }

  async function atWarning() {
    const x = await setup({ signedIn: true });
    await x.clock.advance(29 * 60_000 + 40_000); // ten seconds into the warning
    expect(x.s.step().kind).toBe("idle_warning");
    return x;
  }

  it("stay, after a refresh another tab won: the second refresh holds the winner's sign-in, and the player stays (review)", async () => {
    const { clock, transport, s } = await atWarning();
    const seen = refreshes(transport, [false, true]);
    s.actions.stay();
    await clock.settle();
    expect(s.step().kind).toBe("signed_in");
    expect(seen).toEqual({ refreshes: 2, signOuts: 0 });
  });

  it("stay, with the sign-in gone: refused twice, signs out, which removes the cookies, at refresh_expired", async () => {
    const { clock, transport, s } = await atWarning();
    const seen = refreshes(transport, []);
    s.actions.stay();
    await clock.settle();
    expect(s.step()).toMatchObject({ kind: "signed_out", reason: "refresh_expired" });
    expect(seen).toEqual({ refreshes: 2, signOuts: 1 });
  });

  it("a recent bet whose refresh is refused twice signs out; one refused once stays signed in", async () => {
    const won = await setup({ signedIn: true });
    const a = refreshes(won.transport, [false, true]);
    won.s.notIdle();
    await won.clock.settle();
    expect(won.s.step().kind).toBe("signed_in");
    expect(a.signOuts).toBe(0);
    const gone = await setup({ signedIn: true });
    const b = refreshes(gone.transport, []);
    gone.s.notIdle();
    await gone.clock.settle();
    expect(gone.s.step()).toMatchObject({ kind: "signed_out", reason: "refresh_expired" });
    expect(b).toEqual({ refreshes: 2, signOuts: 1 });
  });

  it("a call that finds the sign-in gone signs the player out with the reason refresh_expired", async () => {
    const { clock, d, transport, s } = await setup({ signedIn: true });
    d.brand.signedIn = null;
    await transport.call({ module: "core", id: "balance", service: "wallet", method: "GET", path: "/v1/casino/players/{brand}/wallets/{currency}/balance", audience: "player" }, { path: { currency: "EUR" } });
    await clock.settle();
    expect(s.step()).toMatchObject({ kind: "signed_out", reason: "refresh_expired" });
  });

  it("stop ends the idle timers", async () => {
    const { clock, site, s } = await setup({ signedIn: true });
    site.stop();
    expect(clock.pending()).toBe(0);
    expect(s.actions.signOut()).toMatchObject({ ok: false });
  });

  it("the visitor's country: core's lookup, asked once; null when unknown or failed", async () => {
    const { d, s } = await setup();
    expect(await s.visitorCountry()).toBe("GF");
    d.script("country", { status: 200, body: { country: "BR", dialCode: "+55" } });
    expect(await s.visitorCountry()).toBe("GF");
    const unknown = await setup();
    unknown.d.script("country", { status: 200, body: { country: null, dialCode: null } });
    expect(await unknown.s.visitorCountry()).toBeNull();
    const failed = await setup();
    failed.d.script("country", { status: 503, body: { error: "unavailable" } });
    expect(await failed.s.visitorCountry()).toBeNull();
  });
});

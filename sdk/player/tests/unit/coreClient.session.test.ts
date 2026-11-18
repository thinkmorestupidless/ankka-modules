import { describe, expect, it } from "vitest";
import { coreClient } from "../../src/core/client.ts";
import { doubles, doubleTransport } from "../../src/testing/doubles.ts";

const setup = () => {
  const d = doubles();
  return { d, core: coreClient(doubleTransport(d)) };
};

describe("core's client: the session rows a page calls", () => {
  it("register answers the player id and status, sending the visitor's fields as players takes them", async () => {
    const { d, core } = setup();
    const r = await core.register({ email: "new@example.com", password: "correct horse battery 1!", currency: "EUR", firstName: "Zé" });
    expect(r).toEqual({ ok: true, value: { playerId: expect.stringMatching(/^p-/), status: "pending_verification" } });
    expect(d.askedOf("register")[0]?.body).toEqual({ email: "new@example.com", password: "correct horse battery 1!", currency: "EUR", firstName: "Zé" });
  });

  it("a refused registration is one refusal with its fields", async () => {
    const { core } = setup();
    expect(await core.register({ email: "", password: "", currency: "EUR" })).toEqual({ ok: false, refusal: { status: 400, code: "missing_fields", fields: { missing: ["email", "password"] } } });
    expect(await core.register({ email: "ana@example.com", password: "x", currency: "EUR" })).toMatchObject({ ok: false, refusal: { status: 409, code: "email_already_exists" } });
  });

  it("verifyEmail, resendVerification and requestPasswordReset", async () => {
    const { d, core } = setup();
    expect(await core.verifyEmail("verify-p-ana")).toEqual({ ok: true, value: { status: "verified" } });
    expect(await core.verifyEmail("nonsense")).toMatchObject({ ok: false, refusal: { code: "invalid_or_expired_token" } });
    d.script("resendVerification", { status: 429, body: { error: "rate_limited", retry_after_seconds: 60 } });
    expect(await core.resendVerification("ana@example.com")).toEqual({ ok: false, refusal: { status: 429, code: "rate_limited", fields: { retryAfterSeconds: 60 } } });
    expect((await core.requestPasswordReset("ana@example.com")).ok).toBe(true);
    expect(d.askedOf("requestPasswordReset")[0]?.body).toEqual({ email: "ana@example.com" });
  });

  it("an answer that does not fit its shape is malformed, naming the service", async () => {
    const { d, core } = setup();
    d.script("register", { status: 201, body: { player: 1 } });
    expect(await core.register({ email: "x@example.com", password: "p", currency: "EUR" })).toEqual({ ok: false, refusal: { status: 201, code: "malformed_answer", fields: {}, service: "players" } });
  });
});

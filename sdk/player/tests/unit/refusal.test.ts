import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { codesByBehaviour, isRefusalCode, refusalCodes } from "../../src/codes.ts";
import { readRefusal } from "../../src/refusal.ts";

const recorded = (path: string, match: (body: Record<string, unknown>) => boolean): Record<string, unknown> => {
  const found: Record<string, unknown>[] = [];
  const walk = (x: unknown): void => {
    if (Array.isArray(x)) x.forEach(walk);
    else if (x && typeof x === "object") {
      const o = x as Record<string, unknown>;
      if (typeof o.error === "string" && match(o)) found.push(o);
      Object.values(o).forEach(walk);
    }
  };
  walk(JSON.parse(readFileSync(new URL(`../../../../tests/port/${path}`, import.meta.url), "utf8")));
  if (!found[0]) throw new Error(`no refusal recorded in ${path}`);
  return found[0];
};

describe("one refusal form (FR-039, FR-040)", () => {
  it("reads the platform's code and explanation as its code", () => {
    expect(readRefusal(403, { status: 403, error: "wrong_brand: this is brand guyanabet" })).toEqual({
      status: 403,
      code: "wrong_brand",
      fields: {},
    });
  });

  it("reads a handler's code with words beside it, keeping the named fields and not the words (kyc, recorded)", () => {
    const body = recorded("kyc/TestScenario29_KYCGates_DepositGateRefusesUnverifiedPendingRejected.json", (b) => b.error === "DEPOSIT_BLOCKED_KYC");
    expect(readRefusal(403, { ...body, service: "kyc" })).toEqual({
      status: 403,
      code: "DEPOSIT_BLOCKED_KYC",
      fields: { kycStatus: "unverified" },
      service: "kyc",
    });
  });

  it("reads a handler's code alone with its named fields (players, recorded)", () => {
    const body = recorded("players/a sign-in by a restricted player is refused naming the status and the attempt is kept [blocked].json", () => true);
    expect(readRefusal(403, body)).toEqual({ status: 403, code: "account_restricted", fields: { reason: "blocked" } });
  });

  it("reads a game provider refusal with its break's end under one name (recorded)", () => {
    const body = recorded("engagement/TestScenario27_RGPlay_LaunchOnBreakRefused.json", (b) => b.error === "cooling_off_active");
    const r = readRefusal(403, body);
    expect(r.code).toBe("cooling_off_active");
    expect(r.fields).toEqual({ until: "2026-11-15T07:52:55Z" });
  });

  it("gives a break's end and a wait one name whatever the service called them", () => {
    expect(readRefusal(403, { error: "cooling_off", until: "2027-01-01T00:00:00Z" }).fields).toEqual({ until: "2027-01-01T00:00:00Z" });
    expect(readRefusal(403, { error: "cooling_off", endsAt: "2027-01-01T00:00:00Z", service: "responsible-gambling" }).fields).toEqual({ until: "2027-01-01T00:00:00Z" });
    expect(readRefusal(429, { error: "rate_limited", retry_after_seconds: 60 }).fields).toEqual({ retryAfterSeconds: 60 });
    expect(readRefusal(429, { error: "too_many_turns", retryAfter: 30 }).fields).toEqual({ retryAfterSeconds: 30 });
  });

  it("gives a deposit limit's figures as minor units", () => {
    const r = readRefusal(403, { error: "over_limit", message: "Deposit limit reached", service: "responsible-gambling", period: "daily", limit: 5000, used: 4000, remaining: 1000 });
    expect(r).toEqual({ status: 403, code: "over_limit", fields: { period: "daily", limit: 5000n, used: 4000n, remaining: 1000n }, service: "responsible-gambling" });
  });

  it("reads a code in details, and the support agent's request awaiting approval", () => {
    const r = readRefusal(409, { error: "Session already active", details: { approvalId: "ap-1", action_name: "set_deposit_limit" } });
    expect(r).toEqual({ status: 409, code: "Session already active", fields: { approvalId: "ap-1", action: "set_deposit_limit" } });
  });

  it("passes a service's words on only where asked", () => {
    const body = { error: "agent_unavailable", message: "Le support est fermé." };
    expect(readRefusal(503, body).words).toBeUndefined();
    expect(readRefusal(503, body, { words: true }).words).toBe("Le support est fermé.");
  });

  it("a code it does not know is unknown with the service's status and none of its text", () => {
    expect(readRefusal(418, { error: "teapot_overheated", message: "the pot is hot", temperature: 99 }, { words: true })).toEqual({
      status: 418,
      code: "unknown",
      fields: {},
    });
  });

  it("a body that is no refusal is unknown", () => {
    expect(readRefusal(500, "<html>oops</html>").code).toBe("unknown");
    expect(readRefusal(502, null).code).toBe("unknown");
    expect(readRefusal(500, [1, 2]).code).toBe("unknown");
  });

  it("every code of every behaviour is a known code, and every known code belongs to a behaviour", () => {
    const named = new Set(Object.values(codesByBehaviour).flat());
    for (const code of named) expect(isRefusalCode(code), code).toBe(true);
    for (const code of refusalCodes) expect(named.has(code), code).toBe(true);
  });
});

import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { afterEach, describe, expect, it } from "vitest";
import { wholeSite } from "./helpers.ts";

let close: (() => Promise<void>) | undefined;
afterEach(async () => {
  await close?.();
  close = undefined;
});

describe("the sign-in is never shown (SC-004, FR-003)", () => {
  it("what players issued appears in no body, no header but Set-Cookie, no log line, and no request but Authorization to the service called", async () => {
    const s = await wholeSite();
    close = s.close;
    const answers: Response[] = [];
    const keep = async (r: Promise<Response>) => {
      const response = await r;
      answers.push(response.clone());
      return response;
    };
    await keep(s.signIn());
    await keep(s.request("POST", "/api/session/refresh"));
    await keep(s.request("GET", "/api/c/core/balance?currency=EUR"));
    await keep(s.request("GET", "/api/session"));

    // every value players issued: from the doubles' own answers
    const issued = new Set<string>();
    for (const a of s.doubles.asked) {
      if (a.id === "refresh") issued.add((a.body as { refreshToken: string }).refreshToken);
      const auth = a.headers.authorization;
      if (auth) issued.add(auth.replace(/^Bearer /, ""));
    }
    for (const name of ["__Host-eo_session", "__Secure-eo_refresh"]) {
      const v = s.browser.get(name);
      if (v) issued.add(v);
    }
    expect(issued.size).toBeGreaterThanOrEqual(3);

    for (const r of answers) {
      const body = await r.text();
      const headers = [...r.headers].filter(([k]) => k !== "set-cookie").map(([k, v]) => `${k}: ${v}`).join("\n");
      for (const v of issued) {
        expect(body).not.toContain(v);
        expect(headers).not.toContain(v);
      }
    }
    const logs = JSON.stringify(s.logs);
    for (const v of issued) expect(logs).not.toContain(v);

    for (const o of s.outbound) {
      const withoutAuth = JSON.stringify({ url: o.url, headers: { ...o.headers, authorization: undefined }, body: o.url.endsWith("/refresh") ? undefined : o.body });
      for (const v of issued) expect(withoutAuth, o.url).not.toContain(v);
      if (o.headers.authorization) expect(o.url).toMatch(/\/(wallet|players)\//);
    }
    // the refresh token goes only to players' refresh route, in its body
    const refreshes = s.outbound.filter((o) => o.body && [...issued].some((v) => o.body!.includes(v)));
    expect(refreshes.every((o) => o.url.endsWith("/players/v1/casino/players/guyanabet/refresh"))).toBe(true);
  });

  it("the server opens no span of its own", () => {
    const dir = join(import.meta.dirname, "../../src/server");
    for (const f of readdirSync(dir)) {
      expect(readFileSync(join(dir, f), "utf8"), f).not.toMatch(/opentelemetry|startSpan|tracer/i);
    }
  });
});

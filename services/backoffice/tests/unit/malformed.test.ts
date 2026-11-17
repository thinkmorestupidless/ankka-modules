// The server answers a malformed path or an owner's unreadable success as such, never as a 500: a
// player id with a broken escape is 400, a built file's is 404, and a 2xx that is not the JSON it
// promised is 502 `unreadable`.
import { mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { describe, expect, test } from "vitest";
import { serveBuilt } from "../../src/server/main.ts";
import { withdrawals } from "../../src/server/composite/withdrawals.ts";
import { table } from "../../src/server/routes.ts";
import { harness, write } from "./support.ts";

describe("a malformed escape", () => {
  test.each(["standing", "timeline"])("in the player's id of /api/players/{p}/%s is 400", async (what) => {
    const h = harness({ table });
    const r = await h.ask("GET", `/api/players/p%zz/${what}`, { session: "s-1" });
    expect(r.status).toBe(400);
    expect(await r.json()).toMatchObject({ error: "invalid_player" });
    expect(h.calls).toHaveLength(0);
  });

  test("in a built file's path is 404", async () => {
    const dist = mkdtempSync(path.join(tmpdir(), "bo-dist-"));
    writeFileSync(path.join(dist, "index.html"), "<!doctype html>");
    expect((await serveBuilt(dist, "/assets/%zz.js")).status).toBe(404);
  });
});

describe("an owner's success that is not JSON", () => {
  test("is 502 unreadable for the timeline's export", async () => {
    const h = harness({ table });
    h.answer(() => new Response("<html>ok</html>", { headers: { "Content-Type": "text/html" } }));
    const r = await h.ask("POST", "/api/players/p-1/timeline/export", { session: "s-1", headers: write, body: "{}" });
    expect(r.status).toBe(502);
    expect(await r.json()).toEqual({ error: "unreadable" });
  });

  test.each([
    ["players", "/players/v1/casino/backoffice/goldvibe/players"],
    ["payouts", "/payouts/v1/casino/brands/goldvibe/withdrawal-requests"],
  ])("is 502 unreadable naming %s for the withdrawals' search", async (source, broken) => {
    const fetchFn = (async (url: string) =>
      new URL(url).pathname === broken
        ? new Response("not json", { status: 200 })
        : Response.json(new URL(url).pathname.includes("/players") ? { items: [{ playerId: "p-1" }], total: 1 } : { requests: [], total: 0 })) as typeof fetch;
    const deps = { brand: "goldvibe", servicesUrl: "http://services.local", sharedProjects: {}, fetch: fetchFn, timeoutMs: 100, table };
    const r = await withdrawals(deps, new URLSearchParams({ q: "ana@example.test" }), "tok");
    expect(r.status).toBe(502);
    expect(await r.json()).toEqual({ error: "unreadable", source });
  });
});

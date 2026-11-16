import { describe, expect, test } from "vitest";
import { match, PaymentsRoles, table } from "../../src/server/routes.ts";
import permissions from "../../src/shared/generated/operator-permissions.v1.json" with { type: "json" };
import { harness, rows } from "./support.ts";
import { ProgrammePermissions } from "../../src/shared/access.ts";

describe("matching a row", () => {
  test("by method, service and path template, with the parameters", () => {
    expect(match(rows, "GET", "players", "/players/p-1/profile")).toEqual({ row: rows[0], params: { p: "p-1" } });
  });

  test("another method is no match", () => {
    expect(match(rows, "DELETE", "players", "/players/p-1/profile")).toBeUndefined();
  });

  test("another service is no match", () => {
    expect(match(rows, "GET", "wallet", "/players/p-1/profile")).toBeUndefined();
  });

  test.each(["/players/p-1/profile/extra", "/players/profile", "/players//profile", "/players/../profile", "/players/a%2Fb/profile"])(
    "%s is no match",
    (rest) => expect(match(rows, "GET", "players", rest)).toBeUndefined(),
  );
});

describe("the table", () => {
  test("no browser path names the brand: the server puts it in", () => {
    for (const row of table) expect(row.path, `${row.method} ${row.service} ${row.path}`).not.toContain("{brand}");
  });

  test("every row's permission is one the contract names, or the affiliate programme's", () => {
    const known = new Set([...permissions.permissions, ...Object.keys(ProgrammePermissions)]);
    const byRole = (row: (typeof table)[number]) => row.where === "payments" && PaymentsRoles.includes(row.permission);
    for (const row of table.filter((r) => !byRole(r))) expect(known.has(row.permission), `${row.method} ${row.service} ${row.path}: ${row.permission}`).toBe(true);
  });

  test("a parameter of the browser's path is one of the owner's", () => {
    const names = (t: string) => [...t.matchAll(/\{(\w+)\}/g)].map((m) => m[1]).filter((n) => n !== "brand").sort();
    for (const row of table) expect(names(row.path), `${row.method} ${row.service} ${row.path}`).toEqual(names(row.upstream));
  });

  test("no two rows answer the same method and path", () => {
    const seen = new Set<string>();
    for (const row of table) {
      const key = `${row.method} ${row.service} ${row.path.replace(/\{\w+\}/g, "{}")}`;
      expect(seen.has(key), key).toBe(false);
      seen.add(key);
    }
  });
});

describe("a path the table does not hold", () => {
  test("is 404 no_such_route, and no owner is asked", async () => {
    const h = harness();
    const r = await h.ask("GET", "/api/s/players/players/p-1/secrets", { session: "s-1" });
    expect(r.status).toBe(404);
    expect(await r.json()).toEqual({ error: "no_such_route" });
    expect(h.calls).toHaveLength(0);
  });

  test("naming a service the table does not hold is the same", async () => {
    const h = harness();
    const r = await h.ask("GET", "/api/s/vault/anything", { session: "s-1" });
    expect(r.status).toBe(404);
    expect(h.calls).toHaveLength(0);
  });
});

// The page's mirror of the contract's role tables (shared/access.ts): the payments privilege table
// (`Routes.PaymentsRoles`) and the programme's realm roles (`Routes.AffiliateManagerRoles`, the
// programme's `Access.ManagerAdminRoles`). The contract publishes neither in its generated JSON, so this
// reads them from their Scala source and holds the mirror equal to it.
import { readFileSync } from "node:fs";
import { describe, expect, test } from "vitest";
import { PaymentsLevels, ProgrammePermissions } from "../../src/shared/access.ts";
import { PaymentsRoles } from "../../src/server/routes.ts";

const source = (path: string) => readFileSync(new URL(`../../../../${path}`, import.meta.url), "utf8");
const block = (text: string, name: string) => {
  const at = text.indexOf(`val ${name}`);
  if (at < 0) throw new Error(`${name} is not in the source`);
  return text.slice(at, text.indexOf(")", at) + 1);
};
const names = (text: string) => [...text.matchAll(/"([a-z_]+)"/g)].map((m) => m[1]!).sort();

const routes = source("contract/src/main/scala/io/eitheror/casino/contract/access/Routes.scala");

describe("the page's role tables against the contract's", () => {
  test("the payments privilege table, role for role and level for level", () => {
    const levels = Object.fromEntries([...block(routes, "PaymentsRoles").matchAll(/"([a-z_]+)"\s*->\s*(\d+)/g)].map((m) => [m[1]!, Number(m[2])]));
    expect(Object.keys(levels).length).toBeGreaterThan(0);
    expect(PaymentsLevels).toEqual(levels);
    expect([...PaymentsRoles].sort()).toEqual(Object.keys(levels).sort());
  });

  test("the programme's manager roles, and the roles that add and remove managers", () => {
    // spec 032: the contract's are held named for a brand; platform_admin, held bare, is decided beside them
    const branded = (roles: readonly string[]) => roles.filter((r) => r !== "platform_admin").sort();
    expect(branded(ProgrammePermissions["programme.manage"]!)).toEqual(names(block(routes, "AffiliateManagerRoles")));
    const access = source("services/affiliate-programme/src/main/scala/io/eitheror/casino/programme/api/Access.scala");
    expect([...ProgrammePermissions["programme.managers"]!].sort()).toEqual(names(block(access, "ManagerAdminRoles")));
  });
});

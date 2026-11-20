// Spec 032: the server's twin of the contract's brand roles, run over the contract's own table
// (contract/src/test/resources/brand-roles.cases.json), so the two readers cannot drift.
import { readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, test } from "vitest";
import { heldIn, programmePermissionsOf } from "../../src/shared/access.ts";
import { AllPermissions, permissionsOf, shownRoles } from "../../src/server/roles.ts";

interface Case {
  name: string;
  slug: string;
  roles: string[];
  held: string[];
  permissions: string[] | "all";
}

const here = path.dirname(fileURLToPath(import.meta.url));
const table = JSON.parse(
  readFileSync(path.resolve(here, "../../../../contract/src/test/resources/brand-roles.cases.json"), "utf8"),
) as { bags: Record<string, string[]>; cases: Case[] };

describe("the contract's brand-role cases", () => {
  for (const c of table.cases) {
    test(c.name, () => {
      expect(heldIn(c.slug, c.roles)).toEqual([...c.held].sort());
      // the bags' permissions only: the table gives no programme or payments role
      const opened = permissionsOf(c.slug, c.roles, table.bags);
      const expected = c.permissions === "all" ? AllPermissions : AllPermissions.filter((p) => c.permissions.includes(p));
      expect(opened.filter((p) => AllPermissions.includes(p))).toEqual(expected);
    });
  }
});

describe("the programme's permissions", () => {
  test("are opened only by its roles named for this brand", () => {
    expect(programmePermissionsOf("goldvibe", ["brand_goldvibe", "affiliate_manager@goldvibe"])).toEqual(["programme.manage"]);
    expect(programmePermissionsOf("goldvibe", ["brand_goldvibe", "affiliate_manager@spinvibe", "affiliate_manager"])).toEqual([]);
    expect(programmePermissionsOf("goldvibe", ["platform_admin"])).toEqual(["programme.manage", "programme.managers"]);
  });
});

describe("the roles /api/me shows", () => {
  test("are this brand's, without the brand, and the installation's own", () => {
    const roles = ["brand_goldvibe", "brand_spinvibe", "brand_finance@goldvibe", "brand_admin@spinvibe", "brand_support", "resource_viewer"];
    expect(shownRoles("goldvibe", roles)).toEqual(["brand_finance", "resource_viewer"]);
    expect(shownRoles("spinvibe", roles)).toEqual(["brand_admin", "resource_viewer"]);
  });
});

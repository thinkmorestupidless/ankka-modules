// What the page knows about the operator's access: whether they hold a permission in this brand (from
// `/api/me`), and, for an action they lack, which of the brand's roles would give it (from
// `/api/permissions`). No bag is written here: the brand keeps them, and they change at run time.

export interface Me {
  subject: string | null;
  name: string | null;
  roles: string[];
  brand: { slug: string; name: string; modules: string[] | null };
  preferences: unknown;
  permissions: string[];
}

/** The brand's roles and what each carries, as `/api/permissions` answers them. */
export type RoleBags = Record<string, string[]>;

export function holds(me: Pick<Me, "permissions">, permission: string): boolean {
  return me.permissions.includes(permission);
}

/** Whether the brand runs `module`; `null` modules (never read) shows the area rather than hide it wrongly. */
export function runs(me: Pick<Me, "brand">, module: string): boolean {
  return me.brand.modules === null || me.brand.modules.includes(module);
}

const Defaults = ["brand_support", "brand_finance", "brand_compliance", "brand_admin"];

/**
 * What the affiliate programme admits by realm role rather than by the brand's bags (contracts/roles.md,
 * *Not in this matrix*; contract `Routes.AffiliateManagerRoles`, the programme's `Access.ManagerAdminRoles`):
 * `programme.manage` its manager routes, `programme.managers` the routes that add and remove managers. The
 * server opens them from the operator's roles beside the bags' permissions; the programme still decides.
 */
export const ProgrammePermissions: Readonly<Record<string, readonly string[]>> = {
  "programme.manage": ["affiliate_manager", "brand_admin", "platform_admin"],
  "programme.managers": ["brand_admin", "platform_admin"],
};

/** The programme's permissions `roles` give. */
export function programmePermissionsOf(roles: readonly string[]): string[] {
  return Object.keys(ProgrammePermissions).filter((p) => ProgrammePermissions[p]!.some((r) => roles.includes(r)));
}

/**
 * The roles whose bag holds `permission`, the default roles first in the matrix's order, then the
 * brand's own by name: what a disabled action names as the role it needs (FR-005). A programme
 * permission names the realm roles that give it.
 */
export function rolesGiving(permission: string, bags: RoleBags): string[] {
  const realm = ProgrammePermissions[permission];
  if (realm) return [...realm];
  const giving = Object.keys(bags).filter((r) => bags[r]!.includes(permission));
  const rank = (r: string) => (Defaults.includes(r) ? Defaults.indexOf(r) : Defaults.length);
  return giving.sort((a, b) => rank(a) - rank(b) || a.localeCompare(b));
}

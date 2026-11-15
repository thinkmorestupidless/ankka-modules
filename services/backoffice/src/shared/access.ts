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
 * The roles whose bag holds `permission`, the default roles first in the matrix's order, then the
 * brand's own by name: what a disabled action names as the role it needs (FR-005).
 */
export function rolesGiving(permission: string, bags: RoleBags): string[] {
  const giving = Object.keys(bags).filter((r) => bags[r]!.includes(permission));
  const rank = (r: string) => (Defaults.includes(r) ? Defaults.indexOf(r) : Defaults.length);
  return giving.sort((a, b) => rank(a) - rank(b) || a.localeCompare(b));
}

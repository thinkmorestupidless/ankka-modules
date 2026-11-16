// What the page knows about the operator's access: whether they hold a permission in this brand (from
// `/api/me`), and, for an action they lack, which of the brand's roles would give it (from
// `/api/permissions`). No bag is written here: the brand keeps them, and they change at run time.

export interface Me {
  subject: string | null;
  name: string | null;
  roles: string[];
  /** `zone`: the brand's reporting zone (players' brand configuration), in which every date is shown (FR-015). */
  brand: { slug: string; name: string; modules: string[] | null; zone: string };
  preferences: unknown;
  permissions: string[];
}

/** The brand's roles and what each carries, as `/api/permissions` answers them. */
export type RoleBags = Record<string, string[]>;

export function holds(me: Pick<Me, "permissions">, permission: string): boolean {
  return me.permissions.includes(permission);
}

/** Whether the brand runs `module`; `null` modules (never read) shows the area rather than hide it wrongly. */
export function runs(me: { brand: Pick<Me["brand"], "modules"> }, module: string): boolean {
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
 * The shared payments' privilege table (contract `Routes.PaymentsRoles`): a role and its level. merchant
 * admits any of them at a level (`Routes.paymentsOperator`) and compares them only on an invitation, which
 * gives a role only below the inviter's highest (`Privilege.canAssignRole`).
 */
export const PaymentsLevels: Readonly<Record<string, number>> = {
  resource_viewer: 10,
  resource_developer: 20,
  resource_finance: 25,
  resource_operator: 30,
  support_agent: 30,
  resource_admin: 40,
  resource_owner: 50,
  fraud_analyst: 60,
  compliance_officer: 60,
  platform_admin: 100,
};

/**
 * What the payments pages offer, by the least level that gives it (no bag gives a payments role): reading
 * any of it (every payments role), the operator's work on payments and the team (`resource_operator`, the
 * original's operator), and administering the hierarchy, providers, terminals and routing (`resource_admin`,
 * as the original's payments console admitted to those pages). merchant still decides every call.
 */
export const PaymentsPermissions: Readonly<Record<string, number>> = {
  "payments.read": 10,
  "payments.operate": 30,
  "payments.admin": 40,
};

/** The operator's level in the payments: the highest of their payments roles, 0 with none. */
export function paymentsLevel(roles: readonly string[]): number {
  return Math.max(0, ...roles.map((r) => PaymentsLevels[r] ?? 0));
}

/** The payments permissions `roles` give. */
export function paymentsPermissionsOf(roles: readonly string[]): string[] {
  const level = paymentsLevel(roles);
  return Object.keys(PaymentsPermissions).filter((p) => level >= PaymentsPermissions[p]!);
}

/** The payments roles, lowest level first, then by name. */
export const PaymentsRoleNames: readonly string[] = Object.keys(PaymentsLevels).sort(
  (a, b) => PaymentsLevels[a]! - PaymentsLevels[b]! || a.localeCompare(b),
);

/** Whether `roles` strictly outrank `role`, so merchant lets their holder give it (`Privilege.canAssignRole`). */
export function mayGiveRole(roles: readonly string[], role: string): boolean {
  const level = PaymentsLevels[role];
  return level !== undefined && paymentsLevel(roles) > level;
}

/**
 * The roles whose bag holds `permission`, the default roles first in the matrix's order, then the
 * brand's own by name: what a disabled action names as the role it needs (FR-005). A programme
 * permission names the realm roles that give it, a payments permission the payments roles at its level.
 */
export function rolesGiving(permission: string, bags: RoleBags): string[] {
  const realm = ProgrammePermissions[permission];
  if (realm) return [...realm];
  const least = PaymentsPermissions[permission];
  if (least !== undefined) return PaymentsRoleNames.filter((r) => PaymentsLevels[r]! >= least);
  const giving = Object.keys(bags).filter((r) => bags[r]!.includes(permission));
  const rank = (r: string) => (Defaults.includes(r) ? Defaults.indexOf(r) : Defaults.length);
  return giving.sort((a, b) => rank(a) - rank(b) || a.localeCompare(b));
}

// The areas of the rail (contracts/routes.md, *The page's routes*): each with the module that runs its
// owners and the permission that opens it. An area whose module the brand does not run is absent
// (R17); one the operator holds no permission for is absent too, since every route in it would be
// refused. The owners still decide every call.
import type { MessageKey } from "../../shared/messages/index.ts";
import { holds, runs, type Me } from "../../shared/access.ts";

export type Icon =
  | "inbox" | "users" | "out" | "scale" | "gift" | "tag" | "id" | "shield" | "alert"
  | "chart" | "table" | "cog" | "card" | "team" | "link";

export interface Area {
  id: string;
  path: string;
  label: MessageKey;
  icon: Icon;
  module: string;
  /** Any of these opens the area. */
  permissions: string[];
}

export const Areas: readonly Area[] = [
  { id: "worklist", path: "/", label: "area.worklist", icon: "inbox", module: "core", permissions: ["player.read"] },
  { id: "players", path: "/players", label: "area.players", icon: "users", module: "core", permissions: ["player.read"] },
  { id: "withdrawals", path: "/withdrawals", label: "area.withdrawals", icon: "out", module: "core", permissions: ["withdrawal.read"] },
  { id: "adjustments", path: "/adjustments/batch", label: "area.adjustments", icon: "scale", module: "core", permissions: ["adjustment.make"] },
  { id: "rewards", path: "/rewards", label: "area.rewards", icon: "gift", module: "casino", permissions: ["reward.read"] },
  { id: "offers", path: "/offers", label: "area.offers", icon: "tag", module: "casino", permissions: ["offer.manage"] },
  { id: "identity", path: "/identity", label: "area.identity", icon: "id", module: "core", permissions: ["identity.review"] },
  { id: "protection", path: "/protection", label: "area.protection", icon: "shield", module: "core", permissions: ["protection.read"] },
  { id: "fraud", path: "/fraud", label: "area.fraud", icon: "alert", module: "core", permissions: ["fraud.review"] },
  { id: "dashboard", path: "/dashboard", label: "area.dashboard", icon: "chart", module: "reporting", permissions: ["dashboard.read"] },
  { id: "reports", path: "/reports", label: "area.reports", icon: "table", module: "reporting", permissions: ["report.read", "report.dayEnd", "report.compliance"] },
  { id: "configuration", path: "/configuration", label: "area.configuration", icon: "cog", module: "core", permissions: ["config.brand", "config.games", "config.crm", "config.agent", "config.attribution", "modules.read"] },
  { id: "payments", path: "/payments", label: "area.payments", icon: "card", module: "core", permissions: ["wallet.read"] },
  { id: "team", path: "/team", label: "area.team", icon: "team", module: "core", permissions: ["roles.read"] },
  { id: "affiliates", path: "/affiliates", label: "area.affiliates", icon: "link", module: "core", permissions: ["config.attribution", "fact.redeliver"] },
];

/** The areas this operator sees in this brand, in the rail's order. */
export function areasFor(me: Pick<Me, "brand" | "permissions">): Area[] {
  return Areas.filter((a) => runs(me, a.module) && a.permissions.some((p) => holds(me, p)));
}

/** The area a path is in: the longest area path that prefixes it. */
export function areaOf(pathname: string): Area | undefined {
  if (pathname === "/") return Areas[0];
  return Areas.filter((a) => a.path !== "/" && (pathname === a.path || pathname.startsWith(`${a.path}/`)))
    .sort((a, b) => b.path.length - a.path.length)[0];
}

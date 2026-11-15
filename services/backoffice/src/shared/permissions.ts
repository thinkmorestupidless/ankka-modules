// The permissions as an administrator chooses them (contracts/roles.md): grouped by the area whose
// owner requires them, each with its description from the catalogue. The names are the contract's
// (`generated/operator-permissions.v1.json`); a test holds this list equal to them.
import type { MessageKey } from "./messages/index.ts";
import defaultsFile from "./generated/operator-permissions.v1.json" with { type: "json" };

export interface PermissionGroup {
  label: MessageKey;
  permissions: readonly string[];
}

export const PermissionGroups: readonly PermissionGroup[] = [
  { label: "area.players", permissions: ["player.read", "note.write", "message.send", "presence.read", "standing.change", "profile.change", "session.end", "flag.clear", "tier.change", "erasure.request", "export.player"] },
  { label: "area.payments", permissions: ["wallet.read", "adjustment.make", "turnover.override"] },
  { label: "area.withdrawals", permissions: ["withdrawal.read", "withdrawal.review"] },
  { label: "area.rewards", permissions: ["reward.read", "reward.grant", "reward.revoke", "offer.manage"] },
  { label: "area.identity", permissions: ["identity.read", "identity.review"] },
  { label: "area.protection", permissions: ["protection.read", "protection.change"] },
  { label: "area.fraud", permissions: ["fraud.review"] },
  { label: "area.reports", permissions: ["dashboard.read", "report.read", "report.dayEnd", "report.compliance"] },
  { label: "area.configuration", permissions: ["config.brand", "config.games", "config.crm", "config.agent", "config.attribution", "modules.read", "modules.request"] },
  { label: "area.team", permissions: ["roles.read", "roles.change", "roles.give"] },
  { label: "permissions.operations", permissions: ["callback.resolve", "fact.redeliver", "send.release"] },
];

export const describePermission = (p: string): MessageKey => `permission.${p}` as MessageKey;

/** What a default role is reset to. */
export const DefaultBags: Readonly<Record<string, readonly string[]>> = (defaultsFile as { defaults: Record<string, string[]> }).defaults;

/** What a change to a role's bag gives and takes away. */
export function difference(before: readonly string[], after: readonly string[]): { gained: string[]; lost: string[] } {
  const b = new Set(before);
  const a = new Set(after);
  return { gained: after.filter((p) => !b.has(p)).sort(), lost: before.filter((p) => !a.has(p)).sort() };
}

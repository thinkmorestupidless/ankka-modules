// The worklist's kinds (contracts/sources.md): the permission that admits each, and the module that
// runs its source. The ids are what preferences keep (`[a-z][a-z0-9_]*`). Undelivered outcomes are
// admitted by a payments role at its level, not by a brand permission: `permission` is null and the
// payments project decides.
import type { MessageKey } from "./messages/index.ts";
import { holds, runs, type Me } from "./access.ts";

export interface Kind {
  id: string;
  label: MessageKey;
  permission: string | null;
  module: string;
}

export const Kinds: readonly Kind[] = [
  { id: "withdrawals_pending", label: "kind.withdrawals_pending", permission: "withdrawal.review", module: "core" },
  { id: "payouts_needing_review", label: "kind.payouts_needing_review", permission: "withdrawal.review", module: "core" },
  { id: "documents_awaiting", label: "kind.documents_awaiting", permission: "identity.review", module: "core" },
  { id: "players_flagged", label: "kind.players_flagged", permission: "identity.review", module: "core" },
  { id: "protection_cases", label: "kind.protection_cases", permission: "protection.change", module: "core" },
  { id: "fraud_cases", label: "kind.fraud_cases", permission: "fraud.review", module: "core" },
  { id: "unacknowledged_calls", label: "kind.unacknowledged_calls", permission: "callback.resolve", module: "casino" },
  { id: "undelivered_facts", label: "kind.undelivered_facts", permission: "fact.redeliver", module: "core" },
  { id: "parked_sends", label: "kind.parked_sends", permission: "send.release", module: "engagement" },
  { id: "undelivered_outcomes", label: "kind.undelivered_outcomes", permission: null, module: "core" },
];

/** The kinds this operator's roles admit in this brand. */
export function kindsFor(me: { brand: Pick<Me["brand"], "modules"> } & Pick<Me, "permissions">): Kind[] {
  return Kinds.filter((k) => runs(me, k.module) && (k.permission === null || holds(me, k.permission)));
}

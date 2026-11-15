// One description per operator action (FR-025, FR-026): what it is called, the effect stated before it
// is confirmed, whether the owner records a reason (and so the backoffice requires one), the
// permission its route requires (src/server/routes.ts), and the states of the record it is offered
// in, as the owner allows them. The backoffice adds no rule of its own (FR-007): a state listed here is
// one in which the owner accepts the action; the owner still decides.
import type { MessageKey } from "../../shared/messages/index.ts";

/** `required`: the owner records a reason; `optional`: it records a note if one is given. */
export type ReasonRule = "required" | "optional" | "none";

export interface ActionDescription {
  label: MessageKey;
  /** The effect in the domain's words, shown before the operator confirms. */
  effect: MessageKey;
  permission: string;
  reason: ReasonRule;
  /** The body field the reason is sent in. */
  reasonField?: string;
  /** The record's states it is offered in; every state when absent. */
  offeredIn?: readonly string[];
}

export const Actions = {
  // payouts: a withdrawal request (WithdrawalRequest.scala: approve and reject only while pending review;
  // the payout settled or failed only once approved)
  "withdrawal.approve": {
    label: "action.withdrawal.approve.label",
    effect: "action.withdrawal.approve.effect",
    permission: "withdrawal.review",
    reason: "none",
    offeredIn: ["pending_review"],
  },
  "withdrawal.reject": {
    label: "action.withdrawal.reject.label",
    effect: "action.withdrawal.reject.effect",
    permission: "withdrawal.review",
    reason: "required",
    offeredIn: ["pending_review"],
  },
  "withdrawal.confirmPayout": {
    label: "action.withdrawal.confirmPayout.label",
    effect: "action.withdrawal.confirmPayout.effect",
    permission: "withdrawal.review",
    reason: "optional",
    reasonField: "note",
    offeredIn: ["approved", "awaiting_manual_payout"],
  },
  "withdrawal.markPayoutFailed": {
    label: "action.withdrawal.markPayoutFailed.label",
    effect: "action.withdrawal.markPayoutFailed.effect",
    permission: "withdrawal.review",
    reason: "required",
    offeredIn: ["approved", "awaiting_manual_payout"],
  },

  // wallet: an adjustment (FR-027: a reason from the brand's list, refused, not reduced, past the balance)
  "wallet.adjust": {
    label: "action.wallet.adjust.label",
    effect: "action.wallet.adjust.effect",
    permission: "adjustment.make",
    reason: "required",
  },

  // players: a player's standing (OperatorStatusRoutes.scala)
  "player.suspend": {
    label: "action.player.suspend.label",
    effect: "action.player.suspend.effect",
    permission: "standing.change",
    reason: "required",
    offeredIn: ["active"],
  },
  "player.reinstate": {
    label: "action.player.reinstate.label",
    effect: "action.player.reinstate.effect",
    permission: "standing.change",
    reason: "required",
    offeredIn: ["suspended"],
  },
  "player.close": {
    label: "action.player.close.label",
    effect: "action.player.close.effect",
    permission: "standing.change",
    reason: "required",
    offeredIn: ["active", "suspended", "blocked"],
  },
  "player.reopen": {
    label: "action.player.reopen.label",
    effect: "action.player.reopen.effect",
    permission: "standing.change",
    reason: "required",
    offeredIn: ["closed"],
  },
  "player.correctDateOfBirth": {
    label: "action.player.correctDateOfBirth.label",
    effect: "action.player.correctDateOfBirth.effect",
    permission: "profile.change",
    reason: "required",
  },
  "player.endSessions": {
    label: "action.player.endSessions.label",
    effect: "action.player.endSessions.effect",
    permission: "session.end",
    reason: "none",
  },

  // responsible-gambling: a break lifted is recorded with its reason (ProfileRoutes.scala)
  "protection.liftBreak": {
    label: "action.protection.liftBreak.label",
    effect: "action.protection.liftBreak.effect",
    permission: "protection.change",
    reason: "required",
  },
  // players: the brand's roles (RolesRoutes.scala records the change and who made it, and no reason)
  "role.create": { label: "action.role.create.label", effect: "action.role.create.effect", permission: "roles.change", reason: "none" },
  "role.change": { label: "action.role.change.label", effect: "action.role.change.effect", permission: "roles.change", reason: "none" },
  "role.reset": { label: "action.role.reset.label", effect: "action.role.reset.effect", permission: "roles.change", reason: "none", offeredIn: ["default"] },
  "role.delete": { label: "action.role.delete.label", effect: "action.role.delete.effect", permission: "roles.change", reason: "none", offeredIn: ["own"] },
  "role.give": { label: "action.role.give.label", effect: "action.role.give.effect", permission: "roles.give", reason: "none" },
} as const satisfies Record<string, ActionDescription>;

export type ActionId = keyof typeof Actions;

export function describe(id: ActionId): ActionDescription {
  return Actions[id];
}

/** Whether the record's state allows the action: exactly those its state allows are offered (FR-026). */
export function offeredIn(action: ActionDescription, state: string | undefined): boolean {
  if (!action.offeredIn) return true;
  return state !== undefined && action.offeredIn.includes(state.toLowerCase());
}

/** The reason as the owner takes it: trimmed, and absent when blank. */
export function reasonOf(text: string): string | undefined {
  const r = text.trim();
  return r === "" ? undefined : r;
}

/** Whether a reason typed allows the action to be confirmed. */
export function reasonGiven(action: ActionDescription, text: string): boolean {
  return action.reason !== "required" || reasonOf(text) !== undefined;
}

/** The body an action sends: the caller's fields and, when one was given, the reason in its field. */
export function withReason(action: ActionDescription, reason: string | undefined, body: Record<string, unknown> = {}): Record<string, unknown> {
  if (action.reason === "none" || reason === undefined) return body;
  return { ...body, [action.reasonField ?? "reason"]: reason };
}

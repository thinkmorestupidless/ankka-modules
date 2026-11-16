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
  // the payout settled or failed by hand only while awaiting manual payout or once its payment needs review,
  // WithdrawalWorkflow.manual: `needs_review` is the state `actionStateOf` names an approved request in then)
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
    offeredIn: ["awaiting_manual_payout", "needs_review"],
  },
  "withdrawal.markPayoutFailed": {
    label: "action.withdrawal.markPayoutFailed.label",
    effect: "action.withdrawal.markPayoutFailed.effect",
    permission: "withdrawal.review",
    reason: "required",
    offeredIn: ["awaiting_manual_payout", "needs_review"],
  },

  // wallet: an adjustment (FR-027: a reason from the brand's list, refused, not reduced, past the balance)
  "wallet.adjust": {
    label: "action.wallet.adjust.label",
    effect: "action.wallet.adjust.effect",
    permission: "adjustment.make",
    reason: "required",
  },

  // an adjustment batch (FR-028): its reason is part of the batch (its id is made from it), so it is
  // written on the batch's form, not in the confirmation, and a repeat is the same batch
  "wallet.adjustBatch": { label: "action.wallet.adjustBatch.label", effect: "action.wallet.adjustBatch.effect", permission: "adjustment.make", reason: "none" },
  // a non-player account (spec 022 FR-009: the wallet requires a reason, and the fact carries the category)
  "wallet.adjustAccount": { label: "action.wallet.adjustAccount.label", effect: "action.wallet.adjustAccount.effect", permission: "adjustment.make", reason: "required" },

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
  // US7 (T095 to T097)
  // kyc: a document's review (KycRecord.review: a document not yet reviewed; a rejection needs one of
  // the reason codes, which is not free text, so it is chosen in the dialog and no reason is typed)
  "identity.approveDocument": { label: "action.identity.approveDocument.label", effect: "action.identity.approveDocument.effect", permission: "identity.review", reason: "none", offeredIn: ["pending"] },
  "identity.rejectDocument": { label: "action.identity.rejectDocument.label", effect: "action.identity.rejectDocument.effect", permission: "identity.review", reason: "none", offeredIn: ["pending"] },
  // kyc: the player's record (OperatorEndpoint: mode, required documents, status, override, source of funds, flags)
  "identity.setManual": { label: "action.identity.setManual.label", effect: "action.identity.setManual.effect", permission: "identity.review", reason: "none", offeredIn: ["automatic"] },
  "identity.setAutomatic": { label: "action.identity.setAutomatic.label", effect: "action.identity.setAutomatic.effect", permission: "identity.review", reason: "none", offeredIn: ["manual"] },
  "identity.setRequired": { label: "action.identity.setRequired.label", effect: "action.identity.setRequired.effect", permission: "identity.review", reason: "none" },
  "identity.markPending": { label: "action.identity.markPending.label", effect: "action.identity.markPending.effect", permission: "identity.review", reason: "none", offeredIn: ["unverified", "verified", "rejected"] },
  "identity.verify": { label: "action.identity.verify.label", effect: "action.identity.verify.effect", permission: "identity.review", reason: "none", offeredIn: ["unverified", "pending", "rejected"] },
  // the original's approve and reject: regardless of the documents and the profile, with an optional note (KycRecord.overrideStatus)
  "identity.overrideVerify": { label: "action.identity.overrideVerify.label", effect: "action.identity.overrideVerify.effect", permission: "identity.review", reason: "optional", offeredIn: ["unverified", "pending", "rejected"] },
  "identity.overrideReject": { label: "action.identity.overrideReject.label", effect: "action.identity.overrideReject.effect", permission: "identity.review", reason: "optional", offeredIn: ["unverified", "pending", "verified"] },
  "identity.clearSof": { label: "action.identity.clearSof.label", effect: "action.identity.clearSof.effect", permission: "identity.review", reason: "none", offeredIn: ["unverified"] },
  "identity.resetSof": { label: "action.identity.resetSof.label", effect: "action.identity.resetSof.effect", permission: "identity.review", reason: "none", offeredIn: ["cleared"] },
  // an open flag dismissed with a note, which kyc requires (KycRecord.dismissFlag: note_required)
  "identity.dismissFlag": { label: "action.identity.dismissFlag.label", effect: "action.identity.dismissFlag.effect", permission: "identity.review", reason: "required", reasonField: "note", offeredIn: ["open"] },

  // responsible-gambling: an operator's change applies at once (ProfileEntity.setLimit, removeLimit), and
  // a change responsible-gambling stages is shown with when it takes effect
  "protection.setLimit": { label: "action.protection.setLimit.label", effect: "action.protection.setLimit.effect", permission: "protection.change", reason: "none" },
  "protection.newLimit": { label: "action.protection.newLimit.label", effect: "action.protection.setLimit.effect", permission: "protection.change", reason: "none" },
  "protection.removeLimit": { label: "action.protection.removeLimit.label", effect: "action.protection.removeLimit.effect", permission: "protection.change", reason: "none" },
  "protection.startBreak": { label: "action.protection.startBreak.label", effect: "action.protection.startBreak.effect", permission: "protection.change", reason: "none", offeredIn: ["none"] },
  "protection.changeBreak": { label: "action.protection.changeBreak.label", effect: "action.protection.changeBreak.effect", permission: "protection.change", reason: "none", offeredIn: ["on_break"] },
  "protection.selfExclude": { label: "action.protection.selfExclude.label", effect: "action.protection.selfExclude.effect", permission: "protection.change", reason: "none", offeredIn: ["not_excluded"] },
  // a return only once a time-limited exclusion has ended, and once (ProfileEntity.requestReturn)
  "protection.requestReturn": { label: "action.protection.requestReturn.label", effect: "action.protection.requestReturn.effect", permission: "protection.change", reason: "none", offeredIn: ["ended"] },
  "protection.openCase": { label: "action.protection.openCase.label", effect: "action.protection.openCase.effect", permission: "protection.change", reason: "optional", reasonField: "note" },
  "protection.noteCase": { label: "action.protection.noteCase.label", effect: "action.protection.noteCase.effect", permission: "protection.change", reason: "required", reasonField: "note", offeredIn: ["open", "closed"] },
  // the close's note is the `reason` field, kept if given (ProfileEntity.closeCase)
  "protection.closeCase": { label: "action.protection.closeCase.label", effect: "action.protection.closeCase.effect", permission: "protection.change", reason: "optional", reasonField: "reason", offeredIn: ["open"] },

  // players: a fraud case (FraudCaseEntity: reviewed while open; dismissed while not dismissed, with
  // optional notes, clearing the flag on every player it links)
  "fraud.review": { label: "action.fraud.review.label", effect: "action.fraud.review.effect", permission: "fraud.review", reason: "none", offeredIn: ["open"] },
  "fraud.dismiss": { label: "action.fraud.dismiss.label", effect: "action.fraud.dismiss.effect", permission: "fraud.review", reason: "optional", reasonField: "notes", offeredIn: ["open", "reviewed"] },

  // the brand's configuration: kyc's policy and licence profile, responsible-gambling's settings
  "config.identityPolicy": { label: "action.config.identityPolicy.label", effect: "action.config.identityPolicy.effect", permission: "config.brand", reason: "none" },
  "config.identityProfile": { label: "action.config.identityProfile.label", effect: "action.config.identityProfile.effect", permission: "config.brand", reason: "none" },
  "config.protectionSettings": { label: "action.config.protectionSettings.label", effect: "action.config.protectionSettings.effect", permission: "config.brand", reason: "none" },

  // rewards: a reward revoked (RewardAdminEndpoint.Revocations: funds and free rounds only while ACTIVE, a pending
  // cap among the refused, NO_ACTIVE_BONUS; the reason kept if given); an operator's award (OperatorAwards: it
  // records the operator and no reason); the brand's offers (OffersEndpoint: an offer is never removed, a delete
  // deactivates it; the operator is recorded on every change, with no reason)
  "reward.forfeit": { label: "action.reward.forfeit.label", effect: "action.reward.forfeit.effect", permission: "reward.revoke", reason: "optional", offeredIn: ["active"] },
  "reward.reclaim": { label: "action.reward.reclaim.label", effect: "action.reward.reclaim.effect", permission: "reward.revoke", reason: "optional", offeredIn: ["active"] },
  "reward.grant": { label: "action.reward.grant.label", effect: "action.reward.grant.effect", permission: "reward.grant", reason: "none" },
  "offer.create": { label: "action.offer.create.label", effect: "action.offer.create.effect", permission: "offer.manage", reason: "none" },
  "offer.edit": { label: "action.offer.edit.label", effect: "action.offer.edit.effect", permission: "offer.manage", reason: "none" },
  "offer.deactivate": { label: "action.offer.deactivate.label", effect: "action.offer.deactivate.effect", permission: "offer.manage", reason: "none", offeredIn: ["active"] },
  "offer.reorder": { label: "action.offer.reorder.label", effect: "action.offer.reorder.effect", permission: "offer.manage", reason: "none" },

  // players: the brand's roles (RolesRoutes.scala records the change and who made it, and no reason)
  "role.create": { label: "action.role.create.label", effect: "action.role.create.effect", permission: "roles.change", reason: "none" },
  "role.change": { label: "action.role.change.label", effect: "action.role.change.effect", permission: "roles.change", reason: "none" },
  "role.reset": { label: "action.role.reset.label", effect: "action.role.reset.effect", permission: "roles.change", reason: "none", offeredIn: ["default"] },
  "role.delete": { label: "action.role.delete.label", effect: "action.role.delete.effect", permission: "roles.change", reason: "none", offeredIn: ["own"] },
  "role.give": { label: "action.role.give.label", effect: "action.role.give.effect", permission: "roles.give", reason: "none" },

  // US9: the brand's configuration (players, wallet, payouts, reporting: each records the operator and no reason)
  "config.brandSite": { label: "action.config.brandSite.label", effect: "action.config.brandSite.effect", permission: "config.brand", reason: "none" },
  "config.countries": { label: "action.config.countries.label", effect: "action.config.countries.effect", permission: "config.brand", reason: "none" },
  "config.currencies": { label: "action.config.currencies.label", effect: "action.config.currencies.effect", permission: "config.brand", reason: "none" },
  "config.zone": { label: "action.config.zone.label", effect: "action.config.zone.effect", permission: "config.brand", reason: "none" },
  "config.turnover": { label: "action.config.turnover.label", effect: "action.config.turnover.effect", permission: "config.brand", reason: "none" },
  "config.withdrawalLimits": { label: "action.config.withdrawalLimits.label", effect: "action.config.withdrawalLimits.effect", permission: "config.brand", reason: "none" },
  "config.rewardSettings": { label: "action.config.rewardSettings.label", effect: "action.config.rewardSettings.effect", permission: "config.brand", reason: "none" },
  "config.reportingSettings": { label: "action.config.reportingSettings.label", effect: "action.config.reportingSettings.effect", permission: "config.brand", reason: "none" },
  // a module is asked of the vendor with a note, kept with the request (players' moduleRequest)
  "module.request": { label: "action.module.request.label", effect: "action.module.request.effect", permission: "modules.request", reason: "none" },
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

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
  // a player's turnover terms (AudienceEndpoints turnover-override: no reason, the actor recorded; an
  // empty body clears them back to the brand's)
  "wallet.turnoverOverride": { label: "action.wallet.turnoverOverride.label", effect: "action.wallet.turnoverOverride.effect", permission: "turnover.override", reason: "none" },
  "wallet.clearTurnoverOverride": { label: "action.wallet.clearTurnoverOverride.label", effect: "action.wallet.clearTurnoverOverride.effect", permission: "turnover.override", reason: "none" },

  // players: a player's standing (OperatorStatusRoutes.scala)
  "player.suspend": {
    label: "action.player.suspend.label",
    effect: "action.player.suspend.effect",
    permission: "standing.change",
    reason: "required",
    offeredIn: ["active"],
  },
  // reinstate reads no reason (OperatorStatusRoutes.reinstate); only a suspended player is reinstated
  "player.reinstate": {
    label: "action.player.reinstate.label",
    effect: "action.player.reinstate.effect",
    permission: "standing.change",
    reason: "none",
    offeredIn: ["suspended"],
  },
  "player.close": {
    label: "action.player.close.label",
    effect: "action.player.close.effect",
    permission: "standing.change",
    reason: "required",
    offeredIn: ["pending_email_verification", "active", "suspended", "self_excluded", "blocked"],
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

  // US3: players, the rest of a player's standing (OperatorStatusRoutes, OperatorProfileRoutes, ErasureRoutes):
  // a reset link and the verification link take no reason; clearing a flag records who and when; an
  // erasure is filed only for a closed account; the profile's and the consents' edits record who, no reason
  "player.resetPassword": { label: "action.player.resetPassword.label", effect: "action.player.resetPassword.effect", permission: "profile.change", reason: "none" },
  "player.verificationLink": { label: "action.player.verificationLink.label", effect: "action.player.verificationLink.effect", permission: "profile.change", reason: "none", offeredIn: ["pending_email_verification"] },
  "player.clearSuspectFlag": { label: "action.player.clearSuspectFlag.label", effect: "action.player.clearSuspectFlag.effect", permission: "flag.clear", reason: "none", offeredIn: ["flagged"] },
  "player.clearNoDepositBlock": { label: "action.player.clearNoDepositBlock.label", effect: "action.player.clearNoDepositBlock.effect", permission: "flag.clear", reason: "none", offeredIn: ["blocked"] },
  "player.requestErasure": { label: "action.player.requestErasure.label", effect: "action.player.requestErasure.effect", permission: "erasure.request", reason: "none", offeredIn: ["closed"] },
  "player.editProfile": { label: "action.player.editProfile.label", effect: "action.player.editProfile.effect", permission: "profile.change", reason: "none" },
  "player.consents": { label: "action.player.consents.label", effect: "action.player.consents.effect", permission: "profile.change", reason: "none" },
  "player.forceCloseSession": { label: "action.player.forceCloseSession.label", effect: "action.player.forceCloseSession.effect", permission: "session.end", reason: "none", offeredIn: ["active"] },
  // a game session ended by force (GameSessionRoutes.forceEnd: only an active one ends; no reason, the end
  // names the operator), and every open game session of the player at once (forceEndAll)
  "player.forceEndGameSession": { label: "action.player.forceEndGameSession.label", effect: "action.player.forceEndGameSession.effect", permission: "session.end", reason: "none", offeredIn: ["active"] },
  "player.forceEndAllGameSessions": { label: "action.player.forceEndAllGameSessions.label", effect: "action.player.forceEndAllGameSessions.effect", permission: "session.end", reason: "none" },
  "note.write": { label: "action.note.write.label", effect: "action.note.write.effect", permission: "note.write", reason: "none" },
  "note.pin": { label: "action.note.pin.label", effect: "action.note.pin.effect", permission: "note.write", reason: "none", offeredIn: ["unpinned"] },
  "note.unpin": { label: "action.note.unpin.label", effect: "action.note.unpin.effect", permission: "note.write", reason: "none", offeredIn: ["pinned"] },
  // players: an inbox item to the player (OperatorStatusRoutes.message: no reason recorded; refused for a closed account)
  "message.send": { label: "action.message.send.label", effect: "action.message.send.effect", permission: "message.send", reason: "none" },

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
  // game-provider: a provider's configuration replaced whole, the catalogue read again, its configuration;
  // an unacknowledged call replayed (no reason) or resolved with the reason game-provider requires
  "games.configureProvider": { label: "action.games.configureProvider.label", effect: "action.games.configureProvider.effect", permission: "config.games", reason: "none" },
  "games.refreshCatalogue": { label: "action.games.refreshCatalogue.label", effect: "action.games.refreshCatalogue.effect", permission: "config.games", reason: "none" },
  "games.catalogue": { label: "action.games.catalogue.label", effect: "action.games.catalogue.effect", permission: "config.games", reason: "none" },
  "callback.replay": { label: "action.callback.replay.label", effect: "action.callback.replay.effect", permission: "callback.resolve", reason: "none" },
  "callback.resolve": { label: "action.callback.resolve.label", effect: "action.callback.resolve.effect", permission: "callback.resolve", reason: "required" },

  // affiliate-programme (US10): OperatorEndpoint at /v1/affiliates/<slug>. A manager's writes record the
  // manager (`principal.subject`) in the programme's audit; none of these takes a reason
  "programme.plan.create": { label: "action.programme.plan.create.label", effect: "action.programme.plan.create.effect", permission: "programme.manage", reason: "none" },
  "programme.plan.edit": { label: "action.programme.plan.edit.label", effect: "action.programme.plan.edit.effect", permission: "programme.manage", reason: "none" },
  "programme.link.create": { label: "action.programme.link.create.label", effect: "action.programme.link.create.effect", permission: "programme.manage", reason: "none" },
  "programme.creative.create": { label: "action.programme.creative.create.label", effect: "action.programme.creative.create.effect", permission: "programme.manage", reason: "none" },
  "programme.creative.delete": { label: "action.programme.creative.delete.label", effect: "action.programme.creative.delete.effect", permission: "programme.manage", reason: "none" },
  "programme.message.send": { label: "action.programme.message.send.label", effect: "action.programme.message.send.effect", permission: "programme.manage", reason: "none" },
  "programme.settings.save": { label: "action.programme.settings.save.label", effect: "action.programme.settings.save.effect", permission: "programme.manage", reason: "none" },
  "programme.page.save": { label: "action.programme.page.save.label", effect: "action.programme.page.save.effect", permission: "programme.manage", reason: "none" },
  // the managers' routes admit brand_admin and platform_admin (`Access.managerAdmin`); a reset sends a link, no password
  "programme.manager.add": { label: "action.programme.manager.add.label", effect: "action.programme.manager.add.effect", permission: "programme.managers", reason: "none" },
  "programme.manager.reset": { label: "action.programme.manager.reset.label", effect: "action.programme.manager.reset.effect", permission: "programme.managers", reason: "none" },
  "programme.manager.revoke": { label: "action.programme.manager.revoke.label", effect: "action.programme.manager.revoke.effect", permission: "programme.managers", reason: "none" },
  // accounts: status at the brand through approve and reject (pending only) and the network routes (suspend an
  // active membership, reactivate a suspended one); the reset sends a link, no password
  "programme.account.create": { label: "action.programme.account.create.label", effect: "action.programme.account.create.effect", permission: "programme.manage", reason: "none" },
  "programme.account.approve": { label: "action.programme.account.approve.label", effect: "action.programme.account.approve.effect", permission: "programme.manage", reason: "none", offeredIn: ["pending"] },
  "programme.account.reject": { label: "action.programme.account.reject.label", effect: "action.programme.account.reject.effect", permission: "programme.manage", reason: "optional", reasonField: "reason", offeredIn: ["pending"] },
  "programme.account.suspend": { label: "action.programme.account.suspend.label", effect: "action.programme.account.suspend.effect", permission: "programme.manage", reason: "none", offeredIn: ["active"] },
  "programme.account.reactivate": { label: "action.programme.account.reactivate.label", effect: "action.programme.account.reactivate.effect", permission: "programme.manage", reason: "none", offeredIn: ["suspended"] },
  "programme.account.resendInvitation": { label: "action.programme.account.resendInvitation.label", effect: "action.programme.account.resendInvitation.effect", permission: "programme.manage", reason: "none", offeredIn: ["active"] },
  "programme.account.resetPassword": { label: "action.programme.account.resetPassword.label", effect: "action.programme.account.resetPassword.effect", permission: "programme.manage", reason: "none" },
  "programme.account.updateEmail": { label: "action.programme.account.updateEmail.label", effect: "action.programme.account.updateEmail.effect", permission: "programme.manage", reason: "none" },
  "programme.account.edit": { label: "action.programme.account.edit.label", effect: "action.programme.account.edit.effect", permission: "programme.manage", reason: "none" },
  "programme.account.note": { label: "action.programme.account.note.label", effect: "action.programme.account.note.effect", permission: "programme.manage", reason: "none" },
  // an adjustment's reason is required by the programme (AdjustmentBody)
  "programme.account.adjust": { label: "action.programme.account.adjust.label", effect: "action.programme.account.adjust.effect", permission: "programme.manage", reason: "required", reasonField: "reason" },
  // payouts (Settlement.transition): pending → approved or rejected, approved → paid
  "programme.payouts.close": { label: "action.programme.payouts.close.label", effect: "action.programme.payouts.close.effect", permission: "programme.manage", reason: "none" },
  "programme.payout.approve": { label: "action.programme.payout.approve.label", effect: "action.programme.payout.approve.effect", permission: "programme.manage", reason: "none", offeredIn: ["pending"] },
  "programme.payout.reject": { label: "action.programme.payout.reject.label", effect: "action.programme.payout.reject.effect", permission: "programme.manage", reason: "optional", reasonField: "reason", offeredIn: ["pending"] },
  "programme.payout.pay": { label: "action.programme.payout.pay.label", effect: "action.programme.payout.pay.effect", permission: "programme.manage", reason: "none", offeredIn: ["approved"] },
  // a fraud flag (Records.dismiss): the reason required, kept with the manager
  "programme.flag.dismiss": { label: "action.fraud.dismiss.label", effect: "action.programme.flag.dismiss.effect", permission: "programme.manage", reason: "required", reasonField: "reason", offeredIn: ["open"] },
  // end of US10
  // engagement: its CRM settings and seed; a parked send retried (no reason) or discarded with the reason
  // engagement requires; support-agent's settings; attribution's windows, networks, webhooks and keys (it
  // records the operator and takes no reason), and a fact resent or discarded while undelivered
  "crm.configure": { label: "action.crm.configure.label", effect: "action.crm.configure.effect", permission: "config.crm", reason: "none" },
  "crm.seed": { label: "action.crm.seed.label", effect: "action.crm.seed.effect", permission: "config.crm", reason: "none" },
  "send.retry": { label: "action.send.retry.label", effect: "action.send.retry.effect", permission: "send.release", reason: "none", offeredIn: ["parked"] },
  "send.discard": { label: "action.send.discard.label", effect: "action.send.discard.effect", permission: "send.release", reason: "required", offeredIn: ["parked"] },
  "agent.configure": { label: "action.agent.configure.label", effect: "action.agent.configure.effect", permission: "config.agent", reason: "none" },
  "attribution.windows": { label: "action.attribution.windows.label", effect: "action.attribution.windows.effect", permission: "config.attribution", reason: "none" },
  "attribution.addNetwork": { label: "action.attribution.addNetwork.label", effect: "action.attribution.addNetwork.effect", permission: "config.attribution", reason: "none" },
  "attribution.changeNetwork": { label: "action.attribution.changeNetwork.label", effect: "action.attribution.changeNetwork.effect", permission: "config.attribution", reason: "none" },
  "attribution.removeNetwork": { label: "action.attribution.removeNetwork.label", effect: "action.attribution.removeNetwork.effect", permission: "config.attribution", reason: "none", offeredIn: ["live"] },
  "attribution.pseudonymKey": { label: "action.attribution.pseudonymKey.label", effect: "action.attribution.pseudonymKey.effect", permission: "config.attribution", reason: "none", offeredIn: ["none"] },
  "attribution.addWebhook": { label: "action.attribution.addWebhook.label", effect: "action.attribution.addWebhook.effect", permission: "config.attribution", reason: "none" },
  "attribution.pauseWebhook": { label: "action.attribution.pauseWebhook.label", effect: "action.attribution.pauseWebhook.effect", permission: "config.attribution", reason: "none" },
  "attribution.resumeWebhook": { label: "action.attribution.resumeWebhook.label", effect: "action.attribution.resumeWebhook.effect", permission: "config.attribution", reason: "none" },
  "attribution.removeWebhook": { label: "action.attribution.removeWebhook.label", effect: "action.attribution.removeWebhook.effect", permission: "config.attribution", reason: "none" },
  "attribution.makeKey": { label: "action.attribution.makeKey.label", effect: "action.attribution.makeKey.effect", permission: "config.attribution", reason: "none" },
  "attribution.retireKey": { label: "action.attribution.retireKey.label", effect: "action.attribution.retireKey.effect", permission: "config.attribution", reason: "none" },
  "fact.resend": { label: "action.fact.resend.label", effect: "action.fact.resend.effect", permission: "fact.redeliver", reason: "none", offeredIn: ["undelivered"] },
  "fact.discard": { label: "action.fact.discard.label", effect: "action.fact.discard.effect", permission: "fact.redeliver", reason: "none", offeredIn: ["undelivered"] },
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

package io.eitheror.casino.programme.links

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId, ErrorCode, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.programme.ProgrammeCodecs

import java.time.Instant

/**
 * An affiliate's bonus code at a brand (data-model.md *BonusCode*), id `<brand>/<CODE>`: the text in
 * upper case, so a code is unique at a brand whatever its case (FR-025). `changes` counts every claim
 * and revocation, so the brand's code document's version (their sum) rises with every change.
 */
final case class BonusCodeState(
    code: String = "",
    brand: String = "",
    affiliate: String = "",
    link: String = "",
    offer: Option[String] = None,
    tag: String = "",
    status: Option[String] = None,
    createdAt: Option[Instant] = None,
    revokedAt: Option[Instant] = None,
    changes: Int = 0,
    changedAt: Option[Instant] = None
):
  def live: Boolean = status.contains(BonusCode.Active)

final case class ClaimCode(code: String, brand: String, affiliate: String, link: String, offer: Option[String], tag: String, at: Instant)
/** `affiliate`: the affiliate revoking, who must hold the code; none when a manager does. */
final case class RevokeCode(affiliate: Option[String], at: Instant)

object BonusCodeCodecs:
  given Serializer[BonusCodeState] = ProgrammeCodecs.serializer[BonusCodeState]("bonus-code")
  given Serializer[ClaimCode]      = ProgrammeCodecs.serializer[ClaimCode]("claim-code")
  given Serializer[RevokeCode]     = ProgrammeCodecs.serializer[RevokeCode]("revoke-code")

import BonusCodeCodecs.given

final class BonusCode(@scala.annotation.unused context: KeyValueEntityContext) extends KeyValueEntity[BonusCodeState]:
  def emptyState: BonusCodeState = BonusCodeState()

  /** One wins: a live code refuses the rest, as the original's create-if-absent did; a revoked one is claimed again. */
  def claim(c: ClaimCode): Effect[BonusCodeState] =
    if currentState.live then effects.error("code_already_exists", ErrorCode.Conflict)
    else
      effects.updateState(BonusCodeState(c.code.toUpperCase(java.util.Locale.ROOT), c.brand, c.affiliate, c.link, c.offer, c.tag, Some(BonusCode.Active),
        Some(c.at), None, currentState.changes + 1, Some(c.at))).thenReplyState

  def revoke(c: RevokeCode): Effect[BonusCodeState] =
    val s = currentState
    if s.status.isEmpty then effects.error("not_found", ErrorCode.NotFound)
    else if c.affiliate.exists(_ != s.affiliate) then effects.error("forbidden", ErrorCode.Forbidden)
    else if !s.live then effects.reply(s)
    else effects.updateState(s.copy(status = Some(BonusCode.Revoked), revokedAt = Some(c.at), changes = s.changes + 1, changedAt = Some(c.at)))
      .thenReplyState

  def get: ReadOnlyEffect[BonusCodeState] = effects.reply(currentState)

object BonusCode extends KeyValueEntity.Companion[BonusCode, BonusCodeState](
      componentId = ComponentId("bonus-code"),
      stateSerializer = BonusCodeCodecs.given_Serializer_BonusCodeState
    ):
  val Active  = "ACTIVE"
  val Revoked = "REVOKED"

  /** The text a code may be: what a player types, and part of the entity's id. */
  val Text = "[A-Za-z0-9_-]{1,64}".r

  def entityId(brand: String, code: String): EntityId = EntityId(s"$brand/${code.toUpperCase(java.util.Locale.ROOT)}")

  def create(context: KeyValueEntityContext) = new BonusCode(context)

  val claim  = command("claim")(_.claim)
  val revoke = command("revoke")(_.revoke)
  val get    = query("get")(_.get)

/** A row per code (data-model.md *Codes*, named apart from the routes' error codes): the code routes' lists and the brand's code document. */
final class BonusCodesView extends View[BonusCodeState, BonusCodeState]:
  def onChange(s: BonusCodeState): Effect = effects.updateRow(s)

object BonusCodes extends View.Companion[BonusCodesView, BonusCodeState, BonusCodeState](
      componentId = ComponentId("codes"),
      source = ChangeSource.stateOf(BonusCode),
      rowSerializer = BonusCodeCodecs.given_Serializer_BonusCodeState
    ):
  /** Every code at a brand, live or not: the document's version sums their changes. */
  val ofBrand = query("of-brand")(s"SELECT payload FROM $table WHERE payload::jsonb->>'brand' = :brand ORDER BY row_key")
  val ofAffiliate = query("of-affiliate")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'affiliate' = :affiliate AND payload::jsonb->>'brand' = :brand ORDER BY row_key")

  def create(ctx: ViewComponentContext) = new BonusCodesView

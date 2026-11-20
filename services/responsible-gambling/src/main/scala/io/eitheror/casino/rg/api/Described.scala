package io.eitheror.casino.rg.api

import com.thinkmorestupidless.ankka.http.openapi.Description

import java.time.Instant

/**
 * What the player routes take and answer, for their descriptions (spec 031,
 * contracts/descriptions.md). The routes build their JSON as trees (`Render`, `Answers`), so these
 * types exist only to give the document their shapes; each mirrors what `Render` writes, field for
 * field, and contracts/routes.md's tables. The kit (`it.Kit`) holds every answer to them.
 */
private[api] object Described:

  /**
   * `Answers.refusal`: the code, the message, and any named fields beside them. The original's
   * refusals had no message (tests/port/engagement/), so a client may not rely on one.
   */
  final case class Refusal(error: String, message: Option[String])

  final case class LimitRow(limitType: String, value: Long, currency: Option[String])
  final case class PendingChange(limitType: String, pendingValue: Option[String], appliesAt: Instant, currency: Option[String])
  /** The original's sums named no currency (tests/port/engagement/); the rebuild's always do. */
  final case class UsedAmount(limitType: String, currency: Option[String], usedAmount: BigDecimal)

  /** `Render.status`: the player's limits, staged changes, sums, break and reality check. */
  final case class Protection(
      limits: Vector[LimitRow],
      pendingChanges: Vector[PendingChange],
      usedAmounts: Vector[UsedAmount],
      isCoolingOff: Boolean,
      activeRestrictions: Vector[String],
      coolingOffUntil: Option[Instant],
      realityCheckIntervalMinutes: Option[Int]
  )

  /** `Render.history`'s entries, newest first. */
  final case class HistoryEntry(eventType: String, actor: String, occurredAt: Instant, description: String,
      limitType: Option[String], previousValue: Option[String], newValue: Option[String])

  final case class Excluded(status: String)
  final case class ReturnRequested(reinstatesAt: Option[Instant])
  final case class Acknowledged(acknowledged: Boolean)

  final case class SetLimit(value: String, currency: Option[String])
  final case class SetBreak(until: Instant)
  final case class SetRealityCheck(intervalMinutes: Int)
  final case class Acknowledge(sessionId: String)

  val refusals: Description = Description.none.refusalShape[Refusal]

  /** Every player route's own refusal: the `{player}` segment is not the token's player. */
  val mine = "player_id_mismatch"

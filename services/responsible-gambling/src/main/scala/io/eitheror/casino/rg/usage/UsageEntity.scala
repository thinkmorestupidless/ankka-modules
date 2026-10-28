package io.eitheror.casino.rg.usage

import com.thinkmorestupidless.ankka.core.{ComponentId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.rg.RgCodecs
import io.eitheror.casino.rg.profile.Sums

import java.time.{Duration, Instant}

final case class Deposit(at: Instant, amount: Long)

/**
 * A player's usage in one currency, for display only (R7; FR-012): every deposit of the last 30 days,
 * exact; net losses (stakes less wins and rollbacks) by minute for 24 hours and by hour for 30 days; and
 * the transaction ids recently folded, so a redelivery is no change. Never read by a check.
 */
final case class Usage(
    deposits: Vector[Deposit] = Vector.empty,
    lossMinutes: Map[Long, Long] = Map.empty,
    lossHours: Map[Long, Long] = Map.empty,
    seen: Vector[String] = Vector.empty
)

/** A transaction folded in: a deposit, or a signed change to the net loss. */
final case class Record(transactionId: String, kind: String, amount: Long, at: Instant)

final case class UsageAt(now: Instant)

/** The deposit and loss sums of the three windows. */
final case class UsageSums(deposits: Sums, losses: Sums)

object Windows:
  val Day   = Duration.ofDays(1)
  val Week  = Duration.ofDays(7)
  val Month = Duration.ofDays(30)
  val SeenKept = 2_000

  def minute(at: Instant): Long = at.getEpochSecond / 60
  def hour(at: Instant): Long   = at.getEpochSecond / 3600

  def fold(u: Usage, r: Record, now: Instant): Usage =
    if u.seen.contains(r.transactionId) then u
    else
      val seen = (u.seen :+ r.transactionId).takeRight(SeenKept)
      val next = r.kind match
        case Record.Deposit => u.copy(deposits = u.deposits :+ Deposit(r.at, r.amount))
        case _ =>
          u.copy(lossMinutes = u.lossMinutes.updated(minute(r.at), u.lossMinutes.getOrElse(minute(r.at), 0L) + r.amount),
            lossHours = u.lossHours.updated(hour(r.at), u.lossHours.getOrElse(hour(r.at), 0L) + r.amount))
      prune(next.copy(seen = seen), now)

  /** Bounded: nothing older than its window (24 h of minutes, 30 days of hours and deposits). */
  def prune(u: Usage, now: Instant): Usage =
    val monthAgo = now.minus(Month)
    u.copy(
      deposits = u.deposits.filter(_.at.isAfter(monthAgo)),
      lossMinutes = u.lossMinutes.filter((m, _) => m > minute(now.minus(Day))),
      lossHours = u.lossHours.filter((h, _) => h > hour(monthAgo))
    )

  /** Deposits exactly; the daily loss to the minute, the weekly and monthly to the hour; a net gain shown as no loss. */
  def sums(u: Usage, now: Instant): UsageSums =
    def deposited(w: Duration) = u.deposits.filter(_.at.isAfter(now.minus(w))).map(_.amount).sum
    def lostSince(buckets: Map[Long, Long], from: Long) = math.max(0L, buckets.collect { case (k, v) if k > from => v }.sum)
    UsageSums(
      Sums(deposited(Day), deposited(Week), deposited(Month)),
      Sums(lostSince(u.lossMinutes, minute(now.minus(Day))), lostSince(u.lossHours, hour(now.minus(Week))),
        lostSince(u.lossHours, hour(now.minus(Month))))
    )

object Record:
  val Deposit = "deposit"
  val Loss    = "loss"

/** Keyed `player/currency`. */
final class UsageEntity(@scala.annotation.unused context: KeyValueEntityContext) extends KeyValueEntity[Usage]:
  def emptyState: Usage = Usage()

  def record(r: Record): Effect[Boolean] =
    if currentState.seen.contains(r.transactionId) then effects.reply(false)
    else effects.updateState(Windows.fold(currentState, r, r.at)).thenReply(_ => true)

  def sums(c: UsageAt): ReadOnlyEffect[UsageSums] = effects.reply(Windows.sums(currentState, c.now))

object UsageEntity
    extends KeyValueEntity.Companion[UsageEntity, Usage](
      componentId = ComponentId("rg-usage"),
      stateSerializer = RgCodecs.serializer[Usage]("usage")
    ):
  given Serializer[Record]    = RgCodecs.serializer[Record]("record")
  given Serializer[UsageAt]   = RgCodecs.serializer[UsageAt]("usage-at")
  given Serializer[UsageSums] = RgCodecs.serializer[UsageSums]("usage-sums")
  given Serializer[Boolean]   = RgCodecs.serializer[Boolean]("recorded")

  def create(context: KeyValueEntityContext) = new UsageEntity(context)

  def id(playerId: String, currency: String): String = s"$playerId/$currency"

  val record = command("record")(_.record)
  val sums   = query("sums")(_.sums)

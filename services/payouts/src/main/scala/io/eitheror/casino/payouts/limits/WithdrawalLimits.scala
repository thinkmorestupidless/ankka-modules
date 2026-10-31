package io.eitheror.casino.payouts.limits

import com.thinkmorestupidless.ankka.core.{ComponentId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.contract.money.Currency
import io.eitheror.casino.payouts.PayoutsCodecs
import io.eitheror.casino.payouts.limits.LimitsEvent.LimitsSet

/**
 * A brand's weekly and monthly withdrawal limits per currency (R10), keyed by the slug. A set replaces
 * every currency's pair and records before and after for the audit; a set that changes nothing writes
 * nothing. A negative amount or an unknown currency is refused naming the field.
 */
final class WithdrawalLimits(@scala.annotation.unused context: EventSourcedEntityContext) extends EventSourcedEntity[LimitsState, LimitsEvent]:
  def emptyState: LimitsState = LimitsState()

  def applyEvent(event: LimitsEvent): LimitsState = event match
    case LimitsSet(_, next, _, _) => LimitsState(next)

  def set(c: SetLimits): Effect[LimitsAnswer] =
    WithdrawalLimits.refusal(c.limits) match
      case Some(field) => effects.reply(LimitsAnswer(currentState, Some(field)))
      case None =>
        val next = c.limits.map((code, pair) => code.toUpperCase -> pair).filter((_, p) => p.weekly.isDefined || p.monthly.isDefined)
        if next == currentState.limits then effects.reply(LimitsAnswer(currentState))
        else effects.persist(LimitsSet(currentState.limits, next, c.operatorId, c.at)).thenReply(LimitsAnswer(_))

  def get: ReadOnlyEffect[LimitsState] = effects.reply(currentState)

object WithdrawalLimits
    extends EventSourcedEntity.Companion[WithdrawalLimits, LimitsState, LimitsEvent](
      componentId = ComponentId("withdrawal-limits"),
      stateSerializer = PayoutsCodecs.serializer[LimitsState]("limits-state"),
      eventSerializer = PayoutsCodecs.serializer[LimitsEvent]("limits-event")
    ):
  given Serializer[LimitsState]  = PayoutsCodecs.serializer[LimitsState]("limits-reply")
  given Serializer[LimitsAnswer] = PayoutsCodecs.serializer[LimitsAnswer]("limits-answer")
  given Serializer[SetLimits]    = PayoutsCodecs.serializer[SetLimits]("set-limits")

  def create(context: EventSourcedEntityContext) = new WithdrawalLimits(context)

  val set = command("set")(_.set)
  val get = query("get")(_.get)

  /** The first field refused, as the body names it: `limits.<currency>` or `limits.<currency>.<window>`. */
  def refusal(limits: Map[String, Pair]): Option[String] =
    limits.toVector.sortBy(_._1).collectFirst {
      case (code, _) if Currency.from(code.toUpperCase).isLeft => s"limits.$code"
      case (code, p) if p.weekly.exists(_ < 0)                 => s"limits.$code.weekly"
      case (code, p) if p.monthly.exists(_ < 0)                => s"limits.$code.monthly"
    }

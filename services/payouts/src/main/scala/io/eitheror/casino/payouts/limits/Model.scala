package io.eitheror.casino.payouts.limits

import java.time.Instant

/** A brand's withdrawal limits, per currency code (FR-005a; data-model.md `WithdrawalLimits`). */
final case class LimitsState(limits: Map[String, Pair] = Map.empty):
  def of(currency: String): Pair = limits.getOrElse(currency, Pair())

sealed trait LimitsEvent
object LimitsEvent:
  /** Written to `platform.audit` with the operator, before and after. */
  final case class LimitsSet(previous: Map[String, Pair], next: Map[String, Pair], operatorId: String, at: Instant)
      extends LimitsEvent

/** Every currency's pair, replacing the last; a currency left out has no limit. */
final case class SetLimits(limits: Map[String, Pair], operatorId: String, at: Instant)

/** What the set answers: the limits stored, or the field it refused. */
final case class LimitsAnswer(state: LimitsState, refusal: Option[String] = None)

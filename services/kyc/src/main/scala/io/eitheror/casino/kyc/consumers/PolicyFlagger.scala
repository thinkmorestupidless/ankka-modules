package io.eitheror.casino.kyc.consumers

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.kyc.KycRuntime
import io.eitheror.casino.kyc.policy.{Policy, PolicyChanged, PolicyEntity}
import io.eitheror.casino.kyc.record.{FlagForPolicy, KycRecord}

/**
 * A policy that newly puts players over a threshold flags each of them (FR-022): on a `PolicyChanged` that
 * sets or lowers a threshold, or changes the KYC currency, every player the queue finds over it is told
 * `FlagForPolicy`. The record raises each flag only when none of its reason is open, so a player is
 * flagged once however often the policy is saved.
 */
final class PolicyFlagger(client: ComponentClient) extends Consumer[PolicyChanged, Nothing]:
  def onMessage(event: PolicyChanged): Effect =
    val players = PolicyFlagger.searches(event.before, event.after).flatMap(KycRuntime.queue().find).distinct
    players.foreach(p => client.forEventSourcedEntity(EntityId(p)).call(KycRecord.flagForPolicy).invoke(FlagForPolicy(event.after, event.at)): Unit)
    if players.isEmpty then effects.ignore() else effects.done()

object PolicyFlagger extends Consumer.Companion[PolicyFlagger, PolicyChanged, Nothing](
      ComponentId("kyc-policy-flagger"), ChangeSource.eventsOf(PolicyEntity)):
  def create(ctx: ConsumerContext) = new PolicyFlagger(ctx.componentClient)

  /** A search of the queue: players over `amount` in `currency`, not verified (`threshold`) or with funds not cleared (`sof`). */
  enum Search:
    case Threshold(currency: String, amount: Long)
    case Sof(currency: String, amount: Long)

  /** The searches a change calls for: a threshold set, lowered, or now in another currency. */
  def searches(before: Policy, after: Policy): Vector[Search] =
    def tighter(was: Option[Long], is: Option[Long]) = is.exists(i => after.currency != before.currency || was.forall(i < _))
    after.currency.toVector.flatMap { c =>
      Option.when(tighter(before.depositThreshold, after.depositThreshold))(Search.Threshold(c, after.depositThreshold.get)).toVector ++
        Option.when(tighter(before.sofThreshold, after.sofThreshold))(Search.Sof(c, after.sofThreshold.get))
    }

/** The queue's searches, wired at start to the running service's view client. */
trait QueueSearch:
  def find(search: PolicyFlagger.Search): Vector[String]

object QueueSearch:
  val Unwired: QueueSearch = _ => throw IllegalStateException("the queue is not wired: KycService sets KycRuntime.queue at start")

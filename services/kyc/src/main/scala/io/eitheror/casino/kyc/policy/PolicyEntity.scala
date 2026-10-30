package io.eitheror.casino.kyc.policy

import com.thinkmorestupidless.ankka.core.{ComponentId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.kyc.KycCodecs

import java.time.Instant

final case class ApplyProfile(profile: Option[String], operatorId: String, at: Instant)

final case class ChangePolicy(change: Change, operatorId: String, at: Instant)

/** The policy as it then stands, or the refusal's code with the field, category or known profiles it names. */
final case class PolicyAnswer(policy: Policy, refusal: Option[String] = None, field: Option[String] = None, known: Vector[String] = Vector.empty)

object PolicyAnswer:
  def refused(policy: Policy, r: PolicyRefusal): PolicyAnswer = r match
    case PolicyRefusal.InvalidPolicy(field)     => PolicyAnswer(policy, Some(r.code), Some(field))
    case PolicyRefusal.InvalidCategory(c)       => PolicyAnswer(policy, Some(r.code), Some(c))
    case PolicyRefusal.UnknownProfile(known)    => PolicyAnswer(policy, Some(r.code), known = known)
    case PolicyRefusal.CurrencyRequired | PolicyRefusal.ProfileRequired => PolicyAnswer(policy, Some(r.code))

/** Every change, with what the policy was and became, so the audit names each field (FR-028). */
final case class PolicyChanged(before: Policy, after: Policy, operatorId: String, at: Instant)

/**
 * A brand's KYC policy (R9), keyed by the slug. With no event it is the Curaçao default, marked as one.
 * Event sourced, as 010's settings are, so `PolicyFlagger` and the audit see every change.
 */
final class PolicyEntity(@scala.annotation.unused context: EventSourcedEntityContext) extends EventSourcedEntity[Policy, PolicyChanged]:
  def emptyState: Policy = Policy.Default

  def applyEvent(event: PolicyChanged): Policy = event.after

  private def save(next: Policy, operatorId: String, at: Instant): Effect[PolicyAnswer] =
    if next == currentState then effects.reply(PolicyAnswer(currentState))
    else effects.persist(PolicyChanged(currentState, next, operatorId, at)).thenReply(PolicyAnswer(_))

  def applyProfile(c: ApplyProfile): Effect[PolicyAnswer] =
    Profiles.of(c.profile) match
      case Left(r)     => effects.reply(PolicyAnswer.refused(currentState, r))
      case Right(next) => save(next, c.operatorId, c.at)

  def change(c: ChangePolicy): Effect[PolicyAnswer] =
    Policy.change(currentState, c.change) match
      case Left(r)     => effects.reply(PolicyAnswer.refused(currentState, r))
      case Right(next) => save(next, c.operatorId, c.at)

  def get: ReadOnlyEffect[Policy] = effects.reply(currentState)

object PolicyEntity
    extends EventSourcedEntity.Companion[PolicyEntity, Policy, PolicyChanged](
      componentId = ComponentId("kyc-policy"),
      stateSerializer = KycCodecs.serializer[Policy]("policy"),
      eventSerializer = KycCodecs.serializer[PolicyChanged]("policy-changed")
    ):
  given Serializer[Policy]       = KycCodecs.serializer[Policy]("policy-reply")
  given Serializer[ApplyProfile] = KycCodecs.serializer[ApplyProfile]("apply-profile")
  given Serializer[ChangePolicy] = KycCodecs.serializer[ChangePolicy]("change-policy")
  given Serializer[PolicyAnswer] = KycCodecs.serializer[PolicyAnswer]("policy-answer")

  def create(context: EventSourcedEntityContext) = new PolicyEntity(context)

  val applyProfile = command("apply-profile")(_.applyProfile)
  val change       = command("change")(_.change)
  val get          = query("get")(_.get)

package io.eitheror.casino.engagement.send

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.engagement.{EngagementCodecs, EngagementRuntime}
import io.eitheror.casino.engagement.clients.{CrmAnswer, CrmCredentials}
import io.eitheror.casino.engagement.config.{Configuration, CrmConfig}
import io.eitheror.casino.engagement.events.SendClass
import io.eitheror.casino.engagement.profile.{Attributes, DepositDay, Profile, ProfileState, Suppression}

import scala.concurrent.duration.*

/**
 * One send as a workflow, id = the send key (research R4, R5). `start` is the claim: a send already started answers
 * its state and calls nothing; a stale fact ends at once. The `call` step makes the request's calls in order, each
 * after reading the profile, the configuration and the keys afresh: an erased player ends it `discarded`, a closed
 * player's track or transactional ends it `account_closed`, a marketing one for a suppressed player
 * `marketing_suppressed` (the identify is still made first, FR-012). A retryable answer pauses 500 ms, 1 s, 2 s and
 * calls again; the fourth parks the send for an operator, who retries or discards it. Any other answer is `refused`:
 * an identify's ends the send, a later call's moves on.
 */
final class Send(context: WorkflowContext) extends Workflow[SendState]:
  import Send.log

  private def conf     = EngagementRuntime.settings
  private def now      = EngagementRuntime.now()

  def emptyState: SendState = SendState()

  override def settings: WorkflowSettings =
    WorkflowSettings.builder
      .defaultStepTimeout(30.seconds)
      // A step that throws (a component call that failed) runs again; past its retries `recover` waits and runs it.
      .stepRecovery(Send.call, RecoverStrategy.maxRetries(3).failoverTo(Send.recover))
      .stepRecovery(Send.recover, RecoverStrategy.maxRetries(100))
      .build

  // ── Commands ─────────────────────────────────────────────────────────────────────────────────

  def start(r: SendRequest): Effect[SendState] =
    if currentState.request.isDefined then effects.reply(currentState)
    else if r.key != context.workflowId.toString then
      effects.error(s"the send ${r.key} is not ${context.workflowId}", ErrorCode.BadRequest)
    else if r.calls.isEmpty then effects.error("a send makes at least one call", ErrorCode.BadRequest)
    else
      val at = now
      val claimed = SendState(request = Some(r), step = r.calls.headOption, parkAt = conf.attempts, updatedAt = Some(at))
      if r.trigger != Send.Erasure && r.occurredAt.plus(conf.staleness).isBefore(at) then
        val stale = claimed.copy(outcome = Outcome.SkippedStale, step = None)
        effects.updateState(stale).transitionTo(Send.finish.ref).thenReply(stale)
      else effects.updateState(claimed).transitionTo(Send.call.ref).thenReply(claimed)

  /** An operator's retry (FR-025): from `parked` only, one more attempt at the call that parked it. */
  def retry(r: Retry): Effect[SendState] =
    if currentState.outcome != Outcome.Parked then effects.error(Send.NotParked, ErrorCode.Conflict)
    else
      val kind = currentState.step.getOrElse(Kind.Identify)
      val next = currentState.copy(outcome = Outcome.Pending, parkedAt = None, parkAt = currentState.attemptsAt(kind) + 1,
        reason = None, updatedAt = Some(now))
      log.info(s"send ${context.workflowId}: retried by ${r.operator}")
      effects.updateState(next).transitionTo(Send.call.ref).thenReply(next)

  /** An operator's discard (FR-025): from `parked` only. The reason is personal: an operator may name the player. */
  def discard(d: Discard): Effect[SendState] =
    if currentState.outcome != Outcome.Parked then effects.error(Send.NotParked, ErrorCode.Conflict)
    else
      val playerId = currentState.request.fold("")(_.playerId)
      val next = currentState.copy(outcome = Outcome.Discarded, step = None, discardedBy = Some(d.operator),
        discardReason = Some(ProfileState.personal(playerId, d.reason)), updatedAt = Some(now))
      effects.updateState(next).transitionTo(Send.finish.ref).thenReply(next)

  /** Erasure's end of an open send (R15): any state not ended. */
  def erase: Effect[SendState] =
    if currentState.request.isEmpty || currentState.ended then effects.reply(currentState)
    else
      val next = currentState.copy(outcome = Outcome.Discarded, step = None, reason = Some(Send.Erased), updatedAt = Some(now))
      effects.updateState(next).transitionTo(Send.finish.ref).thenReply(next)

  def outcome: ReadOnlyEffect[SendState] = effects.reply(currentState)

  // ── Steps ────────────────────────────────────────────────────────────────────────────────────

  def callStep: StepEffect =
    (currentState.request, currentState.step) match
      case (Some(r), Some(kind)) if !currentState.ended && currentState.outcome != Outcome.Parked => attempt(r, kind)
      case _                                                                                     => stepEffects.thenEnd

  def finishStep: StepEffect = stepEffects.thenEnd

  /** A step that failed past its retries: wait, then run it again. */
  def recoverStep: StepEffect =
    log.error(s"send ${context.workflowId}: the call step failed past its retries; running it again")
    stepEffects.thenPause(5.seconds, Send.call.ref)

  private def attempt(r: SendRequest, kind: String): StepEffect =
    val at      = now
    val profile = context.componentClient.forKeyValueEntity(EntityId(r.playerId)).call(Profile.get).invoke()
    val config  = context.componentClient.forKeyValueEntity(EntityId(conf.brand)).call(Configuration.get).invoke()
    Send.judge(r, kind, profile, config) match
      case Some((outcome, reason)) => end(outcome, Some(reason))
      case None =>
        Send.prepare(r, kind, profile, config, conf.brand, DepositDay.of(at)) match
          case Send.Prepared.Skip              => advance(kind)
          case Send.Prepared.Stop(outcome)     => end(outcome, None)
          case Send.Prepared.Make(attributes, transactional, to) =>
            val keys  = EngagementRuntime.secrets.keys(context.secrets, conf.brand)
            val creds = CrmCredentials(config.siteId, config.region, keys.track, keys.app)
            val crm   = EngagementRuntime.crm
            val answer = kind match
              case Kind.Identify      => crm.identify(creds, r.playerId, attributes)
              case Kind.Track         => crm.track(creds, r.playerId, r.event.getOrElse(r.trigger), attributes)
              case Kind.Transactional => crm.send(creds, transactional.getOrElse(""), to.getOrElse(""), attributes)
              case _                  => crm.delete(creds, r.playerId)
            answered(r, kind, answer, at)

  private def answered(r: SendRequest, kind: String, answer: CrmAnswer, at: java.time.Instant): StepEffect =
    val tried = currentState.attemptsAt(kind) + 1
    answer match
      case CrmAnswer.Accepted(status) =>
        val s = currentState.record(Call(kind, tried, Outcome.Accepted, Some(status), None, at))
        next(s, kind)
      case CrmAnswer.Refused(status, body) =>
        log.warn(s"send ${r.key}: Customer.io refused the $kind call with $status")
        val s = currentState.record(Call(kind, tried, Outcome.Refused, Some(status), Some(body), at))
        if kind == Kind.Identify || r.calls.lastOption.contains(kind) then
          stepEffects.updateState(s.copy(outcome = Outcome.Refused, step = None, reason = Some(s"$kind refused $status"))).thenEnd
        else next(s, kind)
      case CrmAnswer.Retryable(status, why) =>
        val s = currentState.record(Call(kind, tried, Send.Retrying, status, Some(why), at))
        if Attempts.parked(tried, currentState.parkAt) then
          log.warn(s"send ${r.key}: the $kind call failed $tried times; parked")
          stepEffects.updateState(s.copy(outcome = Outcome.Parked, parkedAt = Some(at), reason = Some(why))).thenPause()
        else
          val wait = Attempts.delay(conf.attemptBase, tried)
          stepEffects.updateState(s).thenPause(wait.toMillis.millis, Send.call.ref)

  /** The next call after `kind`, or the end: refused when any call was, accepted otherwise. */
  private def next(s: SendState, kind: String): StepEffect =
    val calls = s.request.fold(Vector.empty[String])(_.calls)
    calls.drop(calls.indexOf(kind) + 1).headOption match
      case Some(k) => stepEffects.updateState(s.copy(step = Some(k))).thenTransitionTo(Send.call.ref)
      case None =>
        val outcome = if s.calls.exists(_.outcome == Outcome.Refused) then Outcome.Refused else Outcome.Accepted
        stepEffects.updateState(s.copy(outcome = outcome, step = None)).thenEnd

  private def advance(kind: String): StepEffect = next(currentState, kind)

  private def end(outcome: String, reason: Option[String]): StepEffect =
    stepEffects.updateState(currentState.copy(outcome = outcome, reason = reason, step = None, updatedAt = Some(now))).thenEnd

object Send
    extends Workflow.Companion[Send, SendState](
      componentId = ComponentId("send"),
      stateSerializer = EngagementCodecs.serializer[SendState]("send-state")
    ):
  private val log = org.slf4j.LoggerFactory.getLogger(classOf[Send])

  given JsonValueCodec[SendState] = EngagementCodecs.stored[SendState]
  given Serializer[SendRequest]   = EngagementCodecs.serializer[SendRequest]("send-request")
  given Serializer[SendState]     = EngagementCodecs.serializer[SendState]("send-reply")
  given Serializer[Retry]         = EngagementCodecs.serializer[Retry]("send-retry")
  given Serializer[Discard]       = EngagementCodecs.serializer[Discard]("send-discard")

  def create(context: WorkflowContext) = new Send(context)

  val Erasure   = "erasure"
  val Erased    = "erased"
  val Retrying  = "retrying"
  val NotParked = "not_parked"

  /** The original's built-in transactional ids, used when the brand set none (FR-013). */
  val Fallbacks: Map[String, String] = Map("EmailAddressVerification" -> "email_address_verification")

  /**
   * Whether the profile ends the send before `kind` is called (FR-011, FR-012, FR-016; pure). Erasure is never
   * stopped. An erased player's send is discarded; the identify is otherwise always made. A closed player's track or
   * transactional is `account_closed`; a marketing one for a player suppressed on its channel `marketing_suppressed`.
   */
  def judge(r: SendRequest, kind: String, p: ProfileState, config: CrmConfig): Option[(String, String)] =
    if r.trigger == Erasure then None
    else if p.erased then Some(Outcome.Discarded -> Erased)
    else if !config.configured then Some(Outcome.NoConfiguration -> "no_site")
    else if kind == Kind.Identify then None
    else
      val verdict = Suppression.of(p)
      val channel = if kind == Kind.Transactional then verdict.email else verdict.crm
      if p.status == "closed" then Some(Outcome.AccountClosed -> Suppression.AccountClosed)
      else if r.sendClass == SendClass.Marketing.wire then channel.map(Outcome.MarketingSuppressed -> _)
      else None

  enum Prepared:
    /** The call has nothing to do: no event to track, or no transactional id for a fact's trigger. */
    case Skip
    case Stop(outcome: String)
    case Make(attributes: Vector[(String, String)], transactional: Option[String], to: Option[String])

  /**
   * What `kind` sends (R5, FR-013; pure): the identify the full profile; the track the identify's attributes and the
   * event's data; a fact's transactional the track's data, a route's its own data and `brand_name`, to the request's
   * recipient or the player's address. A route's trigger with no id is `no_configuration`; no address `no_address`.
   */
  def prepare(r: SendRequest, kind: String, p: ProfileState, config: CrmConfig, brand: String, today: java.time.LocalDate): Prepared =
    lazy val identify = Attributes.of(p, brand, today)
    lazy val data     = r.data.flatMap(f => ProfileState.reveal(Some(f.value)).map(f.name -> _))
    kind match
      case Kind.Identify => Prepared.Make(identify, None, None)
      case Kind.Track    => r.event.fold(Prepared.Skip)(_ => Prepared.Make(identify ++ data, None, None))
      case Kind.Transactional =>
        val id = r.transactional.flatMap(t => config.transactional(t).orElse(Fallbacks.get(t)))
        val to = ProfileState.reveal(r.recipient).orElse(ProfileState.reveal(p.email))
        (id, to) match
          case (None, _) if r.routed => Prepared.Stop(Outcome.NoConfiguration)
          case (None, _)             => Prepared.Skip
          case (_, None)             => Prepared.Stop(Outcome.NoAddress)
          case (Some(_), Some(_)) =>
            val sent = if r.routed then data :+ ("brand_name" -> brand) else identify ++ data
            Prepared.Make(sent, id, to)
      case _ => Prepared.Make(Vector.empty, None, None)

  val call    = step("call")(_.callStep)
  val finish  = step("finish")(_.finishStep)
  val recover = step("recover")(_.recoverStep)

  val start   = command("start")(_.start)
  val retry   = command("retry")(_.retry)
  val discard = command("discard")(_.discard)
  val erase   = command("erase")(_.erase)
  val outcome = query("outcome")(_.outcome)

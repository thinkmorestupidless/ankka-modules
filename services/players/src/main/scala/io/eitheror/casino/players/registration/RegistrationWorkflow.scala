package io.eitheror.casino.players.registration

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.players.{Neighbours, PlayersCodecs, PlayersRuntime, Secrets}
import io.eitheror.casino.players.clients.{Neighbour, Raced, SendOutcome}
import io.eitheror.casino.players.config.BrandConfigEntity
import io.eitheror.casino.players.fraud.{FraudCaseEntity, OpenCase}
import io.eitheror.casino.players.player.*
import io.eitheror.casino.players.player.Done
import io.eitheror.casino.players.realm.Tokens

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.{Duration as JDuration, Instant}
import java.util.{HexFormat, Locale}
import scala.concurrent.duration.*

/**
 * What a registration presented, but its password (FR-001, FR-004): held by `PendingRegistration` while
 * the workflow runs and deleted when it ends, so the workflow's journal holds no personal value and no
 * table holds the password (SC-006).
 */
final case class RegistrationRequest(
    address: String,
    currency: String,
    profile: Profile,
    consents: Consents,
    registration: Registration,
    at: Instant
)

final case class PendingRequest(request: Option[RegistrationRequest] = None)

/** The request of one registration, keyed by the workflow's id; deleted by the workflow's `forget`. */
final class PendingRegistration(@scala.annotation.unused context: KeyValueEntityContext) extends KeyValueEntity[PendingRequest]:
  def emptyState: PendingRequest = PendingRequest()

  def put(r: RegistrationRequest): Effect[Done] = effects.updateState(PendingRequest(Some(r))).thenReply(_ => Done.changed)

  def forget: Effect[Done] =
    if currentState.request.isEmpty then effects.reply(Done.unchanged) else effects.deleteEntity().thenReply(_ => Done.changed)

  def get: ReadOnlyEffect[PendingRequest] = effects.reply(currentState)

object PendingRegistration
    extends KeyValueEntity.Companion[PendingRegistration, PendingRequest](
      componentId = ComponentId("pending-registration"),
      stateSerializer = PlayersCodecs.serializer[PendingRequest]("pending-registration")
    ):
  given Serializer[RegistrationRequest] = PlayersCodecs.serializer[RegistrationRequest]("registration-request")
  given Serializer[PendingRequest]      = PlayersCodecs.serializer[PendingRequest]("pending-registration-reply")
  given Serializer[Done]                = PlayersCodecs.serializer[Done]("done")

  def create(context: KeyValueEntityContext) = new PendingRegistration(context)

  val put    = command("put")(_.put)
  val forget = command("forget")(_.forget)
  val get    = query("get")(_.get)

/** How a registration ended for its caller: the player and its status, or the refusal (FR-001, FR-002). */
final case class Outcome(playerId: Option[String] = None, status: Option[String] = None, refusal: Option[String] = None)

/**
 * The workflow's state (data-model.md): the attempt, its step, the player id minted for it, the realm
 * user created for it, the outcome once the caller can be answered, the verification send's first
 * attempt and count, and the lookup tokens (digests, for US6). Nothing personal.
 */
final case class RegistrationFlow(
    attempt: Int = 0,
    step: String = RegistrationFlow.New,
    playerId: String = "",
    realmUserId: Option[String] = None,
    outcome: Option[Outcome] = None,
    firstSendAt: Option[Instant] = None,
    sends: Int = 0,
    lookupTokens: Vector[String] = Vector.empty
):
  def running: Boolean = step != RegistrationFlow.New && step != RegistrationFlow.Ended
  def registered: Boolean = outcome.exists(_.playerId.isDefined)

object RegistrationFlow:
  val New      = "new"
  val Realm    = "realm"
  val Player   = "player"
  val Match    = "match"
  val Email    = "email"
  val Undoing  = "undoing"
  val Ended    = "ended"

/** A start: the player id the caller minted, and when. */
final case class Begin(playerId: String, at: Instant)

/**
 * A start's answer: whether this caller holds the attempt (and so makes the realm call), and the flow.
 * A caller that does not hold it waits for the outcome, or is answered `refusal` at once.
 */
final case class Claim(claimed: Boolean, flow: RegistrationFlow, refusal: Option[String] = None)

/** What the realm answered the caller that holds the attempt. */
final case class RealmAnswer(attempt: Int, userId: Option[String] = None, refusal: Option[String] = None)

/** Whether the workflow took the realm's answer; a created user it did not take is the caller's to remove. */
final case class Taken(taken: Boolean)

/**
 * One registration (FR-001 to FR-005; research R3, amended at T034), keyed by the brand and the
 * address lower-cased, so two registrations of one address share it.
 *
 * `start` is the atomic claim: it mints the attempt and waits in `realm` for the caller's answer, or
 * answers a second caller that one is running. The caller holding the attempt makes the realm call,
 * because the password reaches no workflow state, and reports it with `realmAnswered`: a refusal ends
 * the attempt with it; a created user moves to `player`. `player` writes the player, retried twice and
 * then failed over to `undo-realm`, which removes the user, so no player exists without its realm user
 * and no realm user without its player (FR-002). An answer that never comes is undone after `realm`'s
 * pause. `match` flags a suspected duplicate (US6). `email` sends the verification link, retried for a
 * minute and then left pending on the player (R16). `forget` deletes the request and ends.
 */
final class RegistrationWorkflow(context: WorkflowContext) extends Workflow[RegistrationFlow]:
  import RegistrationFlow.*

  private val components = context.componentClient
  private def now()      = PlayersRuntime.now()
  private def live       = PlayersRuntime.settings
  private def neighbours = Neighbours(context.services, context.secrets)
  private def player     = components.forEventSourcedEntity(PlayerEntity.id(currentState.playerId))
  private def pending    = components.forKeyValueEntity(EntityId(context.workflowId.toString))

  def emptyState: RegistrationFlow = RegistrationFlow()

  override def settings: WorkflowSettings =
    WorkflowSettings.builder
      .defaultStepTimeout(30.seconds)
      .stepRecovery(RegistrationWorkflow.playerStep, RecoverStrategy.maxRetries(2).failoverTo(RegistrationWorkflow.undoRealm))
      .stepRecovery(RegistrationWorkflow.matchStep, RecoverStrategy.maxRetries(2).failoverTo(RegistrationWorkflow.emailStep))
      .stepTimeout(RegistrationWorkflow.emailStep, RegistrationWorkflow.EmailStepTimeout)
      .stepRecovery(RegistrationWorkflow.emailStep, RecoverStrategy.maxRetries(0).failoverTo(RegistrationWorkflow.emailUnanswered))
      .stepRecovery(RegistrationWorkflow.emailUnanswered, RecoverStrategy.maxRetries(3).failoverTo(RegistrationWorkflow.forget))
      .stepRecovery(RegistrationWorkflow.undoRealm, RecoverStrategy.maxRetries(3).failoverTo(RegistrationWorkflow.forget))
      .stepRecovery(RegistrationWorkflow.forget, RecoverStrategy.maxRetries(3))
      .build

  // ── Commands ─────────────────────────────────────────────────────────────────────────────────

  def start(b: Begin): Effect[Claim] =
    val s = currentState
    if s.running && s.outcome.isEmpty then effects.reply(Claim(claimed = false, s))
    // Registered and still sending the email: the realm holds the address, as a new attempt would hear.
    else if s.running then effects.reply(Claim(claimed = false, s, Some(RealmRefusalCodes.EmailExists)))
    else
      val next = RegistrationFlow(attempt = s.attempt + 1, step = Realm, playerId = b.playerId)
      effects.updateState(next).transitionTo(RegistrationWorkflow.awaitRealm).thenReply(Claim(claimed = true, next))

  def realmAnswered(a: RealmAnswer): Effect[Taken] =
    val s = currentState
    if a.attempt != s.attempt || s.step != Realm then effects.reply(Taken(false))
    else (a.userId, a.refusal) match
      case (Some(user), _) =>
        effects.updateState(s.copy(step = Player, realmUserId = Some(user))).transitionTo(RegistrationWorkflow.playerStep).thenReply(Taken(true))
      case (None, Some(code)) if RealmRefusalCodes.final_(code) =>
        effects.updateState(s.copy(outcome = Some(Outcome(refusal = Some(code))))).transitionTo(RegistrationWorkflow.forget).thenReply(Taken(true))
      case _ =>
        effects.updateState(s.copy(step = Undoing)).transitionTo(RegistrationWorkflow.undoRealm).thenReply(Taken(true))

  def status: ReadOnlyEffect[RegistrationFlow] = effects.reply(currentState)

  // ── Steps ────────────────────────────────────────────────────────────────────────────────────

  /** Waits for the caller's realm answer; none within the bound is a caller lost, undone. */
  def awaitRealmStep: StepEffect =
    val wait = live.registrationBound.toMillis.millis * 2
    stepEffects.thenPause(wait, RegistrationWorkflow.undoRealm)

  def playerStep: StepEffect =
    val r = pending.call(PendingRegistration.get).invoke().request
      .getOrElse(throw IllegalStateException(s"registration ${context.workflowId}: no request held"))
    val answer = RegistrationRuntime.write(components, Register(currentState.playerId, r.profile, r.address, r.currency, r.consents,
      r.registration, r.at))
    val tokens = neighbours.secret(Secrets.LookupKey).toOption.toVector.flatMap { key =>
      LookupTokens.identity(key, r.profile.get("firstName").getOrElse(""), r.profile.get("lastName").getOrElse(""),
        r.profile.get("dateOfBirth").getOrElse("")).map(LookupTokens.tagged(LookupTokens.Identity, _)).toVector ++
        r.registration.deviceFingerprint.flatMap(f => LookupTokens.device(key, f.reveal)).map(LookupTokens.tagged(LookupTokens.Device, _))
    }
    stepEffects.updateState(currentState.copy(step = Match, lookupTokens = tokens,
      outcome = Some(Outcome(Some(answer.playerId), Some(answer.status))))).thenTransitionTo(RegistrationWorkflow.matchStep)

  /**
   * Duplicate detection (FR-036, R9): each lookup token's index entry takes the player and answers the
   * others under it, so the match is two reads, never a scan. A match opens one case under the player's
   * id (a retried step opens the same one) and flags every player it links, the new one included.
   * Nothing is compared when the brand's `multiAccountDetection` is off or the registration has no token.
   */
  def matchStep: StepEffect =
    val s      = currentState
    val detect = components.forKeyValueEntity(EntityId(live.brand)).call(BrandConfigEntity.get).invoke().multiAccountDetection
    if detect && s.lookupTokens.nonEmpty then
      val found = s.lookupTokens.map(LookupTokens.split).map { (signal, token) =>
        signal -> components.forKeyValueEntity(EntityId(token)).call(LookupIndexEntity.add).invoke(LookupPlayer(s.playerId)).playerIds
      }.filter(_._2.nonEmpty)
      if found.nonEmpty then
        val players = found.flatMap(_._2).toSet + s.playerId
        val signals = found.map(_._1).toSet
        val caseId  = RegistrationWorkflow.caseOf(s.playerId)
        val at      = now()
        components.forEventSourcedEntity(EntityId(caseId)).call(FraudCaseEntity.open).invoke(OpenCase(players, signals, at)): Unit
        players.toVector.sorted.foreach(p =>
          components.forEventSourcedEntity(PlayerEntity.id(p)).call(PlayerEntity.flagDuplicate).invoke(FlagDuplicate(caseId, players, signals, at)): Unit)
    stepEffects.updateState(s.copy(step = Email)).thenTransitionTo(RegistrationWorkflow.emailStep)

  /**
   * One verification send, made on the step's thread (`Neighbour.inline`), so the step's timeout is its
   * bound: a send `engagement` does not answer fails the step over to `email-unanswered`.
   */
  def emailStep: StepEffect =
    val s       = currentState
    val at      = now()
    val first   = s.firstSendAt.getOrElse(at)
    val request = pending.call(PendingRegistration.get).invoke().request
    request match
      case None => stepEffects.updateState(s.copy(step = Ended)).thenTransitionTo(RegistrationWorkflow.forget)
      case Some(r) =>
        val sent = Neighbour.inline(RegistrationWorkflow.sendVerification(neighbours, s.playerId, r.address, s"registration:${s.playerId}", at))
        sent match
          case Some(SendOutcome.Accepted) =>
            player.call(PlayerEntity.verificationSent).invoke(At(at)): Unit
            stepEffects.updateState(s.copy(firstSendAt = Some(first), sends = s.sends + 1)).thenTransitionTo(RegistrationWorkflow.forget)
          case Some(other) =>
            RegistrationWorkflow.log.info(s"registration ${s.playerId}: the verification email was not sent ($other)")
            player.call(PlayerEntity.verificationPending).invoke(At(at)): Unit
            stepEffects.updateState(s.copy(firstSendAt = Some(first), sends = s.sends + 1)).thenTransitionTo(RegistrationWorkflow.forget)
          case None => unanswered()

  def emailUnansweredStep: StepEffect = unanswered()

  /**
   * A send not taken (R16): the player is recorded pending at the first, and the send is tried again
   * with backoff until a minute from the first attempt, then left pending.
   */
  private def unanswered(): StepEffect =
    val s     = currentState
    val at    = now()
    val first = s.firstSendAt.getOrElse(at)
    if s.firstSendAt.isEmpty then player.call(PlayerEntity.verificationPending).invoke(At(at)): Unit
    val next = s.copy(firstSendAt = Some(first), sends = s.sends + 1)
    if !at.isBefore(first.plus(RegistrationWorkflow.RetryWindow)) then
      RegistrationWorkflow.log.warn(s"registration ${s.playerId}: engagement did not take the verification email within a minute; left pending")
      stepEffects.updateState(next).thenTransitionTo(RegistrationWorkflow.forget)
    else stepEffects.updateState(next).thenPause(RegistrationWorkflow.backoff(next.sends), RegistrationWorkflow.emailStep)

  /** Removes the realm user made for the attempt (FR-002), found by the player id when the caller never said. */
  def undoRealmStep: StepEffect =
    val s     = currentState
    val realm = neighbours.realm
    val user  = s.realmUserId.orElse(realm.userOf(s.playerId).toOption)
    user.foreach { u =>
      realm.deleteUser(u).left.foreach(r => throw IllegalStateException(s"registration ${s.playerId}: the realm user was not removed: ${r.code}"))
    }
    stepEffects.updateState(s.copy(step = Ended, outcome = Some(Outcome(refusal = Some("service_unavailable")))))
      .thenTransitionTo(RegistrationWorkflow.forget)

  def forgetStep: StepEffect =
    pending.call(PendingRegistration.forget).invoke(): Unit
    stepEffects.updateState(currentState.copy(step = Ended)).thenEnd

/**
 * The `player` step's write, as merchant's `DepositRuntime` holds its seams: a suite replaces it to
 * fail the write and see the realm user removed (FR-002), and `reset` puts it back.
 */
object RegistrationRuntime:
  val entity: (ComponentClient, Register) => RegisterAnswer =
    (c, r) => c.forEventSourcedEntity(PlayerEntity.id(r.playerId)).call(PlayerEntity.register).invoke(r)

  @volatile var write: (ComponentClient, Register) => RegisterAnswer = entity

  def reset(): Unit = write = entity

object RegistrationWorkflow
    extends Workflow.Companion[RegistrationWorkflow, RegistrationFlow](
      componentId = ComponentId("registration"),
      stateSerializer = PlayersCodecs.serializer[RegistrationFlow]("registration-flow")
    ):
  private val log = org.slf4j.LoggerFactory.getLogger(classOf[RegistrationWorkflow])

  given Serializer[Begin]       = PlayersCodecs.serializer[Begin]("registration-begin")
  given Serializer[Claim]       = PlayersCodecs.serializer[Claim]("registration-claim")
  given Serializer[RealmAnswer] = PlayersCodecs.serializer[RealmAnswer]("registration-realm-answer")
  given Serializer[Taken]       = PlayersCodecs.serializer[Taken]("registration-taken")

  def create(context: WorkflowContext) = new RegistrationWorkflow(context)

  /** The engagement send's bound and how long a send that is not taken is retried (R16). */
  val SendBound: JDuration   = JDuration.ofSeconds(2)
  val RetryWindow: JDuration = JDuration.ofMinutes(1)

  /** The email step's bound: the send's, and the reads around it. */
  val EmailStepTimeout: FiniteDuration = 5.seconds

  /** 1 s doubling to 10 s between sends. */
  def backoff(sends: Int): FiniteDuration = math.min(10_000L, 1000L << math.min(sends - 1, 10).max(0)).millis

  /** The workflow's id: the brand and the address, lower-cased, digested (R3), so the id names no address. */
  def idOf(slug: String, address: String): EntityId =
    val digest = MessageDigest.getInstance("SHA-256").digest(s"$slug:${address.trim.toLowerCase(Locale.ROOT)}".getBytes(UTF_8))
    EntityId(HexFormat.of().formatHex(digest))

  /** The verification link for a player and address (FR-005): the player site's page with a token valid `verificationValidity`. */
  def link(neighbours: Neighbours, playerId: String, address: String, at: Instant): Either[String, String] =
    val s = PlayersRuntime.settings
    neighbours.secret(Secrets.EmailToken).map { secret =>
      val token = Tokens.mint(secret, Tokens.Claims(s.brand, playerId, address.trim.toLowerCase(Locale.ROOT),
        at.plus(s.verificationValidity), Tokens.Purpose.EmailVerify))
      s"${s.playerSiteBase}/verify-email?token=${java.net.URLEncoder.encode(token, UTF_8)}"
    }

  /**
   * One verification send through `engagement` under `key` (R16): what it answered, or `None` when it
   * did not take it within the bound or the link could not be signed.
   */
  def sendVerification(neighbours: Neighbours, playerId: String, address: String, key: String, at: Instant): Option[SendOutcome] =
    link(neighbours, playerId, address, at) match
      case Left(why) =>
        log.warn(s"verification email for $playerId: the link could not be signed: $why")
        None
      case Right(l) =>
        neighbours.engagement.send(key, playerId, "EmailAddressVerification", address, l, SendBound) match
          case Raced.Answered(o) => Some(o)
          case Raced.Unanswered(_) =>
            log.warn(s"verification email for $playerId: engagement did not answer within $SendBound")
            None
          case Raced.Failed(e) =>
            log.warn(s"verification email for $playerId: engagement failed: ${e.getClass.getSimpleName}: ${Option(e.getMessage).getOrElse("").take(200)}")
            None

  val awaitRealm = step("realm")(_.awaitRealmStep)
  val playerStep = step("player")(_.playerStep)
  val matchStep  = step("match")(_.matchStep)

  /** The case a registration's match opens: one per new player, so a retry opens the same one. */
  def caseOf(playerId: String): String = s"dup-$playerId"
  val emailStep  = step("email")(_.emailStep)
  val emailUnanswered = step("email-unanswered")(_.emailUnansweredStep)
  val undoRealm  = step("undo-realm")(_.undoRealmStep)
  val forget     = step("forget")(_.forgetStep)

  val start         = command("start")(_.start)
  val realmAnswered = command("realm-answered")(_.realmAnswered)
  val status        = query("status")(_.status)

/** The realm's refusals that end a registration as the realm said (FR-002); anything else is undone. */
object RealmRefusalCodes:
  val EmailExists = "email_already_exists"
  val final_ : Set[String] = Set(EmailExists, "password_too_weak", "brand_not_found")

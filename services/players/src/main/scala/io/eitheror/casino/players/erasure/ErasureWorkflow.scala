package io.eitheror.casino.players.erasure

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.players.{Json, Neighbours, PlayersCodecs, PlayersRuntime, Secrets}
import io.eitheror.casino.players.clients.{Neighbour, Raced, Target}
import io.eitheror.casino.players.config.BrandConfigEntity
import io.eitheror.casino.players.fraud.{EraseNotes, FraudCaseEntity, FraudCasesView}
import io.eitheror.casino.players.inbox.{MessageAt, MessageEntity, MessagesView, NoteEntity, NotesView}
import io.eitheror.casino.players.player.*
import io.eitheror.casino.players.realm.RealmRefusal
import io.eitheror.casino.players.registration.{LookupIndexEntity, LookupPlayer, LookupTokens}

import java.time.{Duration as JDuration, Instant}
import java.util.UUID
import scala.concurrent.duration.*

/** One service's answer to the erasure: its certificate, as it answered (contracts/routes.md). */
final case class Certificate(service: String, appliedAt: Instant, certificate: String)

/**
 * An erasure (FR-038, FR-039; research R10), keyed by the player: the correlation id every part files
 * under, the hold, why it is held, each service's certificate, the service that failed it, and the lookup
 * tokens (digests) its own part removes. Nothing personal.
 */
final case class ErasureFlow(
    playerId: String = "",
    correlationId: String = "",
    holdUntil: Option[Instant] = None,
    heldFor: Option[String] = None,
    step: String = ErasureFlow.New,
    certificates: Vector[Certificate] = Vector.empty,
    failedAt: Option[String] = None,
    tokens: Vector[String] = Vector.empty
):
  def filed: Boolean = step != ErasureFlow.New

  /** The wire's `held` | `applying` | `erased` | `failed`. */
  def status: String = step match
    case ErasureFlow.Hold   => "held"
    case ErasureFlow.Erased => "erased"
    case ErasureFlow.Failed => "failed"
    case _                  => "applying"

object ErasureFlow:
  val New     = "new"
  val Hold    = "hold"
  val Applying = "apply"
  val Realm   = "realm"
  val Erased  = "erased"
  val Failed  = "failed"

  /** Why the erasure is not yet applied: the retention period, or an open withdrawal (FR-039). */
  val Retention      = "retention"
  val OpenWithdrawal = "open_withdrawal"

final case class FileRequest(playerId: String, at: Instant)

/** The erasure as filed, or the refusal (`not_closed`, `player_not_found`). */
final case class Filed(flow: ErasureFlow, refusal: Option[String] = None)

/** Where a part of the erasure is applied: the target, and the subject it knows the player by. */
final case class ErasureTarget(name: String, target: Target, subject: String => String)

/**
 * `file` (the command) sets the player `erasure_pending` under one correlation id with the brand's hold,
 * from the closure: `retentionDays`, else [[ErasureWorkflow.DefaultRetentionDays]]. `hold` looks again
 * every `ERASURE_HOLD_POLL` until the hold has passed and no withdrawal is open. `apply` asks one target
 * a time and keeps its certificate, so a target that does not answer is retried and then named in
 * `failed`, never skipped; this service's own part is last (notes, messages, the notes of the fraud cases
 * that link the player, the lookup entries, then the entity's `Erase`). `realm` removes the user; `done` ends.
 */
final class ErasureWorkflow(context: WorkflowContext) extends Workflow[ErasureFlow]:
  import ErasureFlow.*

  private val components = context.componentClient
  private def now()      = PlayersRuntime.now()
  private def live       = PlayersRuntime.settings
  private def neighbours = Neighbours(context.services, context.secrets)
  private def player     = components.forEventSourcedEntity(PlayerEntity.id(currentState.playerId))

  def emptyState: ErasureFlow = ErasureFlow()

  override def settings: WorkflowSettings =
    WorkflowSettings.builder
      .defaultStepTimeout(30.seconds)
      .stepTimeout(ErasureWorkflow.applyStep, live.exportPartBound.toMillis.millis + 1.second)
      .stepRecovery(ErasureWorkflow.applyStep, RecoverStrategy.maxRetries(3).failoverTo(ErasureWorkflow.failedStep))
      .stepRecovery(ErasureWorkflow.realmStep, RecoverStrategy.maxRetries(3).failoverTo(ErasureWorkflow.failedStep))
      .build

  // ── Commands ─────────────────────────────────────────────────────────────────────────────────

  def file(r: FileRequest): Effect[Filed] =
    val s = currentState
    if s.filed then effects.reply(Filed(s))
    else
      val p = components.forEventSourcedEntity(PlayerEntity.id(r.playerId)).call(PlayerEntity.get).invoke()
      if !p.exists then effects.reply(Filed(s, Some("player_not_found")))
      else if p.status != Status.Closed then effects.reply(Filed(s, Some("not_closed")))
      else
        val days  = components.forKeyValueEntity(EntityId(live.brand)).call(BrandConfigEntity.get).invoke().retentionDays
          .getOrElse(ErasureWorkflow.DefaultRetentionDays)
        val hold  = p.closedAt.getOrElse(r.at).plus(JDuration.ofDays(days.toLong))
        val id    = UUID.randomUUID().toString
        val d     = components.forEventSourcedEntity(PlayerEntity.id(r.playerId)).call(PlayerEntity.fileErasure).invoke(FileErasure(id, hold, r.at))
        d.refusal match
          case Some(refused) => effects.reply(Filed(s, Some(refused.code)))
          case None =>
            val next = ErasureFlow(r.playerId, id, Some(hold), Some(Retention), Hold, tokens = ErasureWorkflow.tokensOf(neighbours, p))
            effects.updateState(next).transitionTo(ErasureWorkflow.holdStep).thenReply(Filed(next))

  def status: ReadOnlyEffect[ErasureFlow] = effects.reply(currentState)

  // ── Steps ────────────────────────────────────────────────────────────────────────────────────

  def holdStep: StepEffect =
    val s          = currentState
    val withdrawal = player.call(PlayerEntity.get).invoke().withdrawalOpen
    val lapsed     = s.holdUntil.forall(h => !now().isBefore(h))
    if lapsed && !withdrawal then stepEffects.updateState(s.copy(step = Applying, heldFor = None)).thenTransitionTo(ErasureWorkflow.applyStep)
    else
      val why = if withdrawal then OpenWithdrawal else Retention
      stepEffects.updateState(s.copy(heldFor = Some(why))).thenPause(live.erasureHoldPoll.toMillis.millis, ErasureWorkflow.holdStep)

  /** The next target without a certificate; this service's own part once every other has answered. */
  def applyStep: StepEffect =
    val s    = currentState
    val done = s.certificates.map(_.service).toSet
    ErasureWorkflow.targets(live.brand).find(t => !done(t.name)) match
      case Some(t) =>
        val answer = Neighbour.inline(neighbours.erasure(t.target, t.subject(s.playerId), live.exportPartBound))
        answer match
          case Raced.Answered(body) =>
            val next = s.copy(certificates = s.certificates :+ Certificate(t.name, now(), Json.render(body)))
            stepEffects.updateState(next).thenTransitionTo(ErasureWorkflow.applyStep)
          case Raced.Unanswered(_) => throw IllegalStateException(s"erasure of ${s.playerId}: ${t.name} did not answer")
          case Raced.Failed(e)     => throw IllegalStateException(s"erasure of ${s.playerId}: ${t.name} failed: ${e.getMessage}")
      case None =>
        val at = now()
        ErasureWorkflow.eraseOwn(components, s, at)
        val own = Certificate(ErasureWorkflow.Own, at, Json.render(Json.obj("subject" -> s"player/${s.playerId}", "confirmed" -> true)))
        stepEffects.updateState(s.copy(step = Realm, certificates = s.certificates :+ own)).thenTransitionTo(ErasureWorkflow.realmStep)

  def realmStep: StepEffect =
    val s     = currentState
    val realm = neighbours.realm
    realm.userOf(s.playerId) match
      case Left(RealmRefusal.UserNotFound) => ()
      case Left(r)                         => throw IllegalStateException(s"erasure of ${s.playerId}: the realm did not answer: ${r.code}")
      case Right(user) =>
        realm.deleteUser(user).left.foreach(r => throw IllegalStateException(s"erasure of ${s.playerId}: the realm user was not removed: ${r.code}"))
    stepEffects.updateState(s.copy(step = Erased)).thenEnd

  /** The part a retried step did not apply: named, and the erasure left for a person (FR-039). */
  def failedStep: StepEffect =
    val s    = currentState
    val done = s.certificates.map(_.service).toSet
    val at   = if s.step == Realm then "realm" else ErasureWorkflow.targets(live.brand).find(t => !done(t.name)).fold(ErasureWorkflow.Own)(_.name)
    ErasureWorkflow.log.error("erasure of {}: {} did not apply it; the erasure is left failed", s.playerId, at)
    stepEffects.updateState(s.copy(step = Failed, failedAt = Some(at))).thenEnd

object ErasureWorkflow
    extends Workflow.Companion[ErasureWorkflow, ErasureFlow](
      componentId = ComponentId("erasure"),
      stateSerializer = PlayersCodecs.serializer[ErasureFlow]("erasure-flow")
    ):
  private val log = org.slf4j.LoggerFactory.getLogger(classOf[ErasureWorkflow])

  given Serializer[FileRequest] = PlayersCodecs.serializer[FileRequest]("erasure-file")
  given Serializer[Filed]       = PlayersCodecs.serializer[Filed]("erasure-filed")
  given Serializer[ErasureFlow] = PlayersCodecs.serializer[ErasureFlow]("erasure-reply")

  def create(context: WorkflowContext) = new ErasureWorkflow(context)

  /** The hold a closure starts when the brand names no `retentionDays`: 5 years. */
  val DefaultRetentionDays: Int = 1825

  /** This service's own certificate's name. */
  val Own = "players"

  /**
   * Every part of the erasure but this service's own, in order (R10): the brand's `wallet` and
   * `responsible-gambling` (spec 010 R15), and `merchant` in `payments` under the brand's erasure grant,
   * which knows the player as `<brand>:<player>`; `kyc` (spec 009 R15) deletes the documents' objects too.
   * A later service is one entry.
   */
  def targets(brand: String): Vector[ErasureTarget] = Vector(
    ErasureTarget("wallet", Target("wallet", s"/v1/casino/brands/$brand"), identity),
    ErasureTarget("responsible-gambling", Target("responsible-gambling", s"/v1/casino/brands/$brand/rg"), identity),
    ErasureTarget("kyc", Target("kyc", s"/v1/casino/brands/$brand/kyc"), identity),
    ErasureTarget("merchant", Target("merchant", "", Some("payments")), p => s"$brand:$p")
  )

  def id(playerId: String): EntityId = EntityId(s"erasure-$playerId")

  def file(components: ComponentClient, playerId: String): Filed =
    components.forWorkflow(id(playerId)).call(fileCommand).invoke(FileRequest(playerId, PlayersRuntime.now()))

  def stateOf(components: ComponentClient, playerId: String): ErasureFlow =
    components.forWorkflow(id(playerId)).call(status).invoke()

  /** The player's lookup tokens, read before the profile is erased. */
  def tokensOf(neighbours: Neighbours, p: PlayerState): Vector[String] =
    neighbours.secret(Secrets.LookupKey).toOption.toVector.flatMap { key =>
      LookupTokens.identity(key, p.profile.get("firstName").getOrElse(""), p.profile.get("lastName").getOrElse(""),
        p.profile.get("dateOfBirth").getOrElse("")).toVector ++ p.registration.deviceFingerprint.flatMap(f => LookupTokens.device(key, f.reveal))
    }

  /**
   * This service's part: the notes' and messages' text, the dismissal notes of every case linking the
   * player (they may name the other player too, and are blanked whole), the lookup entries, then the
   * entity's `Erase`.
   */
  def eraseOwn(components: ComponentClient, s: ErasureFlow, at: Instant): Unit =
    NotesView.ofPlayer(PlayersRuntime.views(), s.playerId).foreach(n =>
      components.forKeyValueEntity(EntityId(n.noteId)).call(NoteEntity.erase).invoke(): Unit)
    MessagesView.ofPlayer(PlayersRuntime.views(), s.playerId, 0, 100_000, includeDeleted = true).foreach(m =>
      components.forEventSourcedEntity(EntityId(m.messageId)).call(MessageEntity.erase).invoke(MessageAt(at)): Unit)
    FraudCasesView.ofPlayer(PlayersRuntime.views(), s.playerId).foreach(c =>
      components.forEventSourcedEntity(EntityId(c.caseId)).call(FraudCaseEntity.eraseNotes).invoke(EraseNotes(at)): Unit)
    s.tokens.foreach(t => components.forKeyValueEntity(EntityId(t)).call(LookupIndexEntity.remove).invoke(LookupPlayer(s.playerId)): Unit)
    components.forEventSourcedEntity(PlayerEntity.id(s.playerId)).call(PlayerEntity.erase).invoke(At(at)): Unit

  val holdStep   = step("hold")(_.holdStep)
  val applyStep  = step("apply")(_.applyStep)
  val realmStep  = step("realm")(_.realmStep)
  val failedStep = step("failed")(_.failedStep)

  val fileCommand = command("file")(_.file)
  val status      = query("status")(_.status)

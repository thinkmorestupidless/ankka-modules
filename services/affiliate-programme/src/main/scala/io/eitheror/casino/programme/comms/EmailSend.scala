package io.eitheror.casino.programme.comms

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.programme.{ProgrammeCodecs, Wiring}
import io.eitheror.casino.programme.accounts.{Affiliate, Secrets}
import io.eitheror.casino.programme.brands.BrandProgramme
import io.eitheror.casino.programme.sealing.Sealing

import java.time.{Clock, Instant}
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.*
import scala.util.control.NonFatal

/**
 * One email to an affiliate (FR-035), id the change it tells of. It holds the affiliate's id, never the
 * address: the step opens the sealed identity for the address and name, so nothing readable is kept
 * (SC-007). `data` is what the message needs besides them.
 */
final case class EmailState(
    affiliate: String = "",
    brand: String = "",
    kind: String = "",
    data: Map[String, String] = Map.empty,
    attempts: Int = 0,
    lastStatus: Option[Int] = None,
    lastError: Option[String] = None,
    status: String = EmailRules.New,
    finishedAt: Option[Instant] = None
)

final case class SendEmail(affiliate: String, brand: String, kind: String, data: Map[String, String])

/** Up to three attempts over five minutes: the second a minute after the first, the third four minutes later. */
object EmailRules:
  val New     = "new"
  val Sending = "sending"
  val Sent    = "sent"
  val Failed  = "failed"
  /** Nothing to send with: no message for the kind at the brand, no key, or the affiliate erased. */
  val Skipped = "skipped"

  val Delays: Vector[FiniteDuration] = Vector(1.minute, 4.minutes)

  sealed trait Next
  object Next:
    case object Done                             extends Next
    final case class Retry(after: FiniteDuration) extends Next

  def attempted(s: EmailState, now: Instant, result: Either[String, Int]): (EmailState, Next) =
    val counted = s.copy(attempts = s.attempts + 1, lastStatus = result.toOption.orElse(s.lastStatus), lastError = result.left.toOption)
    result match
      case Right(code) if code / 100 == 2 => (counted.copy(status = Sent, lastError = None, finishedAt = Some(now)), Next.Done)
      case _ if counted.attempts > Delays.size => (counted.copy(status = Failed, finishedAt = Some(now)), Next.Done)
      case _                                   => (counted.copy(status = Sending), Next.Retry(Delays(counted.attempts - 1)))

final class EmailSend(context: WorkflowContext) extends Workflow[EmailState]:
  def emptyState: EmailState = EmailState()

  /** A no-op on one already started: a redelivered change starts the same send. */
  def start(c: SendEmail): Effect[EmailState] =
    if currentState.status != EmailRules.New then effects.reply(currentState)
    else
      effects.updateState(EmailState(c.affiliate, c.brand, c.kind, c.data, status = EmailRules.Sending))
        .transitionTo(EmailSend.send.ref).thenReplyState

  def sendStep: StepEffect =
    val s   = currentState
    val now = EmailSend.clock.get().instant()
    EmailSend.resolve(context, s) match
      case Left(why) => stepEffects.updateState(s.copy(status = EmailRules.Skipped, lastError = Some(why), finishedAt = Some(now))).thenEnd
      case Right(message) =>
        val result =
          try EmailSend.customerIo.get().send(message.apiKey, message.region, message.transactionalId, message.to, message.data)
          catch case NonFatal(e) => Left(Option(e.getMessage).getOrElse(e.getClass.getSimpleName))
        val (next, decision) = EmailRules.attempted(s, now, result)
        decision match
          case EmailRules.Next.Done         => stepEffects.updateState(next).thenEnd
          case EmailRules.Next.Retry(after) => stepEffects.updateState(next).thenPause(after, EmailSend.send.ref)

  def status: ReadOnlyEffect[EmailState] = effects.reply(currentState)

object EmailSend extends Workflow.Companion[EmailSend, EmailState](
      componentId = ComponentId("email-send"),
      stateSerializer = ProgrammeCodecs.serializer[EmailState]("email-send")
    ):
  /** How a step sends and tells the time: set by `ProgrammeService`; fakes in the suites. */
  val customerIo: AtomicReference[CustomerIo] = AtomicReference(CustomerIo.http(java.time.Duration.ofSeconds(10)))
  val clock: AtomicReference[Clock]           = AtomicReference(Clock.systemUTC())

  /** What one send needs, read in the step and never stored. */
  final case class Message(apiKey: String, region: String, transactionalId: String, to: String, data: Map[String, String])

  def resolve(context: WorkflowContext, s: EmailState): Either[String, Message] =
    val brand = context.componentClient.forKeyValueEntity(EntityId(s.brand)).call(BrandProgramme.get).invoke()
    val account = context.componentClient.forEventSourcedEntity(EntityId(s.affiliate)).call(Affiliate.get).invoke()
    val secrets = Secrets(context.secrets)
    for
      tid      <- brand.sender.messages.get(s.kind).toRight(s"no message for ${s.kind} at ${s.brand}")
      apiKey   <- secrets.brandSecret(s.brand, Secrets.CustomerIoKey).left.map(_.reason).flatMap(_.toRight("no Customer.io key"))
      key      <- secrets.existingDataKey(s.affiliate).left.map(_.reason).flatMap(_.toRight("erased"))
      identity <- Sealing.open(account.identity, key, s.affiliate)
    yield
      val region = brand.sender.region.getOrElse(Wiring.settings.get().customerIoRegion)
      val named  = Map("name" -> identity.name) ++ identity.companyName.map("company_name" -> _)
      Message(apiKey, region, tid, identity.email, named ++ s.data)

  given Serializer[SendEmail] = ProgrammeCodecs.serializer[SendEmail]("send-email")

  def create(context: WorkflowContext) = new EmailSend(context)

  val send = step("send")(_.sendStep)

  val start  = command("start")(_.start)
  val status = query("status")(_.status)

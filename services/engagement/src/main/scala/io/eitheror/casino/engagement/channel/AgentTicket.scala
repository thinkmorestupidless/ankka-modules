package io.eitheror.casino.engagement.channel

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.thinkmorestupidless.ankka.core.{ComponentId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.engagement.EngagementCodecs

import java.security.SecureRandom
import java.time.{Duration, Instant}
import java.util.Base64
import scala.concurrent.duration.{DurationInt, FiniteDuration}

/** A ticket as issued: the brand and operator it was issued to, when, and when it was spent. */
final case class AgentTicketState(brand: String = "", operator: String = "", issuedAt: Option[Instant] = None,
    usedAt: Option[Instant] = None)

/** Why a redemption was refused. The route answers each as `invalid_ticket`; the reason is for the log. */
enum TicketRefusal(val wire: String):
  case Unknown      extends TicketRefusal("unknown")
  case Used         extends TicketRefusal("used")
  case AnotherBrand extends TicketRefusal("another_brand")
  case Expired      extends TicketRefusal("expired")

object TicketRefusal:
  given JsonValueCodec[TicketRefusal] = EngagementCodecs.wire(values, _.wire)

/** The commands' payloads. Every time is the caller's: the entity reads no clock. */
object AgentTicketCommand:
  final case class Issue(brand: String, operator: String, now: Instant)
  final case class Redeem(brand: String, now: Instant)

/** An issue's answer: whether this call issued the ticket (an id already issued keeps its first issue). */
final case class Issued(issued: Boolean, expiresAt: Instant)

/** A redemption's answer: the operator the ticket was issued to, or why it was refused. */
final case class Redemption(operator: Option[String] = None, refused: Option[TicketRefusal] = None):
  def ok: Boolean = refused.isEmpty

/**
 * The single-use ticket that opens the agent stream (key value, id = ticket; contracts/routes.md): `EventSource`
 * sends no `Authorization` header, so the operator's bearer buys a ticket for the query string instead. A ticket
 * is redeemed once, within 30 s of its issue, for the brand it was issued for. Any redemption spends it, a refused
 * one too: a ticket presented for another brand is a bug or a probe, and stays unusable for the right one, as the
 * original's `validateAndConsume`.
 */
final class AgentTicket(@scala.annotation.unused context: KeyValueEntityContext) extends KeyValueEntity[AgentTicketState]:
  import AgentTicketCommand.*

  def emptyState: AgentTicketState = AgentTicketState()

  def issue(c: Issue): Effect[Issued] =
    currentState.issuedAt match
      case Some(at) => effects.reply(Issued(issued = false, at.plus(AgentTicket.Ttl)))
      case None =>
        effects.updateState(AgentTicketState(c.brand, c.operator, Some(c.now))).expireAfter(AgentTicket.Kept)
          .thenReply(_ => Issued(issued = true, c.now.plus(AgentTicket.Ttl)))

  def redeem(c: Redeem): Effect[Redemption] =
    val s = currentState
    s.issuedAt match
      case None                          => effects.reply(Redemption(refused = Some(TicketRefusal.Unknown)))
      case Some(_) if s.usedAt.isDefined => effects.reply(Redemption(refused = Some(TicketRefusal.Used)))
      case Some(at) =>
        val answer =
          if s.brand != c.brand then Redemption(refused = Some(TicketRefusal.AnotherBrand))
          else if !c.now.isBefore(at.plus(AgentTicket.Ttl)) then Redemption(refused = Some(TicketRefusal.Expired))
          else Redemption(operator = Some(s.operator))
        effects.updateState(s.copy(usedAt = Some(c.now))).expireAfter(AgentTicket.Kept).thenReply(_ => answer)

object AgentTicket
    extends KeyValueEntity.Companion[AgentTicket, AgentTicketState](
      componentId = ComponentId("agent-ticket"),
      stateSerializer = EngagementCodecs.serializer[AgentTicketState]("agent-ticket-state")
    ):
  import AgentTicketCommand.*

  /** How long a ticket may be redeemed after its issue: the `expires_in` of the ticket route. */
  val Ttl: Duration = Duration.ofSeconds(30)

  /** How long a ticket is kept after its last change, so a late repeat is refused `used` or `expired`, not `unknown`. */
  val Kept: FiniteDuration = 10.minutes

  given Serializer[Issue]      = EngagementCodecs.serializer[Issue]("agent-ticket-issue")
  given Serializer[Redeem]     = EngagementCodecs.serializer[Redeem]("agent-ticket-redeem")
  given Serializer[Issued]     = EngagementCodecs.serializer[Issued]("agent-ticket-issued")
  given Serializer[Redemption] = EngagementCodecs.serializer[Redemption]("agent-ticket-redemption")

  def create(context: KeyValueEntityContext) = new AgentTicket(context)

  val issue  = command("issue")(_.issue)
  val redeem = command("redeem")(_.redeem)

  private val random = SecureRandom()

  /** A new ticket: 32 random bytes, base64url without padding, as the original mints. */
  def newTicket(): String =
    val bytes = new Array[Byte](32)
    random.nextBytes(bytes)
    Base64.getUrlEncoder.withoutPadding().encodeToString(bytes)

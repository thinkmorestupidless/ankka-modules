package io.eitheror.casino.programme.accounts

import com.thinkmorestupidless.ankka.core.{ComponentId, ErrorCode, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.programme.ProgrammeCodecs
import io.eitheror.casino.programme.sealing.Sealed

import java.security.SecureRandom
import java.time.Instant

/** The account's fields that are not personal (FR-001, FR-034), each changed only when named. */
final case class AccountFields(
    minimumPayout: Option[Map[String, Long]] = None,
    postbackUrl: Option[String] = None,
    keycloakUser: Option[String] = None,
    termsVersion: Option[String] = None,
    termsAcceptedAt: Option[Instant] = None,
    managerId: Option[String] = None,
    group: Option[String] = None,
    tags: Option[Vector[String]] = None
)

/**
 * An affiliate's account (data-model.md *Affiliate*): its identity sealed, the email's digest, the
 * network, and the fields that are not personal. Closed accounts sign in no more; an erased one changes
 * no more.
 */
final case class AffiliateState(
    id: String = "",
    identity: Sealed = Sealed.erased,
    emailDigest: String = "",
    parent: Option[String] = None,
    overrideBp: Int = 0,
    minimumPayout: Map[String, Long] = Map.empty,
    postbackUrl: Option[String] = None,
    keycloakUser: Option[String] = None,
    termsVersion: Option[String] = None,
    termsAcceptedAt: Option[Instant] = None,
    managerId: Option[String] = None,
    group: Option[String] = None,
    tags: Vector[String] = Vector.empty,
    firstLoginAt: Option[Instant] = None,
    lastLoginAt: Option[Instant] = None,
    createdAt: Option[Instant] = None,
    closed: Option[Instant] = None,
    erased: Option[Instant] = None
):
  def exists: Boolean = id.nonEmpty

  /** The minimum payout in a currency: none set is no minimum (FR-018). */
  def minimumIn(currency: String): Long = minimumPayout.getOrElse(currency, 0L)

final case class CreateAffiliate(id: String, identity: Sealed, emailDigest: String, parent: Option[String], overrideBp: Int,
    fields: AccountFields, by: String, at: Instant)
final case class UpdateIdentity(identity: Sealed, emailDigest: String, by: String, at: Instant)
final case class UpdateAccount(fields: AccountFields, by: String, at: Instant)
final case class SetParent(parent: Option[String], by: String, at: Instant)
final case class SetOverride(bp: Int, by: String, at: Instant)
final case class Login(first: Boolean, at: Instant)
final case class CloseAccount(by: String, at: Instant)

object Affiliate extends EventSourcedEntity.Companion[Affiliate, AffiliateState, Affiliate.Event](
      componentId = ComponentId("affiliate"),
      stateSerializer = AffiliateCodecs.state,
      eventSerializer = AffiliateCodecs.event
    ):

  sealed trait Event
  final case class Created(identity: Sealed, emailDigest: String, parent: Option[String], overrideBp: Int, fields: AccountFields,
      by: String, at: Instant) extends Event
  final case class IdentityUpdated(identity: Sealed, emailDigest: String, by: String, at: Instant) extends Event
  final case class Updated(fields: AccountFields, by: String, at: Instant) extends Event
  final case class ParentSet(parent: Option[String], by: String, at: Instant) extends Event
  final case class OverrideSet(bp: Int, by: String, at: Instant) extends Event
  final case class LoggedIn(first: Boolean, at: Instant) extends Event
  final case class Closed(by: String, at: Instant) extends Event
  final case class Erased(at: Instant) extends Event

  def applyFields(s: AffiliateState, f: AccountFields): AffiliateState =
    s.copy(
      minimumPayout = f.minimumPayout.getOrElse(s.minimumPayout),
      postbackUrl = f.postbackUrl.orElse(s.postbackUrl).filter(_.nonEmpty),
      keycloakUser = f.keycloakUser.orElse(s.keycloakUser),
      termsVersion = f.termsVersion.orElse(s.termsVersion),
      termsAcceptedAt = f.termsAcceptedAt.orElse(s.termsAcceptedAt),
      managerId = f.managerId.orElse(s.managerId).filter(_.nonEmpty),
      group = f.group.orElse(s.group).filter(_.nonEmpty),
      tags = f.tags.getOrElse(s.tags)
    )

  def next(id: String, s: AffiliateState, e: Event): AffiliateState = e match
    case Created(identity, digest, parent, bp, fields, _, at) =>
      applyFields(AffiliateState(id, identity, digest, parent, bp, createdAt = Some(at)), fields)
    case IdentityUpdated(identity, digest, _, _) => s.copy(identity = identity, emailDigest = digest)
    case Updated(fields, _, _)                   => applyFields(s, fields)
    case ParentSet(parent, _, _)                 => s.copy(parent = parent)
    case OverrideSet(bp, _, _)                   => s.copy(overrideBp = bp)
    case LoggedIn(true, at)                      => s.copy(firstLoginAt = s.firstLoginAt.orElse(Some(at)), lastLoginAt = Some(at))
    case LoggedIn(false, at)                     => s.copy(lastLoginAt = Some(at))
    case Closed(_, at)                           => s.copy(closed = s.closed.orElse(Some(at)))
    case Erased(at)                              => s.copy(identity = Sealed.erased, emailDigest = "", erased = Some(at))

  private val random = SecureRandom()
  private val Base32 = "abcdefghijklmnopqrstuvwxyz234567"

  /** A new affiliate id: `aff-` and 10 random bytes in base32 (data-model.md); imported ids are kept. */
  def newId(): String =
    val bytes = new Array[Byte](10)
    random.nextBytes(bytes)
    val bits = BigInt(1, bytes)
    "aff-" + (0 until 16).map(i => Base32(((bits >> (5 * (15 - i))) & 31).toInt)).mkString

  def percent(bp: Int): Boolean = bp >= 0 && bp <= 10_000
  val OverrideRange             = "overridePercent must be a number between 0 and 100"

  import AffiliateCodecs.given

  def create(context: EventSourcedEntityContext) = new Affiliate(context)

  val createAccount  = command("create")(_.create)
  val updateIdentity = command("update-identity")(_.updateIdentity)
  val update         = command("update")(_.update)
  val setParent      = command("set-parent")(_.setParent)
  val setOverride    = command("set-override")(_.setOverride)
  val login          = command("login")(_.login)
  val close          = command("close")(_.close)
  val erased         = command("erased")(_.erased)
  val get            = query("get")(_.get)

object AffiliateCodecs:
  val state: Serializer[AffiliateState] = ProgrammeCodecs.serializer[AffiliateState]("affiliate")
  val event: Serializer[Affiliate.Event] = ProgrammeCodecs.serializer[Affiliate.Event]("affiliate-event")
  given Serializer[CreateAffiliate]  = ProgrammeCodecs.serializer[CreateAffiliate]("create-affiliate")
  given Serializer[UpdateIdentity]   = ProgrammeCodecs.serializer[UpdateIdentity]("update-identity")
  given Serializer[UpdateAccount]    = ProgrammeCodecs.serializer[UpdateAccount]("update-account")
  given Serializer[SetParent]        = ProgrammeCodecs.serializer[SetParent]("set-parent")
  given Serializer[SetOverride]      = ProgrammeCodecs.serializer[SetOverride]("set-override")
  given Serializer[Login]            = ProgrammeCodecs.serializer[Login]("login")
  given Serializer[CloseAccount]     = ProgrammeCodecs.serializer[CloseAccount]("close-account")
  given Serializer[Instant]          = ProgrammeCodecs.serializer[Instant]("instant")

import Affiliate.*

final class Affiliate(context: EventSourcedEntityContext) extends EventSourcedEntity[AffiliateState, Affiliate.Event]:
  def emptyState: AffiliateState = AffiliateState()

  def applyEvent(event: Affiliate.Event): AffiliateState = Affiliate.next(context.entityId.toString, currentState, event)

  /** A change to an account that is not there, or has been erased, is refused. */
  private def live(f: AffiliateState => Effect[AffiliateState]): Effect[AffiliateState] =
    if !currentState.exists then effects.error("account_not_found", ErrorCode.NotFound)
    else if currentState.erased.isDefined then effects.error("erased", ErrorCode.Conflict)
    else f(currentState)

  /** Made once; a repeat (an import run again) changes nothing. */
  def create(cmd: CreateAffiliate): Effect[AffiliateState] =
    if currentState.exists then effects.reply(currentState)
    else if cmd.parent.contains(context.entityId.toString) then effects.error("parent_cycle", ErrorCode.Conflict)
    else if !Affiliate.percent(cmd.overrideBp) then effects.error(Affiliate.OverrideRange, ErrorCode.BadRequest)
    else effects.persist(Created(cmd.identity, cmd.emailDigest, cmd.parent, cmd.overrideBp, cmd.fields, cmd.by, cmd.at)).thenReplyState

  def updateIdentity(cmd: UpdateIdentity): Effect[AffiliateState] =
    live(_ => effects.persist(IdentityUpdated(cmd.identity, cmd.emailDigest, cmd.by, cmd.at)).thenReplyState)

  def update(cmd: UpdateAccount): Effect[AffiliateState] =
    live(s => if Affiliate.applyFields(s, cmd.fields) == s then effects.reply(s) else effects.persist(Updated(cmd.fields, cmd.by, cmd.at)).thenReplyState)

  /** A manager's change of parent (FR-028); the caller has checked the tree for a loop through the view. */
  def setParent(cmd: SetParent): Effect[AffiliateState] =
    live { s =>
      if cmd.parent.contains(s.id) then effects.error("parent_cycle", ErrorCode.Conflict)
      else if cmd.parent == s.parent then effects.reply(s)
      else effects.persist(ParentSet(cmd.parent, cmd.by, cmd.at)).thenReplyState
    }

  def setOverride(cmd: SetOverride): Effect[AffiliateState] =
    live { s =>
      if !Affiliate.percent(cmd.bp) then effects.error(Affiliate.OverrideRange, ErrorCode.BadRequest)
      else if cmd.bp == s.overrideBp then effects.reply(s)
      else effects.persist(OverrideSet(cmd.bp, cmd.by, cmd.at)).thenReplyState
    }

  def login(cmd: Login): Effect[AffiliateState] =
    live(s => if s.closed.isDefined then effects.error("account_closed", ErrorCode.Conflict) else effects.persist(LoggedIn(cmd.first, cmd.at)).thenReplyState)

  def close(cmd: CloseAccount): Effect[AffiliateState] =
    live(s => if s.closed.isDefined then effects.reply(s) else effects.persist(Closed(cmd.by, cmd.at)).thenReplyState)

  /** The identity is gone: the data key destroyed, the sealed value replaced by the erased one (FR-037). Once. */
  def erased(at: Instant): Effect[AffiliateState] =
    if !currentState.exists then effects.error("account_not_found", ErrorCode.NotFound)
    else if currentState.erased.isDefined then effects.reply(currentState)
    else effects.persist(Erased(at)).thenReplyState

  def get: ReadOnlyEffect[AffiliateState] = effects.reply(currentState)

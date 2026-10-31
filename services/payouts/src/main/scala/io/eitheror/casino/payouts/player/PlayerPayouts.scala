package io.eitheror.casino.payouts.player

import com.thinkmorestupidless.ankka.core.{ComponentId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.contract.personal.Personal
import io.eitheror.casino.payouts.PayoutsCodecs
import io.eitheror.casino.payouts.player.PlayerEvent.*

import java.time.Instant

/**
 * What payouts knows of one player (R3), keyed by the player id: the identity status and names from
 * `casino.players`, the gateways and schemes of confirmed deposits, the saved destinations, and the
 * requests of the last 32 days the limits count. A request's checks read it whole, and the limit check
 * sees a request made a second ago (read-your-writes). A fact seen again writes nothing.
 */
final class PlayerPayouts(@scala.annotation.unused context: EventSourcedEntityContext) extends EventSourcedEntity[PlayerState, PlayerEvent]:
  import PlayerPayouts.*

  def emptyState: PlayerState = PlayerState()

  def applyEvent(event: PlayerEvent): PlayerState = applied(currentState, event)

  private def emit(events: Vector[PlayerEvent], destination: Option[String] = None): Effect[PlayerAnswer] =
    events match
      case first +: rest => effects.persist(first, rest*).thenReply(s => PlayerAnswer(s, None, destination.flatMap(s.destination)))
      case _             => effects.reply(PlayerAnswer(currentState, None, destination.flatMap(currentState.destination)))

  private def refuse(code: String, destination: Option[Destination] = None): Effect[PlayerAnswer] =
    effects.reply(PlayerAnswer(currentState, Some(code), destination))

  /** Requests older than 32 days are dropped at the next command that counts. */
  private def dropped(at: Instant): Vector[PlayerEvent] =
    val before = at.minus(PlayerState.Kept)
    if currentState.recent.exists(_.createdAt.isBefore(before)) then Vector(RecentDropped(before)) else Vector.empty

  def seeKyc(c: SeeKyc): Effect[PlayerAnswer] =
    val s = currentState
    emit(if s.known && s.kyc == c.status then Vector.empty else Vector(KycSeen(c.status, c.at)))

  def seeNames(c: SeeNames): Effect[PlayerAnswer] =
    val s     = currentState
    val names = ProfileName(c.givenName, c.familyName)
    if s.erased then emit(Vector.empty) else emit(if s.known && s.names.contains(names) then Vector.empty else Vector(NamesSeen(names, c.at)))

  def seeEmail(c: SeeEmail): Effect[PlayerAnswer] =
    val s = currentState
    if s.erased then emit(Vector.empty) else emit(if s.known && s.email.contains(c.address) then Vector.empty else Vector(EmailSeen(c.address, c.at)))

  def seeCurrency(c: SeeCurrency): Effect[PlayerAnswer] =
    val code = c.code.trim.toUpperCase
    emit(if currentState.currency.contains(code) then Vector.empty else Vector(CurrencySeen(code, c.at)))

  def seeClosed(c: SeeClosed): Effect[PlayerAnswer] =
    emit(if currentState.closed then Vector.empty else Vector(ClosedSeen(c.at)))

  /** A deposit adds its gateway and scheme; one already recorded, as a redelivery is, writes nothing. */
  def seeDeposit(c: SeeDeposit): Effect[PlayerAnswer] =
    val gateway = c.gateway.trim.toLowerCase
    val scheme  = c.scheme.map(_.trim).filter(_.nonEmpty)
    val known   = currentState.deposits.get(gateway).exists(schemes => scheme.forall(schemes.contains))
    emit(if known then Vector.empty else Vector(DepositSeen(gateway, scheme)))

  /**
   * The IBAN is checked before the profile (the original's order), then the names. The first destination
   * is the default whatever was asked; `setAsDefault` makes a later one the default. A save repeated under
   * its id answers the destination saved.
   */
  def saveDestination(c: SaveDestination): Effect[PlayerAnswer] =
    val s = currentState
    s.destination(c.id) match
      case Some(d) => emit(Vector.empty, Some(d.id))
      case None if !s.known || s.erased => refuse("PLAYER_NOT_FOUND")
      case None if !Iban.isValid(c.iban) => refuse(PlayerCodes.InvalidIban)
      case None =>
        val profile = s.names.map(n => (n.givenName.reveal, n.familyName.reveal))
        Names.check(c.givenName, c.familyName, profile) match
          case Left(refusal) => refuse(refusal.code)
          case Right(()) =>
            val default = c.setAsDefault || !s.activeDestinations.exists(_.default)
            val saved = Destination(c.id, Personal(c.givenName.trim), Personal(c.familyName.trim), Personal(Iban.normalise(c.iban)),
              Personal(c.label), default, active = true, c.at)
            emit(Vector(DestinationSaved(saved)), Some(c.id))

  def setDefault(c: SetDefault): Effect[PlayerAnswer] =
    currentState.destination(c.id) match
      case None                 => refuse(PlayerCodes.NotFound)
      case Some(d) if !d.active => refuse(PlayerCodes.Deleted)
      case Some(d) if d.default => refuse(PlayerCodes.AlreadyDefault, Some(d))
      case Some(d)              => emit(Vector(DefaultSet(d.id, c.at)), Some(d.id))

  /** Kept with its values blanked; no other destination is made the default. */
  def deleteDestination(c: DeleteDestination): Effect[PlayerAnswer] =
    currentState.destination(c.id) match
      case None                 => refuse(PlayerCodes.NotFound)
      case Some(d) if !d.active => refuse(PlayerCodes.Deleted)
      case Some(d)              => emit(Vector(DestinationDeleted(d.id, c.at)), Some(d.id))

  def countRequest(c: CountRequest): Effect[PlayerAnswer] =
    val counted = if currentState.recent.exists(_.id == c.request.id) then Vector.empty else Vector(RequestCounted(c.request))
    emit(dropped(c.at) ++ counted)

  def uncount(c: Uncount): Effect[PlayerAnswer] =
    val uncounted = if currentState.recent.exists(r => r.id == c.id && r.counts) then Vector(RequestUncounted(c.id, c.at)) else Vector.empty
    emit(dropped(c.at) ++ uncounted)

  def erase(c: Erase): Effect[PlayerAnswer] = emit(if currentState.erased then Vector.empty else Vector(Erased(c.at)))

  def get: ReadOnlyEffect[PlayerState] = effects.reply(currentState)

object PlayerPayouts
    extends EventSourcedEntity.Companion[PlayerPayouts, PlayerState, PlayerEvent](
      componentId = ComponentId("player-payouts"),
      stateSerializer = PayoutsCodecs.serializer[PlayerState]("player-state"),
      eventSerializer = PayoutsCodecs.serializer[PlayerEvent]("player-event")
    ):
  given Serializer[PlayerState]       = PayoutsCodecs.serializer[PlayerState]("player-reply")
  given Serializer[PlayerAnswer]      = PayoutsCodecs.serializer[PlayerAnswer]("player-answer")
  given Serializer[SeeKyc]            = PayoutsCodecs.serializer[SeeKyc]("see-kyc")
  given Serializer[SeeNames]          = PayoutsCodecs.serializer[SeeNames]("see-names")
  given Serializer[SeeEmail]          = PayoutsCodecs.serializer[SeeEmail]("see-email")
  given Serializer[SeeClosed]         = PayoutsCodecs.serializer[SeeClosed]("see-closed")
  given Serializer[SeeCurrency]       = PayoutsCodecs.serializer[SeeCurrency]("see-currency")
  given Serializer[SeeDeposit]        = PayoutsCodecs.serializer[SeeDeposit]("see-deposit")
  given Serializer[SaveDestination]   = PayoutsCodecs.serializer[SaveDestination]("save-destination")
  given Serializer[SetDefault]        = PayoutsCodecs.serializer[SetDefault]("set-default")
  given Serializer[DeleteDestination] = PayoutsCodecs.serializer[DeleteDestination]("delete-destination")
  given Serializer[CountRequest]      = PayoutsCodecs.serializer[CountRequest]("count-request")
  given Serializer[Uncount]           = PayoutsCodecs.serializer[Uncount]("uncount")
  given Serializer[Erase]             = PayoutsCodecs.serializer[Erase]("player-erase")

  def create(context: EventSourcedEntityContext) = new PlayerPayouts(context)

  val seeKyc            = command("see-kyc")(_.seeKyc)
  val seeNames          = command("see-names")(_.seeNames)
  val seeEmail          = command("see-email")(_.seeEmail)
  val seeClosed         = command("see-closed")(_.seeClosed)
  val seeCurrency       = command("see-currency")(_.seeCurrency)
  val seeDeposit        = command("see-deposit")(_.seeDeposit)
  val saveDestination   = command("save-destination")(_.saveDestination)
  val setDefault        = command("set-default")(_.setDefault)
  val deleteDestination = command("delete-destination")(_.deleteDestination)
  val countRequest      = command("count-request")(_.countRequest)
  val uncount           = command("uncount")(_.uncount)
  val erase             = command("erase")(_.erase)
  val get               = query("get")(_.get)

  private val Blank = Personal("")

  def applied(s: PlayerState, event: PlayerEvent): PlayerState = event match
    case KycSeen(status, _)  => s.copy(known = true, kyc = status)
    case NamesSeen(names, _) => s.copy(known = true, names = Some(names))
    case EmailSeen(email, _) => s.copy(known = true, email = Some(email))
    case ClosedSeen(_)       => s.copy(known = true, closed = true)
    case CurrencySeen(c, _)  => s.copy(known = true, currency = Some(c))
    case DepositSeen(gateway, scheme) =>
      s.copy(deposits = s.deposits.updated(gateway, s.deposits.getOrElse(gateway, Set.empty) ++ scheme))
    case DestinationSaved(d) =>
      val others = if d.default then s.destinations.map(_.copy(default = false)) else s.destinations
      s.copy(destinations = others :+ d)
    case DefaultSet(id, _)         => s.copy(destinations = s.destinations.map(d => d.copy(default = d.id == id)))
    case DestinationDeleted(id, _) => s.copy(destinations = s.destinations.map(d => if d.id == id then d.blanked.copy(active = false) else d))
    case RequestCounted(r)         => s.copy(recent = s.recent :+ r)
    case RequestUncounted(id, _)   => s.copy(recent = s.recent.map(r => if r.id == id then r.copy(counts = false) else r))
    case RecentDropped(before)     => s.copy(recent = s.recent.filterNot(_.createdAt.isBefore(before)))
    case Erased(_) =>
      s.copy(names = s.names.map(_ => ProfileName(Blank, Blank)), email = s.email.map(_ => Blank),
        destinations = s.destinations.map(_.blanked), erased = true)

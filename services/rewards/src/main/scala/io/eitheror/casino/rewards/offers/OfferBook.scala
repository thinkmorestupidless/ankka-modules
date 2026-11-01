package io.eitheror.casino.rewards.offers

import com.thinkmorestupidless.ankka.core.{ComponentId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.rewards.{Json, RewardsCodecs}
import io.eitheror.casino.rewards.offers.OfferEvent.*

/**
 * A brand's offers (FR-001 to FR-003; research R3), keyed by the slug: one writer for the rules that span offers
 * (code uniqueness against the brand's other active offers and the programme's document, role uniqueness, the order).
 * Each command runs `OfferRules` over the book as it stands and writes one event or answers the first refusal. The
 * provider's spin-value and game checks are the endpoint's, before a create or an edit reaches here. Nothing is
 * removed: a delete deactivates, and a repeated deactivation writes nothing. Its events are the audit's
 * (`OfferAuditPublisher`); no event holds a player.
 */
final class OfferBook(@scala.annotation.unused context: EventSourcedEntityContext) extends EventSourcedEntity[OfferBookState, OfferEvent]:
  def emptyState: OfferBookState = OfferBookState()

  def applyEvent(event: OfferEvent): OfferBookState = OfferBook.applied(currentState, event)

  private def refuse(r: Refusal): Effect[OfferAnswer] = effects.reply(OfferAnswer(refusal = Some(r)))

  /** A create under an id the book already holds answers that offer: the endpoint's retry, not a second offer. */
  def add(c: CreateOffer): Effect[OfferAnswer] =
    currentState.offer(c.draft.offer.id) match
      case Some(o) => effects.reply(OfferAnswer(Some(o)))
      case None =>
        OfferRules.create(c.draft, currentState.offers.values, c.programmeCodes) match
          case Left(r)      => refuse(r)
          case Right(offer) => effects.persist(OfferCreated(offer, c.by)).thenReply(_ => OfferAnswer(Some(offer)))

  def edit(c: EditOffer): Effect[OfferAnswer] =
    currentState.offer(c.id) match
      case None => refuse(Refusal.OfferNotFound)
      case Some(existing) =>
        Json.parseObject(c.body).toRight("body").flatMap(b => Bodies.patch(b, existing).map(_ -> Bodies.namedFields(b))) match
          case Left(field) => refuse(Refusal(400, "invalid_body", Vector("field" -> field)))
          case Right((patch, named)) =>
            OfferRules.edit(c.id, patch, currentState.offers.values, c.programmeCodes, c.at) match
              case Left(r)       => refuse(r)
              case Right(edited) => effects.persist(OfferEdited(edited, named, c.by)).thenReply(_ => OfferAnswer(Some(edited)))

  def deactivate(c: DeactivateOffer): Effect[OfferAnswer] =
    currentState.offer(c.id) match
      case None                    => refuse(Refusal.OfferNotFound)
      case Some(o) if !o.active    => effects.reply(OfferAnswer(Some(o)))
      case Some(_) =>
        effects.persist(OfferDeactivated(c.id, c.by, c.at)).thenReply(s => OfferAnswer(s.offer(c.id)))

  /** The named offers first, in the order given, then the rest as they were; an id not of the brand is ignored. */
  def reorder(c: ReorderOffers): Effect[OfferAnswer] =
    val known = c.ids.distinct.filter(currentState.offers.contains)
    val next  = known ++ currentState.order.filterNot(known.contains)
    if next == currentState.order then effects.reply(OfferAnswer(updated = known.size))
    else effects.persist(OffersReordered(next, c.by, c.at)).thenReply(_ => OfferAnswer(updated = known.size))

  def get: ReadOnlyEffect[OfferBookState] = effects.reply(currentState)

object OfferBook
    extends EventSourcedEntity.Companion[OfferBook, OfferBookState, OfferEvent](
      componentId = ComponentId("offer-book"),
      stateSerializer = RewardsCodecs.serializer[OfferBookState]("offer-book-state"),
      eventSerializer = RewardsCodecs.serializer[OfferEvent]("offer-event")
    ):
  given Serializer[OfferBookState]  = RewardsCodecs.serializer[OfferBookState]("offer-book-reply")
  given Serializer[OfferAnswer]     = RewardsCodecs.serializer[OfferAnswer]("offer-answer")
  given Serializer[CreateOffer]     = RewardsCodecs.serializer[CreateOffer]("offer-create")
  given Serializer[EditOffer]       = RewardsCodecs.serializer[EditOffer]("offer-edit")
  given Serializer[DeactivateOffer] = RewardsCodecs.serializer[DeactivateOffer]("offer-deactivate")
  given Serializer[ReorderOffers]   = RewardsCodecs.serializer[ReorderOffers]("offer-reorder")

  def create(context: EventSourcedEntityContext) = new OfferBook(context)

  val add        = command("create")(_.add)
  val edit       = command("edit")(_.edit)
  val deactivate = command("deactivate")(_.deactivate)
  val reorder    = command("reorder")(_.reorder)
  val get        = query("get")(_.get)

  def applied(s: OfferBookState, event: OfferEvent): OfferBookState = event match
    case OfferCreated(o, _)          => s.copy(offers = s.offers.updated(o.id, o), order = s.order :+ o.id)
    case OfferEdited(o, _, _)        => s.copy(offers = s.offers.updated(o.id, o))
    case OfferDeactivated(id, _, at) => s.copy(offers = s.offers.updatedWith(id)(_.map(_.copy(active = false, updatedAt = at))))
    case OffersReordered(order, _, _) => s.copy(order = order)

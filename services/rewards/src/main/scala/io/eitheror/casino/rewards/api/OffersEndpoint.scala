package io.eitheror.casino.rewards.api

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.http.*
import io.eitheror.casino.contract.access.Routes
import io.eitheror.casino.contract.money.Amounts as MoneyAmounts
import io.eitheror.casino.rewards.Json
import io.eitheror.casino.rewards.Json.*
import io.eitheror.casino.rewards.clients.{Called, GameProvider, SpinValues}
import io.eitheror.casino.rewards.offers.*

import java.util.UUID

import Answers.*

/**
 * `/v1/casino/brands/<slug>/offers` (contracts/routes.md): the brand's offers for its operators holding one of the
 * three admin roles (FR-031). Every amount is a whole number of minor units (FR-032). A create is checked in the
 * original's order (`BR:1880-2110`): the offer's own fields first, then the provider's spin values and the game's
 * eligibility for free rounds, then the book's (codes in use); an edit's spin values are asked only for what it changed
 * (research R1). Each change is audited with the operator (`OfferAuditPublisher`).
 */
final class OffersEndpoint(ctx: Ctx) extends HttpEndpoint(s"/v1/casino/brands/${ctx.slug}/offers"):
  val acl: Acl = Routes.operatorHolding(ctx.slug, Answers.AdminRoles.toVector.sorted*)(ctx.authenticate())

  private def body(text: String)(f: JsonNode => Reply): Reply = Json.parseObject(text).fold(badBody)(f)
  private def by: String = principal.subject

  get("") { () => ok(Json.arr(ctx.offers.list.map(Bodies.render)*)) }

  get("/{offerId}") { (id: String) =>
    ctx.offers.offer(id).fold(Offers.refuse("offer_not_found"))(o => ok(Bodies.render(o)))
  }

  postBody("") { (text: String) => body(text)(Offers.create(ctx, _, by)) }

  patchBody("/{offerId}") { (id: String, text: String) => body(text)(Offers.edit(ctx, id, _, text, by)) }

  delete("/{offerId}") { (id: String) =>
    val a = ctx.book.call(OfferBook.deactivate).invoke(DeactivateOffer(id, by, ctx.now()))
    a.refusal.fold(ok(Json.obj("status" -> "deactivated")))(r => refused(r))
  }

  get("/code-document") { () => ok(Offers.codeDocument(ctx)) }

  get("/free-rounds/spin-values") { () =>
    FreeRoundsReads.spinValues(ctx, query.optional[String]("gameId"), query.optional[String]("currency"))
  }

  putBody("/order") { (text: String) =>
    body(text) { b =>
      val a = ctx.book.call(OfferBook.reorder).invoke(ReorderOffers(b.strings("orderedOfferIds"), by, ctx.now()))
      ok(Json.obj("updated" -> a.updated))
    }
  }

/** The offer routes' work, apart from the endpoint so a suite can read it. */
object Offers:
  def refuse(code: String, fields: (String, Any)*): Reply = Answers.Offers.refuse(code, fields*)

  /**
   * `GET /code-document` (FR-021): the copy's time, its entries, and whether it is stale, past the staleness bound from
   * its fetch or never fetched. A service with no document to read is inert: nothing fetched, and not stale.
   */
  def codeDocument(ctx: Ctx): JsonNode =
    val d       = ctx.codeDocument
    val entries = d.entries.toVector.sortBy(_._1).map((code, e) => Json.obj("code" -> code, "offerId" -> e.offerId, "tag" -> e.tag))
    Json.obj("fetchedAt" -> d.fetchedAt.map(_.toString), "entries" -> Json.arr(entries*),
      "stale" -> (ctx.settings.codeDocumentUrl.isDefined && d.stale(ctx.now(), ctx.settings.codesStaleAfter)))

  /** The amounts of a body, the spin configurations' too, each whole minor units (FR-032). */
  private def whole(b: JsonNode): Either[Reply, Unit] =
    for
      _ <- Answers.amounts(b, Bodies.AmountFields*)
      _ <- b.objects("spinConfigurations").foldLeft[Either[Reply, Unit]](Right(()))((acc, e) =>
             acc.flatMap(_ => Answers.amount(e, "spinValue").map(_ => ()).left.map(_ => notWhole("spinConfigurations.spinValue",
               Option(e.get("spinValue")).fold("")(_.asText)))))
    yield ()

  private def invalid(field: String): Reply = refusal(400, "invalid_body", "field" -> field, "message" -> s"'$field' cannot be read")

  def create(ctx: Ctx, b: JsonNode, by: String): Reply =
    val result = for
      _     <- whole(b)
      draft <- Bodies.draft(b, UUID.randomUUID().toString, ctx.now()).left.map(invalid)
      // The offer's own fields, before anything is asked of the provider: the book's code checks come after.
      offer <- OfferRules.create(draft, Vector.empty, Set.empty).left.map(r => refused(r))
      _     <- providerChecks(ctx.gameProvider, offer, None, game = true)
    yield draft
    result.fold(identity, { draft =>
      ctx.programme.fold(unavailable) { programme =>
        val a = ctx.book.call(OfferBook.add).invoke(CreateOffer(draft, programme.codes, by))
        a.refusal.fold(created(Bodies.render(a.offer.get)))(r => refused(r))
      }
    })

  def edit(ctx: Ctx, id: String, b: JsonNode, text: String, by: String): Reply =
    val book = ctx.offers
    // An edit is checked against the copy as it stands: the contract answers 503 for a create only.
    val programme = ctx.programme.getOrElse(ctx.codeDocument).codes
    val result = for
      _        <- whole(b)
      existing <- book.offer(id).toRight(refuse("offer_not_found"))
      patch    <- Bodies.patch(b, existing).left.map(invalid)
      edited   <- OfferRules.edit(id, patch, book.offers.values, programme, ctx.now()).left.map(r => refused(r))
      _        <- if patch.touchesSpins then providerChecks(ctx.gameProvider, edited, Some(existing), game = false) else Right(())
    yield ()
    result.fold(identity, { _ =>
      val a = ctx.book.call(OfferBook.edit).invoke(EditOffer(id, text, programme, by, ctx.now()))
      a.refusal.fold(ok(Bodies.render(a.offer.get)))(r => refused(r))
    })

  /**
   * The provider's checks of a free-rounds offer (FR-002): each spin value it must offer (`spin_value_not_offered` with
   * the values it does, in minor units), then, on a create, the game's eligibility (G1, `game_not_eligible`). A spin
   * value is refused only on a known mismatch: a brand with no provider configuration, or a provider that does not
   * answer, lets it through, as the original's (`FreeSpinsProvisioner.scala:118-143`). A game the catalogue cannot be
   * asked about is `service_unavailable`.
   */
  def providerChecks(provider: GameProvider, offer: Offer, before: Option[Offer], game: Boolean): Either[Reply, Unit] =
    val spins = OfferRules.spinValueChecks(offer, before).foldLeft[Either[Reply, Unit]](Right(())) { (acc, check) =>
      acc.flatMap { _ =>
        provider.spinValues(Vector(check.gameId), check.currency) match
          case Called.Done(values) =>
            notOffered(values, check).fold(Right(()))(allowed => Left(refuse("spin_value_not_offered", "allowedValues" -> allowed)))
          case _ => Right(())
      }
    }
    spins.flatMap { _ =>
      offer.gameId.filter(_ => game && offer.freeRounds) match
        case None => Right(())
        case Some(g) =>
          provider.game(g) match
            case Called.Done(found) if found.freeRoundsEligible => Right(())
            case Called.Done(_) | Called.Refused(_, _, _)       => Left(refuse("game_not_eligible"))
            case Called.Unreachable(_)                          => Left(unavailable)
    }

  /** The values offered, in minor units, when the spin value is not among them; none when it is, or none are configured. */
  def notOffered(values: SpinValues, check: SpinCheck): Option[Vector[Long]] =
    if !values.configured then None
    else
      val offered = values.values.flatMap(v => MoneyAmounts.parse(v, check.currency).toOption.map(_.value)).distinct
      Option.unless(offered.contains(check.spinValue))(offered)

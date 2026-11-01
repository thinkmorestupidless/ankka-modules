package io.eitheror.casino.rewards.offers

import com.thinkmorestupidless.ankka.testkit.EventSourcedTestKit
import io.eitheror.casino.contract.facts.AuditChange
import io.eitheror.casino.contract.money.Currency
import io.eitheror.casino.rewards.Json
import io.eitheror.casino.rewards.facts.Facts
import io.eitheror.casino.rewards.offers.OfferEvent.*

import java.time.Instant
import java.time.temporal.ChronoUnit.DAYS

/** data-model.md's `OfferBook`: each command through `OfferRules` over the whole book, one event or the first refusal. */
class OfferBookSuite extends munit.FunSuite:
  private val at  = Instant.parse("2026-11-01T12:00:00Z")
  private val Eur = Currency.unsafe("EUR")
  private type Kit = EventSourcedTestKit[OfferBook, OfferBookState, OfferEvent]

  private def book: Kit = EventSourcedTestKit.of(OfferBook, "goldvibe")

  private def offer(id: String, codes: String*) =
    Offer(id = id, name = s"offer $id", offerType = OfferType.DepositMatch, bonusPercentage = 10000L, maxBonusValue = Some(50000L),
      wageringMultiplier = 30L, minDepositAmount = Some(2000L), currency = Some(Eur), redemptionCodes = codes.toVector.map(RedemptionCode(_)),
      createdAt = at, updatedAt = at)

  private def create(kit: Kit, o: Offer, programme: Set[String] = Set.empty) =
    kit.call(OfferBook.add)(CreateOffer(Draft(o), programme, "op-1"))

  private def edit(kit: Kit, id: String, body: String) = kit.call(OfferBook.edit)(EditOffer(id, body, Set.empty, "op-2", at.plus(1, DAYS)))

  private def refusal(a: OfferAnswer) = a.refusal.map(_.error)

  test("a create writes the offer active and places it last in the brand's order"):
    val kit = book
    val r   = create(kit, offer("o-1", "Welcome"))
    assertEquals(r.events.toVector.map(_.getClass.getSimpleName), Vector("OfferCreated"))
    assertEquals(r.replyValue.offer.map(o => (o.id, o.active)), Some(("o-1", true)))
    create(kit, offer("o-2")): Unit
    assertEquals(kit.currentState.list.map(_.id), Vector("o-1", "o-2"))

  test("a create refused writes nothing and answers the first refusal"):
    val kit = book
    val r   = create(kit, offer("o-1").copy(maxBonusValue = None))
    assertEquals((refusal(r.replyValue), r.events.toVector), (Some("bonus_amount_required"), Vector.empty))
    assertEquals(kit.currentState.offers, Map.empty)

  test("a create repeated under its id answers the offer and writes nothing"):
    val kit = book
    create(kit, offer("o-1")): Unit
    val again = create(kit, offer("o-1").copy(name = "another"))
    assertEquals((again.events.toVector, again.replyValue.offer.map(_.name)), (Vector.empty, Some("offer o-1")))

  test("a code another active offer holds, or the programme's document, is in use in any case; a deactivated offer's is free"):
    val kit = book
    create(kit, offer("o-1", "Welcome")): Unit
    assertEquals(refusal(create(kit, offer("o-2", " WELCOME ")).replyValue), Some("code_already_in_use"))
    assertEquals(refusal(create(kit, offer("o-3", "spring"), programme = Set("SPRING")).replyValue), Some("code_already_in_use"))
    kit.call(OfferBook.deactivate)(DeactivateOffer("o-1", "op-1", at)): Unit
    assertEquals(refusal(create(kit, offer("o-4", "welcome")).replyValue), None)

  test("an edit reads its body against the offer: a field named is set, null clears it, one not named is kept"):
    val kit = book
    create(kit, offer("o-1").copy(maxConcurrentGrants = Some(2), claimWindowDays = Some(7))): Unit
    val r = edit(kit, "o-1", """{"name":"Renamed","maxConcurrentGrants":null,"maxStake":500}""")
    assertEquals(refusal(r.replyValue), None)
    val o = kit.currentState.offer("o-1").get
    assertEquals((o.name, o.maxConcurrentGrants, o.claimWindowDays, o.betRules.maxStake, o.updatedAt),
      ("Renamed", None, Some(7), Some(500L), at.plus(1, DAYS)))
    assertEquals(r.events.toVector, Vector(OfferEdited(o, Vector("maxConcurrentGrants", "maxStake", "name"), "op-2")))

  test("an edit keeps the bet-time rules it does not name"):
    val kit = book
    create(kit, offer("o-1")): Unit
    edit(kit, "o-1", """{"maxStake":500,"scopeInclude":["slots"]}"""): Unit
    edit(kit, "o-1", """{"fundingOrder":"bonus_first"}"""): Unit
    val rules = kit.currentState.offer("o-1").get.betRules
    assertEquals((rules.maxStake, rules.scopeInclude, rules.fundingOrder), (Some(500L), Set("slots"), Some("bonus_first")))

  test("an edit of an offer not in the book is offer_not_found; a field that cannot be read is named"):
    val kit = book
    assertEquals(refusal(edit(kit, "nope", "{}").replyValue), Some("offer_not_found"))
    create(kit, offer("o-1")): Unit
    val bad = edit(kit, "o-1", """{"type":"LOTTERY"}""").replyValue.refusal.get
    assertEquals((bad.status, bad.error, bad.detail), (400, "invalid_body", Vector("field" -> "type")))

  test("an edit giving an active offer's role to another is role_already_assigned; clearing a role never is"):
    val kit = book
    create(kit, offer("o-1").copy(role = Some("welcome"))): Unit
    create(kit, offer("o-2")): Unit
    assertEquals(refusal(edit(kit, "o-2", """{"role":"welcome"}""").replyValue), Some("role_already_assigned"))
    assertEquals(refusal(edit(kit, "o-1", """{"role":null}""").replyValue), None)
    assertEquals(refusal(edit(kit, "o-2", """{"role":"welcome"}""").replyValue), None)

  test("a deactivation keeps the offer, inactive; a repeat writes nothing; an unknown offer is offer_not_found"):
    val kit = book
    create(kit, offer("o-1")): Unit
    val r = kit.call(OfferBook.deactivate)(DeactivateOffer("o-1", "op-1", at))
    assertEquals((r.events.toVector, r.replyValue.offer.map(_.active)), (Vector(OfferDeactivated("o-1", "op-1", at)), Some(false)))
    assertEquals(kit.call(OfferBook.deactivate)(DeactivateOffer("o-1", "op-1", at)).events.toVector, Vector.empty)
    assertEquals(refusal(kit.call(OfferBook.deactivate)(DeactivateOffer("o-9", "op-1", at)).replyValue), Some("offer_not_found"))
    assertEquals(kit.currentState.list.map(o => (o.id, o.active)), Vector("o-1" -> false))

  test("a reorder places the named offers first, ignores ids not of the brand, and answers how many it placed"):
    val kit = book
    Vector("o-1", "o-2", "o-3").foreach(id => create(kit, offer(id)): Unit)
    val r = kit.call(OfferBook.reorder)(ReorderOffers(Vector("o-3", "elsewhere", "o-1"), "op-1", at))
    assertEquals(r.replyValue.updated, 2)
    assertEquals(kit.currentState.list.map(_.id), Vector("o-3", "o-1", "o-2"))
    assertEquals(kit.call(OfferBook.reorder)(ReorderOffers(Vector("o-3", "o-1"), "op-1", at)).events.toVector, Vector.empty)

  test("the book's reads: by code among live offers, by role among active ones, the live codes"):
    val kit = book
    create(kit, offer("o-1", "Welcome").copy(role = Some("welcome"))): Unit
    create(kit, offer("o-2", "Later").copy(validFrom = Some(at.plus(5, DAYS)))): Unit
    val s = kit.currentState
    assertEquals(s.byCode("  WELCOME ", at).map(_.id), Some("o-1"))
    assertEquals(s.byCode("later", at), None)
    assertEquals(s.byRole("welcome").map(_.id), Some("o-1"))
    assertEquals(s.liveCodes(at), Map("welcome" -> "o-1"))

  test("each change is an audit record naming the operator; an edit names its fields with their new values"):
    val o = offer("o-1").copy(name = "Welcome")
    val created = Facts.offerAudit("goldvibe", "goldvibe", 1L, OfferCreated(o, "op-1")).fact
    assertEquals((created.action, created.actorId, created.subjectKind, created.subjectId, created.recordId),
      ("rewards.offer_created", "op-1", "offer", "o-1", "audit/offers/goldvibe/1"))
    val edited = Facts.offerAudit("goldvibe", "goldvibe", 2L, OfferEdited(o, Vector("name"), "op-2")).fact
    assertEquals((edited.action, edited.changes), ("rewards.offer_edited", Vector(AuditChange("name", None, Some("Welcome")))))
    val off = Facts.offerAudit("goldvibe", "goldvibe", 3L, OfferDeactivated("o-1", "op-1", at)).fact
    assertEquals((off.action, off.changes), ("rewards.offer_deactivated", Vector(AuditChange("active", Some("true"), Some("false")))))
    val order = Facts.offerAudit("goldvibe", "goldvibe", 4L, OffersReordered(Vector("o-2", "o-1"), "op-1", at)).fact
    assertEquals((order.action, order.subjectKind, order.subjectId, order.changes),
      ("rewards.offers_reordered", "offer-book", "goldvibe", Vector(AuditChange("order", None, Some("o-2,o-1")))))

  test("a body reads to a draft: ANY is any deposit, a type not known is named, an amount reads as minor units"):
    val b = Json.parseObject("""{"type":"NO_DEPOSIT","maxBonusValue":1000,"depositNumberTarget":"ANY","currency":"eur",
      |"redemptionCodes":[{"code":"Spring","isAffiliate":true}],"spinConfigurations":[{"currency":"ZAR","spinValue":200}]}""".stripMargin).get
    val d = Bodies.draft(b, "o-9", at).toOption.get
    assertEquals((d.offer.offerType, d.offer.maxBonusValue, d.offer.depositNumberTarget, d.offer.currency, d.offer.redemptionCodes),
      (OfferType.NoDeposit, Some(1000L), None, Some(Eur), Vector(RedemptionCode("Spring", affiliate = true))))
    assertEquals(d.spinConfigurations, Some(Vector(SpinEntry("ZAR", 200L))))
    assertEquals(Bodies.draft(Json.parseObject("""{"type":"LOTTERY"}""").get, "o-9", at), Left("type"))
    assertEquals(Bodies.draft(Json.parseObject("""{"depositNumberTarget":2}""").get, "o-9", at).map(_.offer.depositNumberTarget),
      Right(Some(2)))
    assertEquals(Bodies.render(d.offer).get("depositNumberTarget").asText, "ANY")

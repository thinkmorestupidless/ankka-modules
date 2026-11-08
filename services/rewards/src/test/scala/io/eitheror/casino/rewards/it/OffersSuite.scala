package io.eitheror.casino.rewards.it

import io.eitheror.casino.contract.facts.{AuditRecord, PlatformAudit}
import io.eitheror.casino.rewards.Json
import io.eitheror.casino.rewards.Json.*

import java.util.UUID

/** `/offers` through the whole service (contracts/routes.md): who may call, the create's order of checks, edit, delete, order, audit. */
class OffersSuite extends munit.FunSuite:
  private lazy val kit = SharedKit.kit
  override def munitTimeout = scala.concurrent.duration.Duration(120, "s")
  override def beforeEach(context: BeforeEach): Unit = kit.reset()

  private def unique(p: String) = s"$p-${UUID.randomUUID().toString.take(8)}"
  private val admin = kit.asOperator("brand_admin", "op-offers")

  private def create(fields: (String, Any)*): Reply = kit.json("POST", kit.offersPath(""), Json.obj(fields*), admin)
  private def matchOffer(fields: (String, Any)*): Vector[(String, Any)] =
    Vector[(String, Any)]("name" -> unique("Match"), "type" -> "DEPOSIT_MATCH", "bonusPercentage" -> 10000L, "maxBonusValue" -> 50000L,
      "wageringMultiplier" -> 30L, "currency" -> "EUR") ++ fields
  private def freeRounds(game: String, fields: (String, Any)*): Vector[(String, Any)] =
    Vector[(String, Any)]("name" -> unique("Spins"), "type" -> "FREE_SPINS", "currency" -> "EUR", "gameId" -> game, "spinCount" -> 10,
      "spinValue" -> 20L) ++ fields

  private def audits(subject: String): Vector[AuditRecord] =
    kit.facts(PlatformAudit).collect { case r: AuditRecord if r.subjectId == subject => r }

  test("only an operator of the brand holding an admin role may call"):
    assertEquals(kit.request("GET", kit.offersPath(""), None, kit.asPlayer("p-1")).status, 403)
    assertEquals(kit.request("GET", kit.offersPath(""), None, kit.asOperator("brand_finance")).status, 403)
    assertEquals(kit.request("GET", kit.offersPath(""), None, kit.asOperator("resource_admin")).status, 200)
    assertEquals(kit.request("GET", kit.offersPath(""), None, Nil).status, 401)

  test("a create answers 201 with the offer, listed and read by id, audited with the operator"):
    val r = create(matchOffer("role" -> unique("welcome"))*)
    assertEquals(r.status, 201, r.text)
    val id = r.str("id")
    val one = kit.request("GET", kit.offersPath(s"/$id"), None, admin)
    assertEquals((one.status, one.json.text("type"), one.json.long("maxBonusValue"), one.json.text("payloadType")),
      (200, Some("DEPOSIT_MATCH"), Some(50000L), Some("bonus_funds")))
    val listed = Json.parse(kit.request("GET", kit.offersPath(""), None, admin).text).get
    assert((0 until listed.size).exists(i => listed.get(i).text("id").contains(id)))
    val audit = kit.eventually("audited")(audits(id).headOption)
    assertEquals((audit.action, audit.actorId, audit.subjectKind), ("rewards.offer_created", "op-offers", "offer"))

  test("an offer not in the book is offer_not_found"):
    val r = kit.request("GET", kit.offersPath("/nope"), None, admin)
    assertEquals((r.status, r.str("error")), (404, "offer_not_found"))

  test("an amount that is not whole minor units is refused naming it"):
    val r = create(matchOffer("maxBonusValue" -> 500.5)*)
    assertEquals((r.status, r.str("error"), r.str("field")), (400, "amount_not_minor_units", "maxBonusValue"))
    val s = create(freeRounds("g-1", "spinConfigurations" -> Json.arr(Json.obj("currency" -> "EUR", "spinValue" -> 0.2)))*)
    assertEquals((s.status, s.str("field")), (400, "spinConfigurations.spinValue"))

  test("the offer's own fields are checked before the provider is asked"):
    val game = unique("g")
    val r = create(freeRounds(game, "winningsDestination" -> "bonus_balance", "wageringMultiplier" -> 5L)*)
    assertEquals((r.status, r.str("error")), (400, "max_convertible_amount_required"))
    assertEquals(kit.provider.received, Vector.empty)

  test("a spin value the provider does not offer is refused with the values it does, in minor units"):
    val game = unique("g")
    kit.provider.game(game, "free_spins")
    kit.provider.spinValues("EUR", "0.10", "0.50")
    val r = create(freeRounds(game)*)
    assertEquals((r.status, r.str("error")), (400, "spin_value_not_offered"), r.text)
    assertEquals(Vector(r.json.get("allowedValues").get(0).asLong, r.json.get("allowedValues").get(1).asLong), Vector(10L, 50L))

  test("a provider with no configuration lets the spin value through; a game not eligible is refused"):
    val game = unique("g")
    kit.provider.notConfigured = true
    val r = create(freeRounds(game)*)
    assertEquals((r.status, r.str("error")), (400, "game_not_eligible"), r.text)
    kit.provider.notConfigured = false
    kit.provider.game(game, "free_bets")
    kit.provider.spinValues("EUR", "0.20")
    assertEquals(create(freeRounds(game)*).status, 201)

  test("an edit sets what it names and clears what it nulls; its spin values are asked only when changed"):
    val game = unique("g")
    kit.provider.game(game, "free_spins")
    kit.provider.spinValues("EUR", "0.20", "0.30")
    val id = create(freeRounds(game, "maxConcurrentGrants" -> 2)*).str("id")
    val asked = kit.provider.received.size
    val e = kit.json("PATCH", kit.offersPath(s"/$id"), Json.obj("name" -> "Renamed", "maxConcurrentGrants" -> null), admin)
    assertEquals((e.status, e.str("name"), e.json.long("maxConcurrentGrants")), (200, "Renamed", None), e.text)
    assertEquals(kit.provider.received.size, asked)
    val bad = kit.json("PATCH", kit.offersPath(s"/$id"), Json.obj("spinValue" -> 40L), admin)
    assertEquals((bad.status, bad.str("error")), (400, "spin_value_not_offered"))
    assertEquals(kit.json("PATCH", kit.offersPath(s"/$id"), Json.obj("spinValue" -> 30L), admin).status, 200)
    val edits = kit.eventually("edits audited")(Some(audits(id).filter(_.action == "rewards.offer_edited")).filter(_.size == 2))
    assertEquals(edits.head.changes.map(_.field), Vector("maxConcurrentGrants", "name"))

  test("a delete deactivates and keeps the offer; the order is the operator's, ids not of the brand ignored"):
    val a = create(matchOffer()*).str("id")
    val b = create(matchOffer()*).str("id")
    val d = kit.request("DELETE", kit.offersPath(s"/$a"), None, admin)
    assertEquals((d.status, d.str("status")), (200, "deactivated"))
    assertEquals(kit.request("GET", kit.offersPath(s"/$a"), None, admin).json.bool("active"), Some(false))
    assertEquals(kit.request("DELETE", kit.offersPath("/nope"), None, admin).status, 404)
    val o = kit.json("PUT", kit.offersPath("/order"), Json.obj("orderedOfferIds" -> Json.arr(b, "elsewhere", a)), admin)
    assertEquals((o.status, o.json.long("updated")), (200, Some(2L)))
    val list = Json.parse(kit.request("GET", kit.offersPath(""), None, admin).text).get
    val ids  = (0 until list.size).flatMap(i => list.get(i).text("id")).toVector
    assertEquals(ids.take(2), Vector(b, a))

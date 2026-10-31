package io.eitheror.casino.payouts.features

import com.fasterxml.jackson.databind.JsonNode
import io.eitheror.casino.contract.personal.Personal
import io.eitheror.casino.payouts.Json
import io.eitheror.casino.payouts.Json.*
import io.eitheror.casino.payouts.player.*
import io.eitheror.casino.payouts.terminals.Terminal

/**
 * features/payouts/terminals.feature (US3; FR-010 to FR-013). The listing and the destinations are read and
 * written through the player's routes; the listing takes the player's currency from their record, as
 * `player_registered` leaves it, so no step names it in the query.
 */
class TerminalsFeatures extends PayoutsFeatures("terminals.feature"):

  private var listed: Vector[JsonNode] = Vector.empty
  private var target: Terminal         = bank
  private var saved: Vector[String]    = Vector.empty

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    listed = Vector.empty; target = bank; saved = Vector.empty

  private def route(path: String) = kit.playerPath(s"/$player$path")

  private def list(): Vector[JsonNode] =
    val r = kit.request("GET", route("/withdrawal-terminals"), None, kit.asPlayer(player))
    assertEquals(r.status, 200, r.text)
    r.json.objects("terminals")

  private def deposited(gateway: String, scheme: String): Unit =
    record().call(PlayerPayouts.seeDeposit).invoke(SeeDeposit(gateway, Some(scheme), kit.now())): Unit

  private def ids = listed.flatMap(_.text("id"))

  Given("a player with a wallet in {string}") { (c: String) =>
    currency = c
    record().call(PlayerPayouts.seeCurrency).invoke(SeeCurrency(c, kit.now())): Unit
  }

  // ── the terminals ────────────────────────────────────────────────────────────────────────────

  private def visaBrand(): Unit =
    verified()
    target = terminal("t-visa", "paymentiq", scheme = "VISA")
    terminals(bank, target)

  Given("a brand with an active {string} terminal and the player never deposited by {string}") { (_: String, _: String) =>
    visaBrand()
    deposited("unityfinance", "EUBANKING")
  }

  Given("a brand with an active {string} terminal and the player deposited by {string}") { (_: String, scheme: String) =>
    visaBrand()
    deposited("paymentiq", scheme)
  }

  When("the player's terminals are listed") { () => listed = list() }

  Then("the {string} terminal is absent") { (scheme: String) => assert(!listed.exists(_.text("scheme").contains(scheme)), listed) }
  Then("the {string} terminal is present") { (scheme: String) => assert(listed.exists(_.text("scheme").contains(scheme)), listed) }

  Given("two eligible terminals, one of a payment provider the player deposited with") { () =>
    verified()
    // Named so that by name alone it would come second.
    target = terminal("t-z", "unityfinance").copy(name = "Zeta bank")
    terminals(terminal("t-a", "paymentiq").copy(name = "Alpha bank"), target)
    deposited("unityfinance", "EUBANKING")
  }

  Then("that one is first") { () => assertEquals(ids.headOption, Some(target.id)) }

  Then("the order is the same on every read") { () =>
    (1 to 20).foreach(_ => assertEquals(list().flatMap(_.text("id")), ids))
  }

  Given("a terminal that is inactive") { () =>
    verified()
    kit.wallet.balanceFor(player, 10000L)
    target = terminal("t-off", "unityfinance", destination = true).copy(active = false)
    terminals(bank, target)
  }

  Given("a terminal that does not pay withdrawals") { () =>
    verified()
    kit.wallet.balanceFor(player, 10000L)
    target = terminal("t-in", "unityfinance", destination = true).copy(supportsWithdrawal = false)
    terminals(bank, target)
  }

  Then("the terminal is absent") { () =>
    assert(ids.contains(bank.id), ids)
    assert(!ids.contains(target.id), ids)
  }

  Then("a withdrawal request naming it is refused as not eligible") { () =>
    ask("amount" -> 1000L, "currency" -> currency, "terminalId" -> target.id, "destinationId" -> destination())
    refusedWith(422, "TERMINAL_NOT_ELIGIBLE")
  }

  // ── the destinations ─────────────────────────────────────────────────────────────────────────

  private def split(name: String): (String, String) =
    val parts = name.trim.split("\\s+").toVector
    (parts.init.mkString(" "), parts.last)

  Given("the player's profile names them {string}") { (name: String) =>
    val (first, last) = split(name)
    record().call(PlayerPayouts.seeNames).invoke(SeeNames(Personal(first), Personal(last), kit.now())): Unit
  }

  Given("the player's profile has no name") { () => kyc(Kyc.Verified) }

  private def save(first: String, family: String, default: Boolean = false) =
    last = kit.json("POST", route("/payout-destinations"),
      Json.obj("firstName" -> first, "lastName" -> family, "iban" -> Iban, "label" -> "main", "setAsDefault" -> default), kit.asPlayer(player))
    last

  When("the player saves a destination in the name {string}") { (name: String) =>
    val (first, family) = split(name)
    save(first, family): Unit
  }

  Then("it is saved") { () =>
    assertEquals(last.status, 201, last.text)
    assertEquals((last.str("ibanLastFour"), last.str("isDefault")), ("3000", "true"))
    assert(!last.text.contains(Iban), last.text)
  }

  Then("it is refused as blocked by the name check") { () => refusedWith(422, "PAYOUT_DESTINATION_AML_BLOCKED") }
  Then("it is refused as profile incomplete") { () => refusedWith(422, "PROFILE_INCOMPLETE") }

  private def offered: Vector[JsonNode] =
    val r = kit.request("GET", route("/payout-destinations"), None, kit.asPlayer(player))
    assertEquals(r.status, 200, r.text)
    r.json.objects("destinations")

  Given("two saved destinations, the first the default") { () =>
    named()
    saved = Vector(Given1, Given1).map { n =>
      val r = save(n, Family)
      assertEquals(r.status, 201, r.text)
      r.str("id")
    }
    assertEquals(offered.map(d => (d.textOr("id", ""), d.bool("isDefault"))), Vector(saved(0) -> Some(true), saved(1) -> Some(false)))
  }

  When("the player sets the second as default") { () =>
    last = kit.json("PUT", route(s"/payout-destinations/${saved(1)}"), Json.obj("setAsDefault" -> true), kit.asPlayer(player))
    assertEquals(last.status, 200, last.text)
  }

  Then("the first is no longer default") { () =>
    assertEquals(offered.map(d => (d.textOr("id", ""), d.bool("isDefault"))), Vector(saved(0) -> Some(false), saved(1) -> Some(true)))
  }

  When("the player deletes the first") { () =>
    last = kit.request("DELETE", route(s"/payout-destinations/${saved(0)}"), None, kit.asPlayer(player))
    assertEquals(last.status, 204, last.text)
  }

  Then("it is no longer offered") { () => assert(!offered.exists(_.text("id").contains(saved(0)))) }

  Then("no destination is default") { () => assertEquals(offered.map(_.bool("isDefault")), Vector(Some(false))) }

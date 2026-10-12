package io.eitheror.casino.gameprovider.it

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.contract.facts.*
import io.eitheror.casino.gameprovider.{Json, TestKeys}
import io.eitheror.casino.gameprovider.Json.*
import io.eitheror.casino.gameprovider.features.SharedKit
import io.eitheror.casino.gameprovider.players.PlayerStatus
import io.eitheror.casino.gameprovider.rounds.RoundState
import io.eitheror.casino.gameprovider.tickets.TicketState

import java.time.Instant
import scala.concurrent.duration.*

/** A suite with a service of its own, started and stopped with it, the shared wiring handed back after. */
abstract class OwnKitSuite extends munit.FunSuite with LogCapturing:
  override val munitTimeout: Duration = 300.seconds
  protected var kit: Kit = null
  protected def startKit(): Kit
  override def beforeAll(): Unit = kit = startKit()
  override def afterAll(): Unit =
    if kit != null then kit.stop()
    SharedKit.restore()

/**
 * PlayerStatus over `casino.players` (research R8): every status fact folds into one row per player, and a
 * fact published before the service started is seen, because the view reads the topic from its start.
 */
class PlayerStatusSuite extends OwnKitSuite:
  private val at = Instant.parse("2026-10-01T00:00:00Z")

  protected def startKit(): Kit = Kit(beforeStart = k =>
    k.publishPlayers(PlayerSuspended("early", "active", "suspended", "operator", None, None, at))
  )

  private def row(player: String) = kit.testKit.service.viewClient.forView(PlayerStatus).get(player)

  test("a fact published before the service started is seen (StartFrom.Earliest, ankka 0.11.0)") {
    assertEquals(kit.eventually("early's row")(row("early")).accountStatus, "suspended")
  }

  test("suspended, reinstated, self-excluded and closed fold into one row per player") {
    kit.publishPlayers(PlayerRegistered("p", at, None, None, None, None, None, None, at))
    kit.eventually("registered")(row("p").filter(_.accountStatus == "active"))
    kit.publishPlayers(PlayerSuspended("p", "active", "suspended", "operator", None, None, at.plusSeconds(1)))
    assert(kit.eventually("suspended")(row("p").filter(_.accountStatus == "suspended")).locked)
    kit.publishPlayers(PlayerReinstated("p", "suspended", "active", "operator", None, None, at.plusSeconds(2)))
    assert(!kit.eventually("reinstated")(row("p").filter(_.accountStatus == "active")).locked)
    kit.publishPlayers(PlayerSelfExcluded("p", "active", "self_excluded", "player", None, None, at, None, at.plusSeconds(3)))
    assert(kit.eventually("excluded")(row("p").filter(_.accountStatus == "self_excluded")).locked)
    kit.publishPlayers(PlayerClosed("p", "self_excluded", "closed", "operator", None, None, at.plusSeconds(4)))
    assert(kit.eventually("closed")(row("p").filter(_.accountStatus == "closed")).locked)
  }

  test("a registration after a suspension does not reopen the account") {
    kit.publishPlayers(PlayerSuspended("q", "active", "suspended", "operator", None, None, at))
    kit.eventually("suspended")(row("q").filter(_.locked))
    kit.publishPlayers(PlayerRegistered("q", at, None, None, None, None, None, None, at))
    kit.publishPlayers(PlayerSuspended("marker", "active", "suspended", "operator", None, None, at))
    kit.eventually("the marker after it")(row("marker"))
    assert(row("q").exists(_.locked))
  }

/** FR-026 on a brand that takes real money: the provider's key is held, never fetched. */
class KeyRotationSuite extends OwnKitSuite:
  protected def startKit(): Kit = Kit(realMoney = true)

  test("a real-money brand is refused a key document") {
    val r = kit.configureSt8(keyDocument = true)
    assertEquals(r.status, 400, r.text)
    assertEquals(r.json.text("error"), Some("real_money_brand"))
  }

  test("a real-money brand holding the key verifies under it and ignores any document") {
    assertEquals(kit.configureSt8().status, 204)
    val token = kit.sessionTicket("rm-player")
    kit.wallet.set("rm-player", "EUR", 100)
    kit.st8.documentKeys = Vector(TestKeys.otherPublicDer)
    assertEquals(kit.callback("balance", Json.obj("token" -> token, "player" -> "rm-player")).word, "ok")
    assertEquals(kit.callback("balance", Json.obj("token" -> token, "player" -> "rm-player"), key = TestKeys.otherPrivate).word, "auth_failed")
  }

  test("the configuration is read back without its private key") {
    assertEquals(kit.configureSt8().status, 204)
    val r = kit.request("GET", s"/v1/casino/backoffice/${kit.brand}/game-provider/providers/st8", None, kit.asOperator())
    assertEquals(r.status, 200, r.text)
    assert(!r.text.contains("operatorPrivateKey") && !r.text.contains(TestKeys.testPrivateDer.take(40)), r.text)
    assertEquals(r.json.text("jurisdiction"), Some("MT"))
  }

  test("only an operator of this brand reaches the configuration") {
    val anon   = kit.request("GET", s"/v1/casino/backoffice/${kit.brand}/game-provider/providers/st8")
    val player = kit.request("GET", s"/v1/casino/backoffice/${kit.brand}/game-provider/providers/st8", None, kit.asPlayer("p"))
    val other  = kit.request("GET", s"/v1/casino/backoffice/${kit.brand}/game-provider/providers/st8", None, Seq("Authorization" -> "Bearer staff|op|brand_spinvibe"))
    assertEquals((anon.status, player.status, other.status), (401, 403, 403))
  }

/** Durability: a round, a ticket and an open unacknowledged entry survive losing every entity from memory. */
class DurabilitySuite extends OwnKitSuite:
  protected def startKit(): Kit = Kit()

  test("a round between its debit and its credit, a ticket and an unacknowledged credit survive a restart") {
    assertEquals(kit.configureSt8().status, 204)
    kit.wallet.set("d-1", "EUR", 1000)
    val token = kit.sessionTicket("d-1")
    assertEquals(kit.callback("debit", Json.obj("token" -> token, "transaction_id" -> "d-t1", "round" -> "d-r1", "amount" -> "1.00", "currency" -> "EUR")).word, "ok")
    kit.wallet.down = true
    assertEquals(kit.callback("credit", Json.obj("token" -> token, "transaction_id" -> "d-c1", "round" -> "d-r1", "amount" -> "3.00")).word, "error")
    kit.wallet.down = false

    kit.testKit.restartService()

    assertEquals(kit.ticket(token).status, TicketState.Checked)
    val round = kit.round("d-r1")
    assertEquals(round.openBets.map(_.walletKey), Vector("d-t1"))
    assert(round.openUnacknowledged("d-c1").isDefined)
    val retry = kit.callback("credit", Json.obj("token" -> token, "transaction_id" -> "d-c1", "round" -> "d-r1", "amount" -> "3.00"))
    assertEquals(retry.word, "ok", retry.text)
    assertEquals(kit.round("d-r1").status, RoundState.Settled)
    assertEquals(kit.wallet.openBets("d-1", "EUR"), Vector.empty)
    val repeat = kit.callback("debit", Json.obj("token" -> token, "transaction_id" -> "d-t1", "round" -> "d-r1", "amount" -> "1.00", "currency" -> "EUR"))
    assertEquals(repeat.json.text("balance"), Some("9.00"), "the first answer, remembered across the restart")
  }

  test("a restart while a debit's workflow is between its wallet call and its round resumes it: one bet, recorded, answered") {
    assertEquals(kit.configureSt8().status, 204)
    kit.wallet.set("d-2", "EUR", 1000)
    val token = kit.sessionTicket("d-2")
    val body  = Json.obj("token" -> token, "transaction_id" -> "d-t2", "round" -> "d-r2", "amount" -> "1.00", "currency" -> "EUR")
    // Held in `record-round` long enough for the restart to land after the wallet moved the money (research R18).
    io.eitheror.casino.gameprovider.rounds.BetRuntime.recordDelay = scala.concurrent.duration.Duration(3, "s")
    val inFlight = Thread.ofVirtual().start(() => scala.util.Try(kit.callback("debit", body)): Unit)
    kit.eventually("the bet placed")(kit.wallet.openBets("d-2", "EUR").headOption)
    kit.testKit.restartService()
    io.eitheror.casino.gameprovider.rounds.BetRuntime.recordDelay = scala.concurrent.duration.Duration.Zero
    inFlight.join(10_000)

    // The step the restart interrupted is journalled; ankka 0.11 resumes the instance when it is next
    // addressed (research R18), here by the provider's retry, which is answered once the round has it.
    val lifecycle = kit.testKit.componentClient.forWorkflow(io.eitheror.casino.gameprovider.rounds.BetWorkflow.id("st8", "d-t2"))
      .lifecycle(io.eitheror.casino.gameprovider.rounds.BetWorkflow).invoke()
    assert(!lifecycle.isTerminal, lifecycle.toString)
    val answer = kit.callback("debit", body)
    assertEquals(kit.round("d-r2").openBets.map(_.walletKey), Vector("d-t2"))
    assertEquals((answer.word, answer.json.text("balance")), ("ok", Some("9.00")), answer.text)
    assertEquals(kit.wallet.calls("bets").count(_.json.flatMap(_.text("key")).contains("d-t2")), 1, "the money moved once")
  }

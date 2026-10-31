package io.eitheror.casino.payouts.features

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.testkit.{GherkinSuite, LogCapturing}
import io.eitheror.casino.contract.facts.*
import io.eitheror.casino.contract.personal.Personal
import io.eitheror.casino.payouts.Json
import io.eitheror.casino.payouts.it.{FakeGateway, Kit, Reply, SharedKit}
import io.eitheror.casino.payouts.player.*
import io.eitheror.casino.payouts.queue.WithdrawalQueue
import io.eitheror.casino.payouts.request.{RequestState, WithdrawalRequest}
import io.eitheror.casino.payouts.terminals.{Refresh, Terminal, TerminalCatalogue}
import io.eitheror.casino.payouts.clients.Gateway

import java.util.UUID
import scala.concurrent.duration.*

/**
 * A feature file of features/payouts bound against the whole service (constitution VII): the player's calls
 * through their routes, the wallet and the gateway as the fakes. Each scenario has a player of its own,
 * since the database outlives scenarios. A step that only sets the scene for another story (a saved
 * destination before saving one is the story, a verified player) is made on the entity itself.
 */
abstract class PayoutsFeatures(file: String) extends GherkinSuite(s"../../features/payouts/$file") with LogCapturing:
  override val munitTimeout: scala.concurrent.duration.Duration = 300.seconds

  protected def kit: Kit = SharedKit.kit

  protected var player      = ""
  protected var currency    = "EUR"
  protected var last: Reply = null

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    kit.reset()
    player = newPlayer()
    currency = "EUR"
    last = null

  protected def newPlayer(): String = s"p-${UUID.randomUUID().toString.take(8)}"
  protected def newKey(): String    = UUID.randomUUID().toString

  protected def json(fields: (String, Any)*): JsonNode = Json.obj(fields*)

  // ── the player, as players' and kyc's facts leave the record ─────────────────────────────────

  protected def record(of: String = player) = kit.components.forEventSourcedEntity(EntityId(of))
  protected def state(of: String = player): PlayerState = record(of).call(PlayerPayouts.get).invoke()

  protected val Given1 = "Ana"
  protected val Family = "Silva"
  protected val Iban   = "DE89370400440532013000"

  protected def kyc(status: Kyc, of: String = player): Unit =
    record(of).call(PlayerPayouts.seeKyc).invoke(SeeKyc(status, kit.now())): Unit

  protected def named(of: String = player): Unit =
    record(of).call(PlayerPayouts.seeNames).invoke(SeeNames(Personal(Given1), Personal(Family), kit.now())): Unit
    record(of).call(PlayerPayouts.seeEmail).invoke(SeeEmail(Personal(s"$of@example.test"), kit.now())): Unit

  protected def verified(of: String = player): Unit =
    named(of)
    kyc(Kyc.Verified, of)

  /** A destination saved on the record, as US3's route saves one; its id. */
  protected def destination(of: String = player, default: Boolean = true): String =
    val id = s"d-${UUID.randomUUID().toString.take(8)}"
    val a  = record(of).call(PlayerPayouts.saveDestination).invoke(SaveDestination(id, Given1, Family, Iban, "main", default, kit.now()))
    assertEquals(a.refusal, None)
    id

  // ── the brand's terminals, as the catalogue holds them ───────────────────────────────────────

  protected val bank: Terminal  = terminal("t-bank", "unityfinance", destination = true, scheme = "EUBANKING")

  protected def terminal(id: String, gateway: String, destination: Boolean = false, scheme: String = "EUBANKING",
      coins: Vector[String] = Vector.empty): Terminal =
    Gateway.terminal(FakeGateway.terminal(id, gateway, scheme = scheme, destination = destination, coins = coins)).get

  /** The gateway lists `terminals` in the currency, and the catalogue holds them now (the timer would within a minute). */
  protected def terminals(ts: Terminal*): Unit =
    kit.gateway.terminals(currency, ts.map(t => FakeGateway.terminal(t.id, t.gateway, t.name, t.scheme, t.active,
      t.requiresPayoutDestination, t.coins, withdrawals = t.supportsWithdrawal))*)
    kit.components.forKeyValueEntity(EntityId(kit.slug)).call(TerminalCatalogue.refresh)
      .invoke(Refresh(currency, ts.toVector, kit.now())): Unit

  // ── the player's routes ──────────────────────────────────────────────────────────────────────

  protected def ask(fields: (String, Any)*): Reply =
    val withKey = if fields.exists(_._1 == "key") then fields else fields :+ ("key" -> newKey())
    last = kit.json("POST", kit.playerPath(s"/$player/withdrawal-requests"), json(withKey*), kit.asPlayer(player))
    last

  /** A request with its amount as written in the step, so a decimal reaches the route as one. */
  protected def askRaw(amount: String, fields: (String, Any)*): Reply =
    val rest = Json.render(json((fields :+ ("key" -> newKey()))*))
    last = kit.request("POST", kit.playerPath(s"/$player/withdrawal-requests"), Some(s"""{"amount":$amount,${rest.drop(1)}"""),
      kit.asPlayer(player))
    last

  protected def requests(of: String = player): JsonNode =
    val r = kit.request("GET", kit.playerPath(s"/$of/withdrawal-requests"), None, kit.asPlayer(of))
    assertEquals(r.status, 200, r.text)
    r.json

  protected def requestState(id: String): RequestState =
    kit.components.forEventSourcedEntity(EntityId(id)).call(WithdrawalRequest.get).invoke()

  protected def queue: WithdrawalQueue.Page =
    WithdrawalQueue.page(kit.views.forView(WithdrawalQueue), WithdrawalQueue.Filter(status = None, player = Some(player)), 1, 200)

  // ── what happened ────────────────────────────────────────────────────────────────────────────

  protected def withdrawals: Vector[CasinoWithdrawalsFact] = kit.facts(CasinoWithdrawals).filter(_.playerId == player)

  protected def refusedWith(status: Int, code: String): JsonNode =
    assertEquals((last.status, last.str("error")), (status, code), last.text)
    last.json

  protected def eventually[A](what: String, within: FiniteDuration = 30.seconds)(f: => Option[A]): A = kit.eventually(what, within)(f)

package io.eitheror.casino.payouts.it

import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.contract.personal.Personal
import io.eitheror.casino.payouts.Json
import io.eitheror.casino.payouts.clients.Gateway
import io.eitheror.casino.payouts.player.*
import io.eitheror.casino.payouts.request.{RequestState, WithdrawalRequest}
import io.eitheror.casino.payouts.terminals.{Refresh, TerminalCatalogue}
import io.eitheror.casino.payouts.workflow.{Flow, WithdrawalWorkflow}

import java.util.UUID

/**
 * The scene the payout suites share: the brand's terminals, a player who may withdraw, a request through the
 * player's route, and the operator's approval. Each player is new, since the database outlives a test.
 */
final class Scene(kit: Kit):
  /** Unity Finance with a status to ask, and a terminal with no automated payout. */
  val polled = FakeGateway.terminal("t-polled", "unityfinance", destination = true, hasStatus = true)
  val manual = FakeGateway.terminal("t-manual", "paymentiq", destination = true)

  def terminals(): Unit =
    kit.gateway.terminals("EUR", polled, manual)
    kit.components.forKeyValueEntity(EntityId(kit.slug)).call(TerminalCatalogue.refresh)
      .invoke(Refresh("EUR", Vector(polled, manual).flatMap(Gateway.terminal), kit.now())): Unit

  /** A verified, named player with an email, a saved destination and a balance; their id. */
  def player(prefix: String = "sc", balance: Long = 10000L): String =
    val p      = s"$prefix-${UUID.randomUUID().toString.take(8)}"
    val record = kit.components.forEventSourcedEntity(EntityId(p))
    record.call(PlayerPayouts.seeNames).invoke(SeeNames(Personal("Ana"), Personal("Silva"), kit.now())): Unit
    record.call(PlayerPayouts.seeEmail).invoke(SeeEmail(Personal(s"$p@example.test"), kit.now())): Unit
    record.call(PlayerPayouts.seeKyc).invoke(SeeKyc(Kyc.Verified, kit.now())): Unit
    record.call(PlayerPayouts.saveDestination).invoke(SaveDestination(s"d-$p", "Ana", "Silva", "DE89370400440532013000", "main", true, kit.now())): Unit
    kit.wallet.balanceFor(p, balance)
    p

  /** A request pending review through the player's route; its id. */
  def request(p: String, terminalId: String = "t-polled", amount: Long = 5000L): String =
    val r = kit.json("POST", kit.playerPath(s"/$p/withdrawal-requests"), Json.obj("amount" -> amount, "currency" -> "EUR",
      "terminalId" -> terminalId, "destinationId" -> s"d-$p", "key" -> UUID.randomUUID().toString), kit.asPlayer(p))
    assert(r.status == 201, r.text)
    r.str("requestId")

  def approve(id: String): Reply =
    kit.request("POST", s"/v1/casino/brands/${kit.slug}/withdrawal-requests/$id/approve", Some("{}"),
      kit.asOperator("op-1", s"brand_${kit.slug},brand_finance"))

  def outcome(id: String, outcome: String, reference: String, headers: Seq[(String, String)] = kit.asGateway): Reply =
    kit.json("POST", s"/v1/casino/brands/${kit.slug}/withdrawals/$id/outcome",
      Json.obj("outcome" -> outcome, "providerReference" -> reference), headers)

  def flow(id: String): Flow            = kit.components.forWorkflow(EntityId(id)).call(WithdrawalWorkflow.status).invoke()
  def state(id: String): RequestState   = kit.components.forEventSourcedEntity(EntityId(id)).call(WithdrawalRequest.get).invoke()
  def at(id: String, step: String): Flow = kit.eventually(s"$id at $step")(Some(flow(id)).filter(_.step == step))

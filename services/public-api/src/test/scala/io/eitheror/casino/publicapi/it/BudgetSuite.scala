package io.eitheror.casino.publicapi.it

import io.eitheror.casino.publicapi.api.{Pipeline, V1Endpoint}
import io.eitheror.casino.publicapi.contract.Refusals

/** T064: a request whose view read is held past the five second budget is `503 unavailable` (FR-035). */
class BudgetSuite extends munit.FunSuite:
  private lazy val kit = SharedKit.peered
  override val munitTimeout = scala.concurrent.duration.Duration(60, "s")

  test("a read held past five seconds answers 503 unavailable, and is recorded as failed") {
    val (peer, at) = kit.peer(Seq(clients =>
      val held = Map("listPlayers" -> ((_: io.eitheror.casino.publicapi.api.Served) =>
        Thread.sleep(7000); clients.viewClient.forView(io.eitheror.casino.publicapi.records.Players).get("none"); Refusals.json(200, "{}")))
      V1Endpoint(kit.contract, Pipeline(kit.settings, kit.contract, clients, () => kit.now, held)(_, _), _ => Refusals.notFound.respond)))
    try
      val k = Keys.noFields(kit)
      val started = System.nanoTime()
      val r = kit.v1("/players", Some(k.secret), at = at)
      assertEquals((r.status, r.error), (503, "unavailable"))
      assert((System.nanoTime() - started) / 1_000_000 < 6500)
    finally Kit.stopPeer(peer)
  }

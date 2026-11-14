package io.eitheror.casino.publicapi.it

import io.eitheror.casino.publicapi.api.{Pipeline, V1Endpoint}
import io.eitheror.casino.publicapi.contract.Refusals

import scala.jdk.CollectionConverters.*

/**
 * What the pipeline tells the key's log, read where it is told, not through the audit's consumer (second review
 * round): a made-up key id is told nothing, a key of the brand is, and its refusals only up to the cap a minute. And
 * the service's pipeline is built once, so its budget's threads are bounded.
 */
class RecordedSuite extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(120, "s")
  private lazy val kit = SharedKit.peered

  test("a made-up key id is recorded nowhere; a wrong secret for a key of the brand is recorded against it, up to the cap") {
    val told = java.util.concurrent.ConcurrentLinkedQueue[(String, Int, Int)]()
    val (peer, at) = kit.peer(Seq(clients =>
      V1Endpoint(kit.contract, Pipeline(kit.settings, kit.contract, clients, () => kit.now, Map.empty,
        served = (_, k, answer, _, _, unrecorded) => told.add((k.keyId, answer.status, unrecorded)): Unit).apply,
        _ => Refusals.notFound.respond)))
    try
      val (madeUp, madeUpKey) = io.eitheror.casino.publicapi.keys.RawKeys.mint()
      assertEquals(kit.v1("/contract", Some(madeUpKey), at = at).status, 401)
      assertEquals(told.asScala.toVector, Vector.empty, "a made-up key id is told to no log")
      val d     = Keys.disabled(kit)
      val wrong = s"ak_${d.id}_${madeUpKey.stripPrefix(s"ak_${madeUp}_")}"
      (1 to 62).foreach(_ => assertEquals(kit.v1("/contract", Some(wrong), at = at).status, 401))
      val mine = told.asScala.toVector
      assertEquals(mine.size, io.eitheror.casino.publicapi.api.RefusalCap.PerMinute, "recorded up to the cap a minute")
      assert(mine.forall(_ == (d.id, 401, 0)), mine.toString)
    finally Kit.stopPeer(peer)
  }

  test("requests share the service's pipeline: its budget's threads stop at its workers, however many requests") {
    val k = Keys.noFields(kit)
    def threads = Thread.getAllStackTraces.keySet.asScala.count(_.getName == "public-api-request")
    val before  = threads
    (1 to 100).foreach(_ => assertEquals(kit.v1("/contract", Some(k.secret)).status, 200))
    // A pool starts a thread per task until it holds its workers, then reuses them; one pool per request would not stop.
    assert(threads - before <= io.eitheror.casino.publicapi.api.Deadline.Workers, s"$before threads before 100 requests, $threads after")
  }

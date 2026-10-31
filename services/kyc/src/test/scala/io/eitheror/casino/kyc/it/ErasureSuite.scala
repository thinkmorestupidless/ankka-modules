package io.eitheror.casino.kyc.it

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.kyc.Json
import io.eitheror.casino.kyc.documents.{ObjectNames, Store}
import io.eitheror.casino.kyc.record.*

import java.time.Duration
import java.util.UUID

/**
 * Erasure (FR-033, FR-034, SC-004) against the kit's store: every version under the player's prefix gone,
 * the records' non-personal fields kept, a late object removed by the next call, no certificate while the
 * store cannot be reached, and no other path deleting a recorded document's object.
 */
class ErasureSuite extends munit.FunSuite with LogCapturing:
  override def munitTimeout = scala.concurrent.duration.Duration(180, "s")

  private lazy val kit = SharedKit.kit
  private var player   = ""

  override def beforeEach(context: BeforeEach): Unit =
    kit.reset()
    player = s"p-${UUID.randomUUID().toString.take(8)}"

  override def afterEach(context: AfterEach): Unit = kit.reset()

  private def record = kit.components.forEventSourcedEntity(EntityId(player))
  private def state  = record.call(KycRecord.get).invoke()

  private def offer(category: String, as: Seq[(String, String)] = Nil, base: String = kit.playerPath(s"/$player"), reason: Option[String] = None) =
    val r = kit.json("POST", s"$base/uploads", Json.obj("category" -> category, "contentType" -> "image/png", "reason" -> reason),
      if as.isEmpty then kit.asPlayer(player) else as)
    assertEquals(r.status, 200, r.text)
    (r.str("documentId"), r.str("url"), r.str("objectName"))

  private def submit(category: String, bytes: Int = 64): String =
    val (id, url, _) = offer(category)
    assertEquals(kit.putSigned(url, Array.fill(bytes)(3.toByte), "image/png") / 100, 2)
    val c = kit.json("POST", kit.playerPath(s"/$player/documents"), Json.obj("documentId" -> id), kit.asPlayer(player))
    assertEquals(c.status, 200, c.text)
    id

  private def erase(): Reply = kit.request("POST", kit.servicePath(s"/erasure/players/$player"), None, kit.asService("players"))

  private def present(name: String) = kit.store.head(name).toOption.flatten.isDefined

  test("after erasure no object is left under the player's prefix in any version the store keeps"):
    submit("identity_front"); submit("proof_of_address"); submit("other")
    val names = state.documents.map(_.objectName)
    val r     = erase()
    assertEquals(r.status, 200, r.text)
    assert(names.forall(n => !present(n)))
    assertEquals(kit.store.deleteAll(ObjectNames.prefix(player)), Right(0))

  test("the records' ids, categories, times, statuses and reason codes are unchanged; every personal value blank"):
    val a = submit("identity_front")
    record.call(KycRecord.review).invoke(ReviewDocument(a, Outcome.Rejected, Some("document_unclear"), "op-1", Vector.empty, kit.now())): Unit
    val (sid, surl, _) = offer("proof_of_address", kit.asOperator(), kit.operatorPath(s"/$player"), Some("sent by post"))
    assertEquals(kit.putSigned(surl, Array.fill(64)(3.toByte), "image/png") / 100, 2)
    assertEquals(kit.json("POST", kit.operatorPath(s"/$player/documents"), Json.obj("documentId" -> sid), kit.asOperator()).status, 200)
    def kept(s: KycState) = s.documents.map(d => (d.id, d.category, d.uploadedAt, d.review.map(r => (r.outcome, r.reasonCode, r.at)), d.objectName))
    val before = state
    assertEquals(erase().status, 200)
    val after = state
    assertEquals(kept(after), kept(before))
    assert(after.erased)
    assertEquals(after.documents.flatMap(_.by.reason).map(_.reveal), Vector(""))
    assertEquals(after.profile.adultFrom, None)

  test("an object written after an erasure is removed by the next call, and each call answers a certificate"):
    submit("identity_front")
    assertEquals(erase().json.get("objectsDeleted").asInt, 1)
    val late = s"${ObjectNames.prefix(player)}late.png"
    assertEquals(kit.putSigned(kit.store.signPut(late, "image/png", Duration.ofMinutes(5)).toOption.get, Array.fill(8)(1.toByte), "image/png") / 100, 2)
    val again = erase()
    assertEquals((again.status, again.json.get("objectsDeleted").asInt, again.json.get("recordsKept").asInt), (200, 1, 1))
    assert(!present(late))

  test("a store that cannot be reached answers no certificate and leaves the record as it was"):
    submit("identity_front")
    kit.reset(Store.Unavailable)
    val r = erase()
    assertEquals((r.status, r.str("error")), (503, "storage_unavailable"), r.text)
    assert(!state.erased)

  test("no path but erasure deletes a recorded document's object; a rejected file is deleted before it is recorded"):
    val store = CountingStore(kit.store)
    kit.reset(store)
    val first = submit("identity_front")
    record.call(KycRecord.review).invoke(ReviewDocument(first, Outcome.Rejected, Some("document_unclear"), "op-1", Vector.empty, kit.now())): Unit
    submit("identity_front"): Unit
    val (tooBig, url, objectName) = offer("proof_of_address")
    assertEquals(kit.putSigned(url, Array.fill((kit.limit + 1).toInt)(3.toByte), "image/png") / 100, 2)
    val c = kit.json("POST", kit.playerPath(s"/$player/documents"), Json.obj("documentId" -> tooBig), kit.asPlayer(player))
    assertEquals(c.str("error"), "file_rejected", c.text)
    record.call(KycRecord.close).invoke(Close(kit.now())): Unit
    val recorded = state.documents.map(_.objectName)
    assertEquals(recorded.size, 2)
    assert(recorded.forall(present))
    val deletes = store.calls.filter((op, _) => op == "delete" || op == "deleteAll")
    assertEquals(deletes, Vector("delete" -> objectName))

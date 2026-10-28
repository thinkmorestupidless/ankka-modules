package io.eitheror.casino.ingest.it

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.ingest.requests.{Outcome, RequestsView}
import io.eitheror.casino.ingest.Json

import scala.concurrent.duration.*

/** T049: what the service decided outlives a restart over the same database; the restart's refresh adds nothing. */
class DurabilitySuite extends munit.FunSuite with LogCapturing:
  override val munitTimeout: Duration = 5.minutes
  private lazy val kit = Kit()

  override def afterAll(): Unit = kit.stop()

  test("a session, a key, a raise and a lost answer survive a restart; the refresh on restart issues nothing new") {
    val key   = kit.issueKey()
    kit.merchant.keys = Vector((io.eitheror.casino.ingest.shape.Digests.ofKey(key.raw), key.keyId, key.subBrand))
    val first = kit.create(key, "transactionId" -> "t-1")
    assertEquals(first.status, 200)
    assertEquals(kit.request("PUT", s"/v1/ingest/sub-brands/${key.subBrand}/rate", Some("""{"perMinute":6000}"""), kit.asOperator()).status, 204)
    kit.merchant.hold("t-lost")
    assertEquals(kit.create(key, "transactionId" -> "t-lost").status, 503)
    kit.merchant.release("t-lost")
    val lost = kit.eventually("the lost answer")(RequestsView.lostAnswers(kit.views, Some(key.subBrand)).headOption)
    val keysAsked = kit.merchant.received(FakeMerchant.Keys).size
    val row       = kit.keyRow(key)

    kit.restart()

    assertEquals(kit.merchant.received(FakeMerchant.Keys).size, keysAsked + 1)
    assertEquals(kit.refreshed, 1)
    assertEquals(kit.keyRow(key), row)
    val again = kit.create(key, "transactionId" -> "t-1")
    assertEquals((again.status, again.text), (first.status, first.text))
    assertEquals(kit.merchant.received(FakeMerchant.Create).count(_.json.exists(n => Json.text(n)("transactionId").contains("t-1"))), 1)
    assertEquals(Json.parse(kit.request("GET", s"/v1/ingest/sub-brands/${key.subBrand}/rate", None, kit.asOperator()).text)
      .flatMap(n => Option(n.get("perMinute"))).map(_.asInt), Some(6000))
    val listed = kit.eventually("the lost answer still listed")(RequestsView.lostAnswers(kit.views, Some(key.subBrand)).find(_.recordId == lost.recordId))
    assertEquals(listed.outcome, Outcome.LostAnswer)
  }

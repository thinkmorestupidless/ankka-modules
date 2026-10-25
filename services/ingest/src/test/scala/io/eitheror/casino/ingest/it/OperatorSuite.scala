package io.eitheror.casino.ingest.it

import com.fasterxml.jackson.databind.JsonNode
import io.eitheror.casino.ingest.Json
import io.eitheror.casino.ingest.requests.{Outcome, RecordState}

import java.time.Instant
import java.util.UUID
import scala.jdk.CollectionConverters.*

/** T045 (US5; FR-014, FR-017, SC-007): payments operators read what a sub-brand sent, and the lost answers. */
class OperatorSuite extends munit.FunSuite:
  private lazy val kit = Kit()

  override def afterAll(): Unit = kit.stop()
  override def beforeEach(context: BeforeEach): Unit = kit.reset()

  private def get(path: String, headers: Seq[(String, String)] = kit.asOperator()) = kit.request("GET", path, None, headers)
  private def items(r: Reply, field: String): Vector[JsonNode] = r.json.get(field).elements().asScala.toVector
  private def fields(n: JsonNode): Set[String] = n.fieldNames().asScala.toSet

  test("a sub-brand's requests: four, newest first, data-model's fields only; the unknown key's is under none; from, to and limit bound them") {
    val acme     = s"acme-${UUID.randomUUID().toString.take(6)}"
    val key      = kit.issueKey(acme)
    val disabled = kit.issueKey(acme)
    val started  = Instant.now()
    assertEquals(kit.create(key, "transactionId" -> "t-1").status, 200)
    assertEquals(kit.create(key, "transactionId" -> "t-2").status, 200)
    kit.deactivateKey(disabled)
    assertEquals(kit.create(disabled).status, 401)
    kit.merchant.refuseCreate = Some(400 -> "NO_ROUTING_MATCH")
    assertEquals(kit.create(key, "transactionId" -> "t-3").status, 400)
    kit.create(Some(RawKey("mk_unknown", "k", "x")), kit.body()): Unit

    val all = kit.eventually("four records")(Some(get(s"/v1/ingest/sub-brands/$acme/requests")).filter(r => items(r, "requests").size == 4))
    val rs  = items(all, "requests")
    assertEquals(rs.map(n => Json.text(n)("outcome").get), Vector(Outcome.Refused, Outcome.KeyDisabled, Outcome.Served, Outcome.Served))
    rs.foreach(n => assert(fields(n).subsetOf(RecordState.Fields.toSet), fields(n).toString))
    assert(fields(rs.last).contains("ticket") && fields(rs.last).contains("receivedAt"))

    assertEquals(items(get(s"/v1/ingest/sub-brands/$acme/requests?limit=2"), "requests").size, 2)
    val future = Instant.now().plusSeconds(3600)
    assertEquals(items(get(s"/v1/ingest/sub-brands/$acme/requests?from=$future&to=${future.plusSeconds(60)}"), "requests").size, 0)
    assertEquals(items(get(s"/v1/ingest/sub-brands/$acme/requests?from=${started.minusSeconds(1)}&to=${Instant.now().plusSeconds(1)}"), "requests").size, 4)
  }

  test("by request id: every attempt oldest first, found within 2 s of being served (SC-007)") {
    val key = kit.issueKey()
    kit.merchant.hold("t-1")
    assertEquals(kit.create(key, "transactionId" -> "t-1").status, 503)
    val served  = kit.create(key, "transactionId" -> "t-1")
    val started = System.nanoTime()
    kit.merchant.release("t-1")
    val r = kit.eventually("both attempts", scala.concurrent.duration.DurationInt(2).seconds)(
      Some(get(s"/v1/ingest/sub-brands/${key.subBrand}/requests?requestId=t-1")).filter(r => items(r, "requests").size == 2))
    println(s"SC-007: found ${(System.nanoTime() - started) / 1_000_000} ms after being served")
    assertEquals(items(r, "requests").map(n => Json.text(n)("outcome").get).last, Outcome.Served)
    assertEquals(items(r, "requests").head.get("attempt").asInt, 1)
    assertEquals(served.status, 200)
  }

  test("the lost answers: listed with the late ticket, filtered by sub-brand, gone once seen; seen refuses another outcome and no record") {
    val key = kit.issueKey()
    kit.merchant.hold("t-lost")
    assertEquals(kit.create(key, "transactionId" -> "t-lost").status, 503)
    kit.merchant.release("t-lost")
    val listed = kit.eventually("the lost answer listed")(items(get(s"/v1/ingest/lost-answers?subBrand=${key.subBrand}"), "lostAnswers").headOption)
    assert(Json.text(listed.get("lostAnswer"))("ticket").exists(_.nonEmpty), listed.toString)
    assertEquals(items(get(s"/v1/ingest/lost-answers?subBrand=nobody"), "lostAnswers"), Vector.empty)
    val id = Json.text(listed)("recordId").get
    assertEquals(kit.request("POST", s"/v1/ingest/lost-answers/$id/seen", None, kit.asOperator()).status, 204)
    kit.eventually("gone from the list")(Some(items(get(s"/v1/ingest/lost-answers?subBrand=${key.subBrand}"), "lostAnswers")).filter(_.isEmpty)): Unit

    assertEquals(kit.create(key, "transactionId" -> "t-ok").status, 200)
    val served = kit.recordWhere(key.subBrand)(_.requestId.contains("t-ok"))
    assertEquals(kit.request("POST", s"/v1/ingest/lost-answers/${served.recordId}/seen", None, kit.asOperator()).status, 409)
    assertEquals(kit.request("POST", s"/v1/ingest/lost-answers/no-such-record/seen", None, kit.asOperator()).status, 404)
  }

  test("each operator route: a staff token without payments 403, no token 401") {
    Seq("GET" -> "/v1/ingest/sub-brands/x/requests", "GET" -> "/v1/ingest/lost-answers", "POST" -> "/v1/ingest/lost-answers/x/seen",
      "GET" -> "/v1/ingest/sub-brands/x/rate", "PUT" -> "/v1/ingest/sub-brands/x/rate").foreach { (m, p) =>
      val body = Option.when(m == "PUT")("""{"perMinute":10}""")
      assertEquals(kit.request(m, p, body, kit.asStaff).status, 403, s"$m $p")
      assertEquals(kit.request(m, p, body, kit.asNobody).status, 401, s"$m $p")
    }
  }

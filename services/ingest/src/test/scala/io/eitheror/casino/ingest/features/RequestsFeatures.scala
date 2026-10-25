package io.eitheror.casino.ingest.features

import com.fasterxml.jackson.databind.JsonNode
import io.eitheror.casino.ingest.Json
import io.eitheror.casino.ingest.it.RawKey
import io.eitheror.casino.ingest.requests.{Outcome, RecordState}

import scala.jdk.CollectionConverters.*

/** features/payments/requests.feature (spec 006 US5): an operator reads what a sub-brand sent. */
class RequestsFeatures extends IngestFeatures("requests.feature"):

  private val personal = Vector("Ada", "Lovelace", "ada@example.com", "+15550100", "1 King St", "203.0.113.5")
  private var listed   = Vector.empty[JsonNode]
  private var headers  = Seq.empty[(String, String)]

  private def requests(subBrand: String, as: Seq[(String, String)] = kit.asOperator()) =
    kit.request("GET", s"/v1/ingest/sub-brands/$subBrand/requests", None, as)

  Given("a sub-brand that sent four creates, two served, one refused for its key and one refused for its routing") { () =>
    key = kit.issueKey()
    val disabled = kit.issueKey(key.subBrand)
    kit.deactivateKey(disabled)
    val customer = Seq("customerFirstName" -> "Ada", "customerLastName" -> "Lovelace", "customerEmail" -> "ada@example.com",
      "customerPhone" -> "+15550100", "customerAddress" -> "1 King St")
    def send(k: RawKey) = kit.create(Some(k), kit.body(customer*), "X-Forwarded-For" -> "203.0.113.5")
    send(key): Unit
    send(key): Unit
    send(disabled): Unit
    kit.merchant.refuseCreate = Some(400 -> "NO_ROUTING_MATCH")
    send(key): Unit
  }
  When("an operator reads the sub-brand's recent requests") { () =>
    val r = kit.eventually("four listed")(Some(requests(key.subBrand)).filter(r => r.json.get("requests").size == 4))
    replies = Vector(r)
    listed = r.json.get("requests").elements().asScala.toVector
  }
  Then("all four are listed with their time, request id, amount, currency, country and outcome") { () =>
    listed.foreach(n => Seq("receivedAt", "requestId", "amount", "currency", "country", "outcome").foreach(f => assert(n.has(f), s"$f: $n")))
    assertEquals(listed.map(n => Json.text(n)("outcome").get).sorted,
      Vector(Outcome.KeyDisabled, Outcome.Refused, Outcome.Served, Outcome.Served).sorted)
  }
  Then("none shows a player's name, email address, telephone number, postal address or network address") { () =>
    personal.foreach(p => assert(!replies.head.text.contains(p), p))
    listed.foreach(n => assert(n.fieldNames().asScala.toSet.subsetOf(RecordState.Fields.toSet)))
  }

  Given("a request the cashier accepted whose answer could not be returned to the sub-brand") { () =>
    key = kit.issueKey()
    kit.merchant.hold(id("t-lost"))
    assertEquals(kit.create(key, "transactionId" -> id("t-lost")).status, 503)
    kit.merchant.release(id("t-lost"))
  }
  When("an operator reads the lost answers") { () =>
    listed = kit.eventually("the lost answer listed")(Some(kit.request("GET", s"/v1/ingest/lost-answers?subBrand=${key.subBrand}", None, kit.asOperator()))
      .map(_.json.get("lostAnswers").elements().asScala.toVector).filter(_.nonEmpty))
  }
  Then("the request is listed with the cashier session or the payment the cashier made") { () =>
    assert(Json.text(listed.head.get("lostAnswer"))("ticket").exists(_.nonEmpty), listed.head.toString)
  }
  Then("the operator can mark it seen") { () =>
    val id = Json.text(listed.head)("recordId").get
    assertEquals(kit.request("POST", s"/v1/ingest/lost-answers/$id/seen", None, kit.asOperator()).status, 204)
  }

  Given("an operator whose roles do not name the shared payments") { () => headers = kit.asStaff }
  When("they read a sub-brand's recent requests") { () => last = requests("any", headers) }
  Then("they are refused") { () => assertEquals(last.status, 403) }

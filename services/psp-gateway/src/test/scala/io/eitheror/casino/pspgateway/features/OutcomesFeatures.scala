package io.eitheror.casino.pspgateway.features

import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.pspgateway.Json.text
import io.eitheror.casino.pspgateway.it.{Reply, Scripted}
import io.eitheror.casino.pspgateway.sessions.*

import java.time.Duration as JDuration
import java.util.UUID
import scala.jdk.CollectionConverters.*

/**
 * features/payments/outcomes.feature (US4). The cashier is `FakeMerchant`; Unity Finance settles the
 * deposit. On the shared kit the timer runs every two minutes, so "the next re-drive runs" sends the
 * command the timer sends (`redrive` by `timer`); `RedriveSuite` holds the timer itself on its own kit.
 */
class OutcomesFeatures extends GatewayFeatures("outcomes.feature"):

  private var calls = 0

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    provider = Provider.UnityFinance
    calls = 0

  private def deliveredTo = kit.merchant.deliveredTo(paymentId)
  private def delivery    = kit.deposit(paymentId).delivery

  private def captured(): Unit =
    kit.psp.control(provider, "pending")
    paymentId = UUID.randomUUID().toString
    val r = kit.initiate(paymentId, provider)
    assertEquals(r.status, 200, r.text)
    kit.psp.webhook(provider, kit.stubReference(paymentId, provider), "outcome" -> "success"): Unit

  private def until(what: String)(p: Delivery => Boolean): Delivery =
    kit.eventually(s"$paymentId $what")(Some(delivery).filter(p))

  private def listed(path: String): Vector[com.fasterxml.jackson.databind.JsonNode] =
    val r = kit.request("GET", s"/v1/psp/outcomes/$path", None, kit.asOperator())
    assertEquals(r.status, 200, r.text)
    r.json.path("outcomes").elements.asScala.toVector

  private def row(path: String) =
    kit.eventually(s"$paymentId listed $path")(listed(path).find(_.text("reference").contains(paymentId)))

  private def redriveAsOperator(): Reply =
    val r = kit.request("POST", s"/v1/psp/outcomes/$paymentId/redrive", None, kit.asOperator(subject = "op-7"))
    assertEquals(r.status, 202, r.text)
    r

  private def redriveAsTimer(): Unit =
    kit.components.forEventSourcedEntity(EntityId(paymentId)).call(DepositSession.redrive)
      .invoke(Redrive(DepositSession.Timer, kit.now())): Unit

  // Retried with backoff until the cashier answers.

  Given("the cashier answering a server error or not at all") { () => kit.merchant.answer = Scripted.Pending }
  When("a capture outcome is delivered to it") { () => captured() }
  Then("delivery is retried with backoff") { () =>
    kit.eventually("a first attempt")(Option.when(deliveredTo.nonEmpty)(()))
    kit.merchant.answer = Scripted.Ok
  }
  Then("it succeeds once the cashier answers") { () =>
    until("delivered")(_.isInstanceOf[Delivery.Delivered]): Unit
    assert(kit.deposit(paymentId).attempts >= 2, kit.deposit(paymentId).toString)
  }

  // Refused as a wrong request.

  Given("the cashier answering that the request itself is wrong") { () => kit.merchant.answer = Scripted.BadRequest }
  Then("it is not retried") { () =>
    until("refused")(_.isInstanceOf[Delivery.Refused]): Unit
    Thread.sleep(1000)
    assertEquals(deliveredTo.size, 1)
  }
  Then("the provider session shows the outcome refused, with the answer") { () =>
    val read = kit.session(paymentId)
    assertEquals((read.status, read.json.path("refused").asBoolean), (200, true), read.text)
    assert(row("refused").text("lastAnswer").exists(_.contains("400")), listed("refused").toString)
  }

  // Exhausted and listable.

  Given("a capture outcome whose retries are exhausted") { () =>
    kit.merchant.answer = Scripted.Pending
    captured()
  }
  When("its retry budget is spent") { () => until("undelivered")(_.isInstanceOf[Delivery.Undelivered]): Unit }
  Then("the provider session shows the outcome undelivered, with its kind, its receiver and its content") { () =>
    val read = kit.session(paymentId)
    assertEquals((read.status, read.json.path("undelivered").asBoolean), (200, true), read.text)
    val r = row("undelivered")
    assertEquals((r.text("kind"), r.text("receiver"), r.text("outcome")),
      (Some(SessionRow.Deposit), Some(Receiver.Merchant(paymentId).describe), Some("captured")), r.toString)
  }
  Then("the undelivered outcomes are listable") { () => assert(listed("undelivered").nonEmpty) }
  Then("the outcome is re-driven every two minutes until it is delivered or refused") { () =>
    assertEquals(kit.settings.redriveInterval, JDuration.ofMinutes(2))
    calls = deliveredTo.size
    redriveAsTimer()
    kit.eventually("re-driven")(Option.when(deliveredTo.size > calls)(()))
    until("undelivered again")(d => d.isInstanceOf[Delivery.Undelivered] && kit.deposit(paymentId).redriving.isEmpty): Unit
    kit.merchant.answer = Scripted.Ok
    redriveAsTimer()
    until("delivered")(_.isInstanceOf[Delivery.Delivered]): Unit
  }

  // Delivered once, by an operator or by the re-drive after the receiver recovers.

  Given("an undelivered outcome") { () =>
    kit.merchant.answer = Scripted.Pending
    captured()
    until("undelivered")(_.isInstanceOf[Delivery.Undelivered]): Unit
  }
  When("an operator re-drives it") { () =>
    kit.merchant.answer = Scripted.Ok
    calls = deliveredTo.size
    redriveAsOperator(): Unit
  }
  When("the receiver recovers and the next re-drive runs") { () =>
    kit.merchant.answer = Scripted.Ok
    calls = deliveredTo.size
    redriveAsTimer()
  }
  Then("it is delivered once") { () =>
    until("delivered")(_.isInstanceOf[Delivery.Delivered]): Unit
    Thread.sleep(500)
    assertEquals(deliveredTo.size, calls + 1)
  }
  Then("the provider session shows it delivered") { () =>
    val read = kit.session(paymentId)
    assertEquals((read.status, read.json.path("delivered").asBoolean), (200, true), read.text)
  }

  // A refused outcome is left by the timer.

  Given("a refused outcome") { () =>
    kit.merchant.answer = Scripted.BadRequest
    captured()
    until("refused")(_.isInstanceOf[Delivery.Refused]): Unit
  }
  When("the next re-drive runs") { () =>
    kit.merchant.answer = Scripted.Ok
    calls = deliveredTo.size
    redriveAsTimer()
  }
  Then("it is not delivered") { () =>
    Thread.sleep(1000)
    assertEquals(deliveredTo.size, calls)
    assert(delivery.isInstanceOf[Delivery.Refused], delivery.toString)
  }

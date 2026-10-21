package io.eitheror.casino.pspgateway.it

import io.eitheror.casino.pspgateway.Json.text
import io.eitheror.casino.pspgateway.sessions.*

import java.util.UUID
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * US4, FR-015 and FR-016: an outcome its receiver does not take is recorded undelivered, listed for the
 * payments operators, and delivered once by the `Redrive` timer when the receiver is back; a refused one
 * is listed apart, left by the timer, and delivered by an operator's re-drive. A service of its own (its
 * own JVM, build.sbt), since the timer runs every 3 s here and the suite stops a receiver.
 */
class RedriveSuite extends munit.FunSuite:
  override val munitTimeout: Duration = 5.minutes

  private val Every = java.time.Duration.ofSeconds(3)
  private lazy val kit = Kit.withStub(redriveInterval = Every)
  private val provider = Provider.UnityFinance
  private val brand    = "goldvibe"

  override def beforeAll(): Unit = kit: Unit
  override def afterAll(): Unit  = kit.stop()

  override def beforeEach(context: BeforeEach): Unit =
    kit.reset()
    Provider.values.foreach(kit.credential("sb-1", _))
    kit.payoutTerminals(brand)

  /** A deposit settled captured by its webhook while merchant answers as `answer`. */
  private def settled(answer: Scripted): String =
    kit.merchant.answer = answer
    kit.psp.control(provider, "pending")
    val paymentId = UUID.randomUUID().toString
    val r         = kit.initiate(paymentId, provider)
    assertEquals(r.status, 200, r.text)
    kit.psp.webhook(provider, kit.stubReference(paymentId, provider), "outcome" -> "success"): Unit
    paymentId

  private def deliveryOf(paymentId: String): Delivery = kit.deposit(paymentId).delivery

  private def until(what: String)(p: Delivery => Boolean)(paymentId: String): Delivery =
    kit.eventually(s"$paymentId $what", 60.seconds)(Some(deliveryOf(paymentId)).filter(p))

  private def listed(path: String): Vector[String] =
    val r = kit.request("GET", s"/v1/psp/outcomes/$path", None, kit.asOperator())
    assertEquals(r.status, 200, r.text)
    r.json.path("outcomes").elements.asScala.toVector.flatMap(_.text("reference"))

  private def redrive(reference: String): Reply =
    kit.request("POST", s"/v1/psp/outcomes/$reference/redrive", None, kit.asOperator(subject = "op-7"))

  test("twenty outcomes merchant does not take are undelivered, listed, and each delivered by the timer once merchant is back") {
    val ids = Vector.fill(20)(settled(Scripted.Pending))
    ids.foreach(until("undelivered")(_.isInstanceOf[Delivery.Undelivered]))
    kit.eventually("all listed undelivered")(Option.when(ids.forall(listed("undelivered").contains))(()))
    val read = kit.session(ids.head)
    assertEquals((read.status, read.json.path("undelivered").asBoolean), (200, true), read.text)

    kit.merchant.answer = Scripted.Ok
    ids.foreach(until("delivered")(_.isInstanceOf[Delivery.Delivered]))
    val calls = ids.map(kit.merchant.deliveredTo(_).size)
    Thread.sleep(Every.toMillis * 2)
    assertEquals(ids.map(kit.merchant.deliveredTo(_).size), calls, "a delivered outcome is not re-driven")
    ids.foreach(id => assert(kit.deposit(id).redrives >= 1, kit.deposit(id).toString))
    kit.eventually("none listed")(Option.when(!listed("undelivered").exists(ids.contains))(()))
  }

  test("a refused outcome is listed apart, left by the timer, and delivered by an operator's re-drive") {
    val paymentId = settled(Scripted.BadRequest)
    val refused   = until("refused")(_.isInstanceOf[Delivery.Refused])(paymentId)
    assert(refused.toString.contains("400"), refused.toString)
    kit.eventually("listed refused")(Option.when(listed("refused").contains(paymentId))(()))
    assert(!listed("undelivered").contains(paymentId))

    val calls = kit.merchant.deliveredTo(paymentId).size
    Thread.sleep(Every.toMillis * 2)
    assertEquals(kit.merchant.deliveredTo(paymentId).size, calls, "the timer leaves a refused outcome")
    assert(deliveryOf(paymentId).isInstanceOf[Delivery.Refused])

    kit.merchant.answer = Scripted.Ok
    val r = redrive(paymentId)
    assertEquals((r.status, r.json.path("redriven").asBoolean), (202, true), r.text)
    until("delivered")(_.isInstanceOf[Delivery.Delivered])(paymentId): Unit
    assertEquals(kit.merchant.deliveredTo(paymentId).size, calls + 1)
    assertEquals(kit.deposit(paymentId).redriving, None)
  }

  test("a delivered outcome is not re-drivable, and an unknown one is not found") {
    val paymentId = settled(Scripted.Ok)
    until("delivered")(_.isInstanceOf[Delivery.Delivered])(paymentId): Unit
    val r = redrive(paymentId)
    assertEquals((r.status, r.str("error")), (409, "not_redrivable"), r.text)
    assertEquals(redrive(UUID.randomUUID().toString).status, 404)
  }

  test("only a payments operator lists or re-drives") {
    assertEquals(kit.request("GET", "/v1/psp/outcomes/undelivered", None, kit.asMerchant).status / 100, 4)
    assertEquals(kit.request("GET", "/v1/psp/outcomes/undelivered", None, kit.asBrowser).status, 401)
  }

  test("a payout outcome the brand does not take is undelivered, listed, and delivered by the timer") {
    kit.psp.control(provider, "pending")
    kit.payouts.answer = Scripted.Pending
    val requestId = s"w-${UUID.randomUUID()}"
    val r         = kit.withdraw(brand, requestId, provider)
    assertEquals(r.status, 200, r.text)
    kit.psp.webhook(provider, requestId, "outcome" -> "success"): Unit
    kit.eventually(s"$requestId undelivered", 60.seconds)(Some(kit.payout(requestId).delivery).collect { case d: Delivery.Undelivered => d })
    kit.eventually("listed undelivered")(Option.when(listed("undelivered").contains(requestId))(()))

    kit.payouts.answer = Scripted.Ok
    kit.eventually(s"$requestId delivered", 60.seconds)(Some(kit.payout(requestId).delivery).collect { case d: Delivery.Delivered => d })
    assertEquals(kit.payouts.delivered(brand).count(_.key == s"$requestId/completed") >= 1, true)
  }

  test("a payout outcome refused by a brand whose grant is revoked is listed refused and delivered by an operator's re-drive") {
    kit.psp.control(provider, "pending")
    kit.payouts.revoked = Set(brand)
    val requestId = s"w-${UUID.randomUUID()}"
    assertEquals(kit.withdraw(brand, requestId, provider).status, 200)
    kit.psp.webhook(provider, requestId, "outcome" -> "success"): Unit
    kit.eventually(s"$requestId refused", 60.seconds)(Some(kit.payout(requestId).delivery).collect { case d: Delivery.Refused => d })
    kit.eventually("listed refused")(Option.when(listed("refused").contains(requestId))(()))
    Thread.sleep(Every.toMillis * 2)
    assert(kit.payout(requestId).delivery.isInstanceOf[Delivery.Refused], "the timer leaves it")

    kit.payouts.revoked = Set.empty
    assertEquals(redrive(requestId).status, 202)
    kit.eventually(s"$requestId delivered", 60.seconds)(Some(kit.payout(requestId).delivery).collect { case d: Delivery.Delivered => d })
    assertEquals(kit.payouts.delivered(brand).count(_.key == s"$requestId/completed"), 1)
  }

  test("the status page counts the undelivered and the refused") {
    val stuck = settled(Scripted.Pending)
    until("undelivered")(_.isInstanceOf[Delivery.Undelivered])(stuck): Unit
    val first = kit.request("GET", "/status", None, kit.asService("local", "ingest"))
    assertEquals(first.status, 200, first.text)
    kit.eventually("counted") {
      val s = kit.request("GET", "/status", None, kit.asService("local", "ingest"))
      Option.when(s.json.path("undelivered").asLong >= 1 && s.json.has("refused"))(s)
    }: Unit
    kit.merchant.answer = Scripted.Ok
    until("delivered")(_.isInstanceOf[Delivery.Delivered])(stuck): Unit
  }

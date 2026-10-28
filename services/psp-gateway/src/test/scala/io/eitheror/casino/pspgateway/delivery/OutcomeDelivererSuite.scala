package io.eitheror.casino.pspgateway.delivery

import com.thinkmorestupidless.ankka.core.effect.ConsumerEffect
import com.thinkmorestupidless.ankka.testkit.{ConsumerTestKit, LogCapturing, ScriptedServices, TestTransport}
import io.eitheror.casino.contract.money.Currency
import io.eitheror.casino.pspgateway.Json
import io.eitheror.casino.pspgateway.Json.*
import io.eitheror.casino.pspgateway.sessions.*

import java.time.{Duration, Instant}
import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*

/** FR-015, R13, data-model.md's deliverer: what reaches the receiver, and what is recorded of it. */
class OutcomeDelivererSuite extends munit.FunSuite with LogCapturing:

  private val at = Instant.parse("2026-10-18T12:00:00Z")

  override def beforeEach(context: BeforeEach): Unit = DeliveryRuntime.sleep = _ => ()

  // The schedule, without a receiver or a clock.

  private def schedule(answers: Answer*): (Delivery, Int, Vector[Duration]) =
    val left  = answers.iterator
    val slept = Vector.newBuilder[Duration]
    val (d, n) = Deliver(() => left.next(), () => at, slept += _)
    (d, n, slept.result())

  test("a 2xx is delivered at the first attempt, with no wait") {
    val (d, n, slept) = schedule(Answer(Some(200), ""))
    assertEquals(d, Delivery.Delivered(at, 200))
    assertEquals((n, slept), (1, Vector.empty))
  }

  test("503 OUTCOME_PENDING, 429 and silence are tried again after 250 ms, 500 ms and 1 s") {
    val (d, n, slept) = schedule(Answer(Some(503), "OUTCOME_PENDING"), Answer(Some(429), ""), Answer(None, "silent"),
      Answer(Some(204), ""))
    assertEquals(d, Delivery.Delivered(at, 204))
    assertEquals(n, 4)
    assertEquals(slept, Vector(250L, 500L, 1000L).map(Duration.ofMillis))
  }

  test("a client error is refused at once and recorded with its answer") {
    val (d, n, _) = schedule(Answer(Some(400), "invalid_state"))
    assertEquals(d, Delivery.Refused(at, 400, "400 invalid_state"))
    assertEquals(n, 1)
  }

  test("four answers short of an acknowledgement are undelivered, with the last answer") {
    val (d, n, _) = schedule(Seq.fill(4)(Answer(None, "silent"))*)
    assertEquals(d, Delivery.Undelivered(at, 4, "silent"))
    assertEquals(n, 4)
  }

  test("the budget ends the attempts before the fourth when the receiver is slow") {
    var clock = at
    val (d, n) = Deliver(() => { clock = clock.plusSeconds(8); Answer(None, "timed out") }, () => clock, _ => ())
    assertEquals(n, 2, "8 s and 16 s in: a third would start past 15 s")
    assert(d.isInstanceOf[Delivery.Undelivered], d)
  }

  // The consumer, over scripted sessions and neighbours.

  private val claim = DepositClaim(Provider.DreamzPay, "VISA", "sb-1", None, "c-1", 1000, Currency.unsafe("EUR"),
    "https://gw/return", "https://gw/hook")

  private def deposit(outcome: Outcome, delivery: Delivery = Delivery.NotYet) =
    DepositState("p-1", Some(claim), Some(Initiation(Some("ref-9"), Destination.Redirect("https://psp"), Map.empty, at)),
      outcome = Some(outcome), providerStatus = Some("Y"), settledAt = Some(at), delivery = delivery)

  private final class Rig(state: DepositState, credential: String, answer: Int = 200):
    val recorded = ConcurrentLinkedQueue[RecordDelivery]()
    val services = ScriptedServices().answer("merchant") { r =>
      if r.method == "GET" then ScriptedServices.json(credential)
      else ScriptedServices.json("{}", answer)
    }
    private val transport = TestTransport()
      .stub(DepositSession.get)(() => state)
      .stub(DepositSession.recordDelivery) { c => recorded.add(c); state.copy(delivery = c.delivery) }
    val kit = ConsumerTestKit.of(DepositDeliverer, transport.client, services = services)
    def posts = services.requests.filter(_.method == "POST")
    def settle = kit.onMessage(DepositEvent.Settled(state.outcome.get, "Y", at), subject = "p-1")

  test("a capture is sent to merchant as 004's gateway.md body and recorded delivered") {
    val rig = Rig(deposit(Outcome.Captured()), """{"credentials":{"memberId":"m"}}""")
    assertEquals(rig.settle.effect, ConsumerEffect.Done)
    val post = rig.posts.loneElement
    assertEquals(post.path, "/v1/payments/p-1/capture")
    val body = Json.parseObject(post.text).get
    assertEquals(body.text("outcome"), Some("success"))
    assertEquals(body.text("psp_transaction_id"), Some("ref-9"))
    val r = rig.recorded.asScala.toVector.loneElement
    assertEquals((r.delivery.word, r.attempts), ("Delivered", 1))
  }

  test("a failure is sent as the fail body with the provider's code and reason") {
    val rig = Rig(deposit(Outcome.Failed(Some("05"), "Declined by bank")), """{"credentials":{}}""")
    rig.settle
    val post = rig.posts.loneElement
    assertEquals(post.path, "/v1/payments/p-1/fail")
    val body = Json.parseObject(post.text).get
    assertEquals((body.text("failureCode"), body.text("failureReason")), (Some("05"), Some("Declined by bank")))
  }

  test("signed with X-PSP-Signature and X-PSP-Timestamp when the credential has a webhookSecret, neither otherwise") {
    val signed = Rig(deposit(Outcome.Captured()), """{"credentials":{"webhookSecret":"shh"}}""")
    signed.settle
    val names = signed.posts.loneElement.headers.map(_._1.toLowerCase).toSet
    assert(names.contains("x-psp-signature") && names.contains("x-psp-timestamp"), names)
    val sig = signed.posts.loneElement.headers.collectFirst { case (k, v) if k.equalsIgnoreCase("x-psp-signature") => v }
    val ts  = signed.posts.loneElement.headers.collectFirst { case (k, v) if k.equalsIgnoreCase("x-psp-timestamp") => v }
    assertEquals(Signature.sign(signed.posts.loneElement.text, "shh", Instant.ofEpochSecond(ts.get.toLong)).toMap.get(Signature.SignatureHeader), sig)

    val unsigned = Rig(deposit(Outcome.Captured()), """{"credentials":{"memberId":"m"}}""")
    unsigned.settle
    assert(!unsigned.posts.loneElement.headers.exists(_._1.toLowerCase.startsWith("x-psp-")))
  }

  test("a session already delivered sends nothing on a later event") {
    val rig = Rig(deposit(Outcome.Captured(), Delivery.Delivered(at, 200)), """{"credentials":{}}""")
    assertEquals(rig.settle.effect, ConsumerEffect.Ignore)
    assert(rig.services.requests.isEmpty)
    assert(rig.recorded.isEmpty)
  }

  test("events before the settlement are ignored") {
    val rig = Rig(deposit(Outcome.Captured()), """{"credentials":{}}""")
    assertEquals(rig.kit.onMessage(DepositEvent.Claimed(claim, at), subject = "p-1").effect, ConsumerEffect.Ignore)
    assert(rig.services.requests.isEmpty)
  }

  test("a refusal and an unreadable credential are recorded, and the handler is done either way") {
    val refused = Rig(deposit(Outcome.Captured()), """{"credentials":{}}""", answer = 400)
    assertEquals(refused.settle.effect, ConsumerEffect.Done)
    assertEquals(refused.recorded.asScala.toVector.loneElement.delivery.word, "Refused")

    val down = Rig(deposit(Outcome.Captured()), "not json")
    assertEquals(down.settle.effect, ConsumerEffect.Done)
    assertEquals(down.recorded.asScala.toVector.loneElement.delivery.word, "Undelivered")
    assert(down.posts.isEmpty, "nothing is sent unsigned when the credential cannot be read")
  }

  test("a payout's outcome goes to the brand's payouts as contracts/payouts.md's body, unsigned") {
    val payout = PayoutState("w-1", Some(PayoutClaim("goldvibe", "sb-1", Provider.Cozeni, "t-1", "USDT", 5000,
      Currency.unsafe("USD"), "https://gw/hook")), outcome = Some(Outcome.Completed), providerReference = Some("cz-7"))
    val recorded = ConcurrentLinkedQueue[RecordDelivery]()
    val services = ScriptedServices().answer("payouts", "goldvibe")(_ => ScriptedServices.json("{}"))
    val transport = TestTransport()
      .stub(PayoutSession.get)(() => payout)
      .stub(PayoutSession.recordDelivery) { c => recorded.add(c); payout.copy(delivery = c.delivery) }
    val kit = ConsumerTestKit.of(PayoutDeliverer, transport.client, services = services)
    assertEquals(kit.onMessage(PayoutEvent.Settled(Outcome.Completed, Some("cz-7"), "done", at), subject = "w-1").effect,
      ConsumerEffect.Done)
    val post = services.requests.loneElement
    val body = Json.parseObject(post.text).get
    assertEquals((post.project, body.text("outcome"), body.text("providerReference")), ("goldvibe", Some("completed"), Some("cz-7")))
    assert(!post.headers.exists(_._1.toLowerCase.startsWith("x-psp-")))
    assertEquals(recorded.asScala.toVector.loneElement.delivery.word, "Delivered")
  }

  extension [A](v: Vector[A])
    private def loneElement: A =
      assertEquals(v.size, 1, v)
      v.head

  // T051: the retries, the re-drive and a replay, at the consumer.

  /**
   * merchant answering `statuses` in turn (the last repeated), and a session that records as the entity
   * does: a record only while `deliverable`. `get` answers the state as it was read before the first
   * record, as a replay after a crash would read it.
   */
  private final class Sequence(start: DepositState, statuses: Int*):
    @volatile var state = start
    private val left    = statuses.iterator
    @volatile private var last = statuses.head
    val recorded = ConcurrentLinkedQueue[RecordDelivery]()
    val services = ScriptedServices().answer("merchant") { r =>
      if r.method == "GET" then ScriptedServices.json("""{"credentials":{}}""")
      else
        last = if left.hasNext then left.next() else last
        ScriptedServices.json(if last / 100 == 2 then "{}" else s"""{"error":"E$last"}""", last)
    }
    private val transport = TestTransport()
      .stub(DepositSession.get)(() => start)
      .stub(DepositSession.recordDelivery) { c =>
        if state.deliverable then
          recorded.add(c)
          state = state.copy(delivery = c.delivery, attempts = state.attempts + c.attempts, redriving = None)
        state
      }
    val kit = ConsumerTestKit.of(DepositDeliverer, transport.client, services = services)
    def posts = services.requests.filter(_.method == "POST")
    def on(event: DepositEvent) = kit.onMessage(event, subject = "p-1").effect
    def settle = on(DepositEvent.Settled(start.outcome.get, "Y", at))
    def only = recorded.asScala.toVector.loneElement

  test("503 four times is recorded undelivered after 4 attempts, with the last answer") {
    val s = Sequence(deposit(Outcome.Captured()), 503)
    assertEquals(s.settle, ConsumerEffect.Done)
    assertEquals(s.posts.size, 4)
    val r = s.only
    assertEquals(r.attempts, 4)
    r.delivery match
      case Delivery.Undelivered(_, n, answer) => assertEquals(n, 4); assert(answer.startsWith("503"), answer)
      case d                                  => fail(s"not undelivered: $d")
  }

  test("503 then 200 is delivered after 2 attempts; 429 is retried too") {
    val s = Sequence(deposit(Outcome.Captured()), 503, 200)
    s.settle
    assertEquals((s.only.delivery.word, s.only.attempts, s.posts.size), ("Delivered", 2, 2))
    val t = Sequence(deposit(Outcome.Captured()), 429, 204)
    t.settle
    assertEquals((t.only.delivery.word, t.posts.size), ("Delivered", 2))
  }

  test("400, 401 missing_token and 403 are refused after one attempt, with the answer") {
    Seq(400, 401, 403).foreach { status =>
      val s = Sequence(deposit(Outcome.Captured()), status)
      assertEquals(s.settle, ConsumerEffect.Done)
      assertEquals(s.posts.size, 1, s"$status")
      s.only.delivery match
        case Delivery.Refused(_, st, answer) => assertEquals(st, status); assert(answer.contains(s"E$status"), answer)
        case d                               => fail(s"$status not refused: $d")
    }
  }

  test("Redriven by the timer on an undelivered session delivers again") {
    val undelivered = deposit(Outcome.Captured(), Delivery.Undelivered(at, 4, "503")).copy(redriving = Some(DepositSession.Timer))
    val s = Sequence(undelivered, 200)
    assertEquals(s.on(DepositEvent.Redriven(DepositSession.Timer, at)), ConsumerEffect.Done)
    assertEquals((s.posts.size, s.only.delivery.word), (1, "Delivered"))
  }

  test("Redriven by an operator on a refused session delivers; a refused session not re-driven is left") {
    val refused = deposit(Outcome.Captured(), Delivery.Refused(at, 400, "400 invalid_state"))
    val s = Sequence(refused.copy(redriving = Some("op-7")), 200)
    assertEquals(s.on(DepositEvent.Redriven("op-7", at)), ConsumerEffect.Done)
    assertEquals((s.posts.size, s.only.delivery.word), (1, "Delivered"))

    val t = Sequence(refused, 200)
    assertEquals(t.on(DepositEvent.Settled(Outcome.Captured(), "Y", at)), ConsumerEffect.Ignore)
    assert(t.posts.isEmpty && t.recorded.isEmpty)
  }

  test("a Settled event replayed after a crash calls the receiver twice and records once") {
    // The receiver took the outcome and the process died before the record, so the replay reads the
    // session as it was; the entity takes the first record and ignores the second.
    val s = Sequence(deposit(Outcome.Captured()), 200)
    assertEquals(s.settle, ConsumerEffect.Done)
    assertEquals(s.settle, ConsumerEffect.Done)
    assertEquals(s.posts.size, 2)
    assertEquals(s.only.delivery.word, "Delivered")
  }

  test("nothing is thrown when merchant answers only 5xx") {
    val s = Sequence(deposit(Outcome.Captured()), 502)
    assertEquals(s.settle, ConsumerEffect.Done)
    assertEquals(s.only.delivery.word, "Undelivered")
  }

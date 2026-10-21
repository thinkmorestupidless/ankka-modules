package io.eitheror.casino.wallet.it

import com.thinkmorestupidless.ankka.core.ComponentDescriptor
import com.thinkmorestupidless.ankka.http.Caller
import com.thinkmorestupidless.ankka.sdk.ServiceResponse
import com.thinkmorestupidless.ankka.testkit.ScriptedService
import io.eitheror.casino.wallet.WalletService
import io.eitheror.casino.wallet.api.ResponsibleGambling
import io.eitheror.casino.wallet.entity.WalletEntity

import java.nio.charset.StandardCharsets.UTF_8
import scala.concurrent.duration.*

/**
 * The deposit flow asking `responsible-gambling` through ankka's service client, as it does in a
 * cluster (FR-022; spec 010 FR-011), against a scripted `responsible-gambling` on loopback. The
 * other suites give the flow a fake, which is how the two defects spec 005 T072 found in a cluster
 * passed every suite: the call hopped off the step's thread, where ankka refuses it, and the flow
 * kept `RG_UNAVAILABLE` as final. Each test here fails on one of them.
 */
class ResponsibleGamblingItSuite extends Harness:

  private val scripted = ScriptedService.start()
  private val merchant = Caller.Service("payments", "merchant")

  override def components: Seq[ComponentDescriptor] =
    WalletService.components(services => ResponsibleGambling.http(services, brand))

  override def localServices: Map[String, String] = Map(ResponsibleGambling.ServiceName -> scripted.address)

  override def afterAll(): Unit =
    try super.afterAll()
    finally scripted.stop()

  override def beforeEach(context: BeforeEach): Unit =
    scripted.clear()
    scripted.delay(Duration.Zero)
    answer("""{"decision":"allowed"}""")

  private def answer(json: String): Unit =
    scripted.answer(_ => ServiceResponse(200, "application/json", json.getBytes(UTF_8), Vector.empty))

  private def deposit(player: String, paymentId: String, amount: Long): (Int, String) =
    send("POST", s"${wallet(player)}/deposits",
      Some(s"""{"paymentId":"$paymentId","amount":$amount,"terminal":"t1","psp":"dreamzpay","scheme":"card","paymentProvider":"dreamzpay"}"""),
      as = merchant)

  private def balance(player: String): Option[String] = field(send("GET", s"${wallet(player)}/balance")._2, "realBalance")

  private def asked(player: String): Vector[ScriptedService.Request] =
    scripted.requests.filter(r => r.path == ResponsibleGambling.mayDepositPath(brand) && r.text.contains(s""""playerId":"$player""""))

  test("the flow's step asks may-deposit through the service client, with the wallet's sums, and credits what is allowed") {
    assertEquals(field(deposit("http1", "pay-1", 5000)._2, "realBalance"), Some("5000"))
    val requests = asked("http1")
    assertEquals(requests.map(_.method), Vector("POST"))
    val body = requests.head.text
    assert(body.contains("\"currency\":\"EUR\"") && body.contains("\"amount\":5000") && body.contains("\"last24h\":0"), body)
    assertEquals(requests.head.contentType, Some("application/json"))
  }

  test("no answer within the client's 500 ms refuses as RG_UNAVAILABLE within the bound, and the same deposit arriving once it answers is checked again and credited") {
    scripted.delay(3.seconds)
    val started        = System.nanoTime()
    val (status, body) = deposit("http2", "pay-2", 5000)
    val elapsed        = (System.nanoTime() - started).nanos
    assertEquals((status, field(body, "error")), (503, Some("RG_UNAVAILABLE")))
    assert(elapsed < 2.seconds, s"the refusal took ${elapsed.toMillis} ms: the wait is not the service client's 500 ms")
    assertEquals(balance("http2"), Some("0"))
    assert(testKit.componentClient.forEventSourcedEntity(WalletEntity.id("http2", "EUR")).call(WalletEntity.outcomeOf).invoke("pay-2").isEmpty,
      "the wallet saw a deposit responsible-gambling never answered")

    scripted.delay(Duration.Zero)
    assertEquals(field(deposit("http2", "pay-2", 5000)._2, "realBalance"), Some("5000"))
    assertEquals(asked("http2").size, 2, "the check did not run again")
    // Credited once: the third arrival is the first outcome, and asks nothing.
    assertEquals(field(deposit("http2", "pay-2", 5000)._2, "realBalance"), Some("5000"))
    assertEquals((balance("http2"), asked("http2").size), (Some("5000"), 2))
  }

  test("a 5xx from responsible-gambling is RG_UNAVAILABLE and credits nothing") {
    scripted.failNext(503)
    val (status, body) = deposit("http3", "pay-3", 5000)
    assertEquals((status, field(body, "error")), (503, Some("RG_UNAVAILABLE")))
    assertEquals(balance("http3"), Some("0"))
  }

  test("a refusal that is an answer is final: the same deposit sent again is refused the same way and asks nothing") {
    answer("""{"decision":"over_limit","period":"daily","limit":10000,"used":8000,"remaining":2000}""")
    val first = deposit("http4", "pay-4", 5000)
    assertEquals((first._1, field(first._2, "error")), (409, Some("OVER_LIMIT")))
    answer("""{"decision":"allowed"}""")
    assertEquals(deposit("http4", "pay-4", 5000), first)
    assertEquals((balance("http4"), asked("http4").size), (Some("0"), 1))
  }

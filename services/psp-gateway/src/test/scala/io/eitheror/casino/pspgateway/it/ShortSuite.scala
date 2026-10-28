package io.eitheror.casino.pspgateway.it

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.pspgateway.Json
import io.eitheror.casino.pspgateway.sessions.*

import java.util.UUID
import scala.concurrent.duration.*

/** SC-005: a crypto payment one unit short of its quote is never captured as credited in full. */
class ShortSuite extends munit.FunSuite with LogCapturing:
  override val munitTimeout: Duration = 5.minutes

  private lazy val kit = SharedKit.kit

  override def beforeEach(context: BeforeEach): Unit =
    kit.reset()
    Provider.values.foreach(kit.credential("sb-1", _))

  /** The quote less one unit of its last decimal place: `0.00123400` → `0.00123399`. */
  private def oneUnitShort(quote: String): String =
    val q = BigDecimal(quote)
    (q - BigDecimal(1, q.scale)).bigDecimal.toPlainString

  private def quoted(provider: Provider): (String, String) =
    kit.psp.control(provider, "pending")
    val id = UUID.randomUUID().toString
    val r  = kit.initiate(id, provider)
    assertEquals(r.status, 200, r.text)
    (id, r.str("cryptoAmount"))

  private def delivered(id: String): Delivered = kit.eventually(s"$id delivered")(kit.merchant.deliveredTo(id).headOption)

  private def notCreditedInFull(id: String): Unit =
    val d = delivered(id)
    val b = Json.parse(d.body).get
    assert(d.key == s"$id/fail" || Json.text(b)("variance").contains(Variance.Underpayment), s"${d.key} ${d.body}")
    assert(kit.deposit(id).outcome.exists {
      case Outcome.Captured(None) => false
      case _                      => true
    }, kit.deposit(id).outcome.toString)

  test("Morphosis: one unit short fails underpaid") {
    val (id, quote) = quoted(Provider.Morphosis)
    kit.psp.webhook(Provider.Morphosis, kit.stubReference(id, Provider.Morphosis), "outcome" -> "underpayment",
      "receivedAmount" -> oneUnitShort(quote)): Unit
    notCreditedInFull(id)
    assertEquals(delivered(id).key, s"$id/fail")
  }

  test("Morphosis: a confirmation with no amount is short") {
    val (id, _)  = quoted(Provider.Morphosis)
    val token    = kit.stubReference(id, Provider.Morphosis)
    val qveiId   = kit.deposit(id).initiation.flatMap(_.reference).get
    val r = kit.request("POST", s"/v1/webhooks/morphosis/deposit/$token",
      Some(Json.render(Json.obj("id" -> qveiId, "status" -> "ac"))), kit.asProvider)
    assertEquals(r.status, 200, r.text)
    notCreditedInFull(id)
  }

  test("Cozeni: one unit short and unvalued fails; never a plain capture") {
    val (id, quote) = quoted(Provider.Cozeni)
    kit.psp.webhook(Provider.Cozeni, id, "outcome" -> "underpayment", "receivedAmount" -> oneUnitShort(quote),
      "convertAmount" -> "0"): Unit
    notCreditedInFull(id)
  }

  test("Cozeni: one unit short and valued captures with UNDERPAYMENT, not in full") {
    val (id, quote) = quoted(Provider.Cozeni)
    kit.psp.webhook(Provider.Cozeni, id, "outcome" -> "underpayment", "receivedAmount" -> oneUnitShort(quote),
      "convertAmount" -> "24.99"): Unit
    notCreditedInFull(id)
  }

package io.eitheror.casino.pspgateway.it

import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.contract.money.Currency
import io.eitheror.casino.pspgateway.Json
import io.eitheror.casino.pspgateway.sessions.*

import java.util.UUID
import scala.concurrent.duration.*

/**
 * Sessions of both kinds, in every state, read the same after the service restarts on the same
 * database; the re-drive picks up after it; the reference index survives; a repeat initiate answers the
 * first answer. A service of its own (build.sbt), since it restarts it.
 */
class DurabilitySuite extends munit.FunSuite:
  override val munitTimeout: Duration = 5.minutes

  private lazy val kit = Kit.withStub(redriveInterval = java.time.Duration.ofSeconds(3))
  private val brand    = "goldvibe"

  override def beforeAll(): Unit = kit: Unit
  override def afterAll(): Unit  = kit.stop()

  override def beforeEach(context: BeforeEach): Unit =
    kit.reset()
    Provider.values.foreach(kit.credential("sb-1", _))
    kit.payoutTerminals(brand)

  private def id() = UUID.randomUUID().toString

  private def initiated(provider: Provider): (String, Reply) =
    kit.psp.control(provider, "pending")
    val paymentId = id()
    val r         = kit.initiate(paymentId, provider)
    assertEquals(r.status, 200, r.text)
    (paymentId, r)

  private def settled(answer: Scripted, until: Delivery => Boolean): String =
    kit.merchant.answer = answer
    val (paymentId, _) = initiated(Provider.UnityFinance)
    kit.psp.webhook(Provider.UnityFinance, paymentId, "outcome" -> "success"): Unit
    kit.eventually(s"$paymentId delivery")(Some(kit.deposit(paymentId).delivery).filter(until))
    paymentId

  test("sessions in every state read the same after a restart; the re-drive delivers the undelivered after it; the index and repeats hold") {
    // Claimed and never initiated: as a crash between the claim and the call leaves it.
    val claimedOnly = id()
    kit.components.forEventSourcedEntity(EntityId(claimedOnly)).call(DepositSession.claim).invoke(Claim(
      DepositClaim(Provider.T365, "PIX", "sb-1", None, "c-1", 2500, Currency.unsafe("BRL"), "https://gw/return", "https://gw/hook"),
      kit.now())): Unit

    val (open, firstAnswer) = initiated(Provider.UnityFinance)

    kit.psp.control(Provider.T365, "unauthorised")
    val failed = id()
    kit.initiate(failed, Provider.T365): Unit
    kit.psp.control(Provider.T365, "success")

    val delivered   = settled(Scripted.Ok, _.isInstanceOf[Delivery.Delivered])
    val refused     = settled(Scripted.BadRequest, _.isInstanceOf[Delivery.Refused])
    val undelivered = settled(Scripted.Pending, _.isInstanceOf[Delivery.Undelivered])

    val (morphosis, _) = initiated(Provider.Morphosis)
    val token          = kit.stubReference(morphosis, Provider.Morphosis)

    kit.psp.control(Provider.UnityFinance, "pending")
    val withdrawal = s"w-${id()}"
    assertEquals(kit.withdraw(brand, withdrawal, Provider.UnityFinance).status, 200)

    // The undelivered one is left out of the exact comparison: the timer may re-drive it at any moment.
    val deposits = Vector(claimedOnly, open, failed, delivered, refused, morphosis)
    val before   = deposits.map(kit.deposit) -> kit.payout(withdrawal)
    val stuck    = kit.deposit(undelivered)
    assert(kit.deposit(failed).stage == Stage.InitiationFailed, kit.deposit(failed).toString)

    kit.restart()

    assertEquals(deposits.map(kit.deposit), before._1)
    assertEquals(kit.payout(withdrawal), before._2)
    val after = kit.deposit(undelivered)
    assertEquals((after.stage, after.outcome, after.claim), (stuck.stage, stuck.outcome, stuck.claim))
    assert(after.delivery.isInstanceOf[Delivery.Undelivered] || after.delivery == Delivery.NotYet, after.delivery.toString)

    val repeat = kit.initiate(open, Provider.UnityFinance)
    assertEquals(Json.parse(repeat.text), Json.parse(firstAnswer.text), "a repeat initiate after the restart answers the first answer")

    kit.merchant.answer = Scripted.Ok
    kit.eventually(s"$undelivered delivered after the restart")(Some(kit.deposit(undelivered).delivery).collect { case d: Delivery.Delivered => d })
    assert(kit.deposit(refused).delivery.isInstanceOf[Delivery.Refused], "the timer leaves a refused outcome after the restart too")

    // The index: Morphosis's webhook names its token, which only the index maps back to the payment.
    kit.psp.webhook(Provider.Morphosis, token, "outcome" -> "success"): Unit
    kit.eventually(s"$morphosis settled through the index")(kit.deposit(morphosis).outcome): Unit
  }

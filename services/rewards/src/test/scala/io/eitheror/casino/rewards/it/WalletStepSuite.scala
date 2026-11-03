package io.eitheror.casino.rewards.it

import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.rewards.RewardsRuntime
import io.eitheror.casino.rewards.clients.{AwardTerms, Balances, Wallet}
import io.eitheror.casino.rewards.steps.*

import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*

/**
 * `WalletStep` over the whole service (research R5), ankka 0.13 having no workflow test kit: the claim, each
 * route keyed as R5's table says, every kind of answer the wallet gives, a transport failure tried again, a repeat
 * start, and the delivery receiving exactly one answer per step.
 */
class WalletStepSuite extends munit.FunSuite:
  private lazy val kit = SharedKit.kit
  override def munitTimeout = scala.concurrent.duration.Duration(120, "s")

  private val delivered = ConcurrentLinkedQueue[(String, StepRequest, WalletAnswer)]()

  override def beforeEach(context: BeforeEach): Unit =
    kit.reset()
    delivered.clear()
    RewardsRuntime.delivery = _ => (rewardId, request, answer) => delivered.add((rewardId, request, answer)): Unit

  private def fresh(): (String, String) =
    val tag = UUID.randomUUID().toString.take(8)
    (s"ws-r-$tag", s"ws-p-$tag")

  private val terms = AwardTerms("offer-1", 1000L, 30L, 1000L, Some(5L), None)

  private def workflow(id: String)    = kit.components.forWorkflow(EntityId(id))
  private def state(id: String)       = workflow(id).call(WalletStep.status).invoke()
  private def start(r: StepRequest)   = workflow(r.id).call(WalletStep.start).invoke(r)
  private def deliveredFor(id: String) = delivered.asScala.toVector.filter(_._2.id == id)

  /** Starts the step and answers what the reward was handed, once the step is done. */
  private def run(r: StepRequest): WalletAnswer =
    start(r): Unit
    kit.eventually(s"${r.id} done")(Some(state(r.id)).filter(_.phase == StepState.Done))
    val got = kit.eventually(s"${r.id} delivered")(Some(deliveredFor(r.id)).filter(_.nonEmpty))
    assertEquals(got.size, 1)
    assertEquals(got.head._1, r.rewardId)
    assertEquals(state(r.id).answer, Some(got.head._3))
    got.head._3

  private def awarded(reward: String, player: String): Unit =
    run(StepRequest(reward, player, "EUR", WalletCall.Award(terms))): Unit

  test("award: keyed by the reward id, the wallet's record handed to the reward"):
    val (reward, player) = fresh()
    val r = StepRequest(reward, player, "EUR", WalletCall.Award(terms))
    assertEquals(r.id, s"$reward/award")
    run(r) match
      case WalletAnswer.Awarded(a) => assertEquals((a.recordId, a.wageringRequirement), (Some(reward), 30000L))
      case other                   => fail(s"not awarded: $other")
    assertEquals(kit.wallet.keys("awards"), Vector(reward))
    assertEquals(kit.wallet.calls("awards").head.json.flatMap(n => Option(n.get("bonusRef")).map(_.asText)), Some("offer-1"))

  test("an award refused is handed to the reward with the wallet's code"):
    val (reward, player) = fresh()
    kit.wallet.answerFor(reward, FakeWallet.refusal("BONUS_ALREADY_ACTIVE"))
    assertEquals(run(StepRequest(reward, player, "EUR", WalletCall.Award(terms))),
      WalletAnswer.Refused(409, "BONUS_ALREADY_ACTIVE", "BONUS_ALREADY_ACTIVE"))

  test("winnings under their own reward <reward>/winnings; a switch and a credit keyed <reward>/<step>, the grant the reward id"):
    val (reward, player) = fresh()
    awarded(reward, player)
    run(StepRequest(s"$reward/winnings", player, "EUR", WalletCall.Winnings(terms))) match
      case WalletAnswer.Awarded(a) => assertEquals(a.recordId, Some(s"$reward/winnings"))
      case other                   => fail(s"not awarded: $other")
    run(StepRequest(reward, player, "EUR", WalletCall.Switch(1))) match
      case WalletAnswer.Switched(_) => ()
      case other                    => fail(s"not switched: $other")
    assertEquals(kit.wallet.calls("awards/switch").head.json.flatMap(n => Option(n.get("grantRef")).map(_.asText)), Some(reward))
    run(StepRequest(reward, player, "EUR", WalletCall.Credit(250L, "free_rounds_winnings", "rewards"))) match
      case WalletAnswer.Credited(c) => assertEquals(c.txnId, Some(s"txn-$reward/credit"))
      case other                    => fail(s"not credited: $other")
    assertEquals(kit.wallet.keys("awards"), Vector(reward, s"$reward/winnings"))
    assertEquals(kit.wallet.keys("awards/switch"), Vector(s"$reward/switch-1"))
    val credit = kit.wallet.calls("provider-credits").head.json.get
    assertEquals((credit.get("key").asText, credit.get("providerRef").asText, credit.get("amount").asLong),
      (s"$reward/credit", reward, 250L))

  test("a late round is keyed <reward>/late/<round>"):
    val (reward, player) = fresh()
    val r = StepRequest(reward, player, "EUR", WalletCall.LateCredit("round-9", 40L, "free_rounds_winnings", "rewards"))
    assertEquals(r.id, s"$reward/late/round-9")
    run(r) match
      case WalletAnswer.Credited(_) => ()
      case other                    => fail(s"not credited: $other")
    assertEquals(kit.wallet.keys("provider-credits"), Vector(s"$reward/late/round-9"))

  test("forfeit: done, the records the wallet forfeited handed over"):
    val (reward, player) = fresh()
    awarded(reward, player)
    run(StepRequest(reward, player, "EUR", WalletCall.Forfeit, byTimer = true)) match
      case WalletAnswer.Forfeited(f) => assertEquals(f.forfeited, Vector(reward))
      case other                     => fail(s"not forfeited: $other")
    assertEquals(kit.wallet.keys("awards/forfeit"), Vector(s"$reward/forfeit"))

  test("NO_ACTIVE_BONUS on a timer's forfeit is NotOpen; on a revoke's it is a refusal"):
    val (reward, player) = fresh()
    assertEquals(run(StepRequest(reward, player, "EUR", WalletCall.Forfeit, byTimer = true)), WalletAnswer.NotOpen(Wallet.NoActiveBonus))
    val (other, player2) = fresh()
    assertEquals(run(StepRequest(other, player2, "EUR", WalletCall.Forfeit)),
      WalletAnswer.Refused(409, Wallet.NoActiveBonus, Wallet.NoActiveBonus))

  test("close-cap: done on a pending cap; NOT_PENDING_CAP on the timer's step is NotOpen"):
    val (reward, player) = fresh()
    awarded(reward, player)
    kit.wallet.pendingCap(player, "EUR", reward)
    assertEquals(run(StepRequest(reward, player, "EUR", WalletCall.CloseCap, byTimer = true)),
      WalletAnswer.CapClosed(io.eitheror.casino.rewards.clients.CapClosed(Some(reward))))
    assertEquals(kit.wallet.keys("awards/close-pending-cap"), Vector(s"$reward/close-cap"))
    val (open, player2) = fresh()
    awarded(open, player2)
    assertEquals(run(StepRequest(open, player2, "EUR", WalletCall.CloseCap, byTimer = true)), WalletAnswer.NotOpen(Wallet.NotPendingCap))

  test("cancel: done with the clawback; MISSING_BASELINE on a reclaim is a refusal"):
    val (reward, player) = fresh()
    awarded(reward, player)
    run(StepRequest(reward, player, "EUR", WalletCall.Cancel())) match
      case WalletAnswer.Cancelled(c) => assertEquals(c.forfeited, Vector(reward))
      case other                     => fail(s"not cancelled: $other")
    val (old, player2) = fresh()
    awarded(old, player2)
    kit.wallet.noBaseline(player2, "EUR", old)
    assertEquals(run(StepRequest(old, player2, "EUR", WalletCall.Cancel(requireBaseline = true))),
      WalletAnswer.Refused(409, Wallet.MissingBaseline, Wallet.MissingBaseline))
    assertEquals(kit.wallet.calls("awards/cancel").last.json.flatMap(n => Option(n.get("requireBaseline")).map(_.asBoolean)), Some(true))

  test("a transport failure, then a 5xx, are tried again until the wallet answers; the reward gets one answer"):
    val (reward, player) = fresh()
    val r = StepRequest(reward, player, "EUR", WalletCall.Award(terms))
    kit.wallet.stop()
    try
      start(r): Unit
      kit.eventually("tried and failed")(Some(state(r.id)).filter(s => s.attempts >= 1 && s.lastError.isDefined))
      assertEquals((state(r.id).phase, deliveredFor(r.id)), (StepState.Calling, Vector.empty))
      kit.wallet.down = Some(Answer(503, "{}"))
    finally kit.wallet.start()
    kit.eventually("a 5xx tried again")(Option.when(kit.wallet.keys("awards").size >= 2)(()))
    kit.wallet.down = None
    kit.eventually(s"${r.id} done")(Some(state(r.id)).filter(_.phase == StepState.Done))
    kit.eventually("delivered")(Some(deliveredFor(r.id)).filter(_.nonEmpty))
    Thread.sleep(500)
    assertEquals(deliveredFor(r.id).map(_._3).collect { case WalletAnswer.Awarded(a) => a.recordId }, Vector(Some(reward)))
    assert(kit.wallet.open(player, "EUR").map(_.grantRef) == Vector(reward))

  test("a second start answers the first's state, calls the wallet no more, and delivers nothing more"):
    val (reward, player) = fresh()
    val r = StepRequest(reward, player, "EUR", WalletCall.Award(terms))
    run(r): Unit
    val first = state(r.id)
    val again = start(r.copy(call = WalletCall.Award(terms.copy(amount = 99L))))
    assertEquals(again, first)
    Thread.sleep(500)
    assertEquals((kit.wallet.keys("awards"), deliveredFor(r.id).size), (Vector(reward), 1))

  test("a start under another step's id is refused"):
    val (reward, player) = fresh()
    val r = StepRequest(reward, player, "EUR", WalletCall.Forfeit)
    intercept[Exception](workflow(s"$reward/cancel").call(WalletStep.start).invoke(r)): Unit
    assertEquals(kit.wallet.calls("awards/forfeit"), Vector.empty)

  test("the answer kinds are pure: a refusal off a timer, NotOpen on one, a transport failure tried again"):
    import io.eitheror.casino.rewards.clients.Called
    val r = StepRequest("r", "p", "EUR", WalletCall.CloseCap, byTimer = true)
    assertEquals(WalletStep.answer(r, Called.Refused(409, Wallet.NotPendingCap, "")), Right(WalletAnswer.NotOpen(Wallet.NotPendingCap)))
    assertEquals(WalletStep.answer(r.copy(byTimer = false), Called.Refused(409, Wallet.NotPendingCap, "m")),
      Right(WalletAnswer.Refused(409, Wallet.NotPendingCap, "m")))
    assertEquals(WalletStep.answer(r, Called.Refused(409, "OTHER", "m")), Right(WalletAnswer.Refused(409, "OTHER", "m")))
    assertEquals(WalletStep.answer(r, Called.Unreachable("down")), Left("down"))
    val done = WalletAnswer.Forfeited(io.eitheror.casino.rewards.clients.Forfeited(Vector("r"), Balances(0L, 0L)))
    assertEquals(WalletStep.answer(r, Called.Done(done)), Right(done))

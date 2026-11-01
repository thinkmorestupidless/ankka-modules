package io.eitheror.casino.rewards.it

import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.contract.facts.{DepositConfirmed, MoneyTransactions}
import io.eitheror.casino.contract.money.{Currency, MinorUnits, SignedMinorUnits}
import io.eitheror.casino.rewards.Json
import io.eitheror.casino.rewards.features.Scene
import io.eitheror.casino.rewards.player.PlayerRewards
import io.eitheror.casino.rewards.reward.{Reward, RewardState, RewardStatus}

import java.time.Duration as JDuration
import java.util.UUID

/**
 * The deposit path (FR-012; research R4) over the whole service: the wallet's `deposit_confirmed` on the broker, read
 * by `MoneyConsumer`, decides the player's pending selection on `PlayerRewards`, and the award goes to the wallet.
 */
class DepositSuite extends munit.FunSuite:
  private lazy val kit = SharedKit.kit
  override def munitTimeout = scala.concurrent.duration.Duration(120, "s")
  override def beforeEach(context: BeforeEach): Unit = kit.reset()

  private val Eur = Currency.unsafe("EUR")
  private def unique(p: String) = s"$p-${UUID.randomUUID().toString.take(8)}"

  /** A deposit match of 100% up to 500.00, its fields overridden by `fields`. */
  private def offer(fields: (String, Any)*): String =
    val body = Json.obj((Vector[(String, Any)]("name" -> unique("Match"), "type" -> "DEPOSIT_MATCH", "bonusPercentage" -> 10000L,
      "maxBonusValue" -> 50000L, "wageringMultiplier" -> 30L, "currency" -> "EUR") ++ fields)*)
    val r = kit.json("POST", kit.offersPath(""), body, kit.asOperator())
    assertEquals(r.status, 201, r.text)
    r.str("id")

  /** The offer made visible to a new player, who selects it: the reward pending. */
  private def pending(offerId: String): (String, String) =
    val player = unique("dp-p")
    val id     = Scene.target(kit, player, offerId)
    val r      = kit.json("POST", kit.rewardsPath(s"/$player/select"), Json.obj("offerId" -> offerId), kit.asPlayer(player))
    assertEquals(r.status, 200, r.text)
    assertEquals(reward(id).status, RewardStatus.Pending)
    (player, id)

  private def deposit(player: String, amount: Long, ordinal: Int, paymentRef: String = unique("pay")): DepositConfirmed =
    val fact = DepositConfirmed(player, Eur, unique("txn"), MinorUnits.unsafe(amount), paymentRef, ordinal, SignedMinorUnits(amount),
      MinorUnits.unsafe(0L), kit.now())
    kit.publish(MoneyTransactions, player, fact)
    fact

  private def reward(id: String): RewardState = kit.components.forEventSourcedEntity(EntityId(id)).call(Reward.get).invoke()
  private def until(id: String)(p: RewardState => Boolean): RewardState =
    kit.eventually(s"reward $id")(Some(reward(id)).filter(p))
  private def awards(player: String) = kit.wallet.calls("awards").filter(_.path.contains(s"/wallets/$player/"))

  test("a confirming deposit activates the pending reward, its number the wallet's ordinal"):
    val offerId      = offer("depositNumberTarget" -> 2)
    val (player, id) = pending(offerId)
    deposit(player, 3000L, ordinal = 2)
    val s = until(id)(_.status == RewardStatus.Active)
    assertEquals((s.faceValue, s.wageringRequirement), (3000L, 90000L))
    assertEquals(kit.wallet.open(player, "EUR").map(r => (r.grantRef, r.amount)), Vector((id, 3000L)))

  test("a deposit of another number, or below the minimum, leaves the reward pending with the reason"):
    val (first, a) = pending(offer("depositNumberTarget" -> 2))
    deposit(first, 3000L, ordinal = 1)
    assertEquals(until(a)(_.lastDecline.isDefined).lastDecline, Some("DEPOSIT_NUMBER_MISMATCH"))
    val (second, b) = pending(offer("minDepositAmount" -> 2000L))
    deposit(second, 1000L, ordinal = 1)
    val s = until(b)(_.lastDecline.isDefined)
    assertEquals((s.status, s.lastDecline), (RewardStatus.Pending, Some("BELOW_MIN_DEPOSIT")))
    assertEquals(awards(first) ++ awards(second), Vector.empty)

  test("a deposit after the claim window expires the selection and awards nothing"):
    val (player, id) = pending(offer("claimWindowDays" -> 1))
    kit.advance(JDuration.ofDays(2))
    deposit(player, 3000L, ordinal = 1)
    val s = until(id)(_.status == RewardStatus.Expired)
    assertEquals(s.terminationReason, Some("pending_expired"))
    assertEquals(awards(player), Vector.empty)

  test("a deposit for an offer no longer live expires the selection"):
    val offerId      = offer()
    val (player, id) = pending(offerId)
    assertEquals(kit.request("DELETE", kit.offersPath(s"/$offerId"), None, kit.asOperator()).status, 200)
    deposit(player, 3000L, ordinal = 1)
    assertEquals(until(id)(_.status == RewardStatus.Expired).terminationReason, Some("pending_expired"))
    assertEquals(awards(player), Vector.empty)

  test("the same deposit reported twice awards once"):
    val (player, id) = pending(offer())
    val fact = deposit(player, 3000L, ordinal = 1)
    until(id)(_.status == RewardStatus.Active): Unit
    kit.publish(MoneyTransactions, player, fact)
    val again = deposit(player, 4000L, ordinal = 2, paymentRef = fact.paymentReference)
    assertEquals(again.paymentReference, fact.paymentReference)
    // Facts of one player are read in order: once a later deposit of theirs is recorded, the repeats were read.
    val later = deposit(player, 100L, ordinal = 3)
    kit.eventually("the later deposit read")(
      Some(kit.components.forEventSourcedEntity(EntityId(player)).call(PlayerRewards.get).invoke())
        .filter(_.lastDeposit.exists(_.paymentRef == later.paymentReference)))
    assertEquals(awards(player).size, 1)
    assertEquals(kit.wallet.open(player, "EUR").map(_.amount), Vector(3000L))

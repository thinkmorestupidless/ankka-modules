package io.eitheror.casino.rewards.it

import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.contract.facts.{CasinoRewards, CasinoRounds, FreeSpinsSettled, RoundSettled}
import io.eitheror.casino.contract.money.{Currency, MinorUnits, SignedMinorUnits}
import io.eitheror.casino.rewards.RewardsRuntime
import io.eitheror.casino.rewards.Json.*
import io.eitheror.casino.rewards.clients.AwardTerms
import io.eitheror.casino.rewards.eligibility.Decision
import io.eitheror.casino.rewards.offers.PayloadType
import io.eitheror.casino.rewards.reward.*

import java.time.{Duration as JDuration, Instant}
import java.time.temporal.ChronoUnit
import java.util.UUID
import scala.concurrent.duration.*

/**
 * Free rounds' finalisation over the whole service (FR-024; task T056): on the last round, and at `rounds-<reward>`,
 * `Settlement` decides; real balance is the wallet's provider credit (`<reward>/credit`), restricted funds an award
 * (`<reward>/winnings`) below every open record with a 30-day expiry; nothing won calls nothing; a round after
 * finalisation is paid on its own under `<reward>/late/<round>`. Each destination with and without an active reward.
 */
class SettlementItSuite extends munit.FunSuite:
  private lazy val kit = SharedKit.kit
  override def munitTimeout = Duration(120, "s")
  override def beforeEach(context: BeforeEach): Unit = kit.reset()

  private val Eur  = Currency.unsafe("EUR")
  private val Lead = JDuration.ofSeconds(3)
  private def unique(p: String) = s"$p-${UUID.randomUUID().toString.take(8)}"

  private def entity(id: String) = kit.components.forEventSourcedEntity(EntityId(id))
  private def reward(id: String) = entity(id).call(Reward.get).invoke()

  /** Live free rounds of `count` rounds paying to `destination`, granted `at`: the reward's id. */
  private def rounds(player: String, count: Int, destination: Destination, at: Instant = kit.now()): String =
    val id = unique("st-r")
    val opening = Opening(player, unique("o"), "Spins", "FREE_SPINS", PayloadType.FreeSpins, Decision(0L, 0L, 3000L, 20L, Eur),
      source = Source.OperatorManual, freeRounds = Some(FreeRoundsTerms("g-1", "st8", count, 20L, destination)))
    entity(id).call(Reward.open).invoke(Open(opening, RewardStatus.Active, at)): Unit
    id

  /** An active reward of funds, its record at the wallet at `priority`. */
  private def activeFunds(player: String, priority: Long): String =
    val id = unique("st-a")
    RewardsRuntime.wallet(kit.services).award(player, Eur, id, AwardTerms("o-funds", 1000L, 10L, 1000L, Some(priority), None)): Unit
    id

  private def settle(player: String, grant: String, round: String, win: Long): Unit =
    kit.publish(CasinoRounds, s"$player/EUR", RoundSettled(player, Eur, round, "st8", s"p-$round", "g-1", "s-1", MinorUnits.unsafe(0),
      MinorUnits.unsafe(win), SignedMinorUnits(win), RoundSettled.Outcome.Win, Vector.empty, Vector.empty, Vector.empty, Some(grant),
      kit.now()))

  private def until(id: String, status: RewardStatus): RewardState =
    kit.eventually(s"$id ${status.wire}", 60.seconds)(Some(reward(id)).filter(_.status == status))

  private def keyed(route: String, key: String) =
    kit.eventually(s"$route $key")(kit.wallet.calls(route).flatMap(_.json).find(_.text("key").contains(key)))

  private def onWallet(player: String) = kit.wallet.received.filter(r => r.method == "POST" && r.path.contains(s"/wallets/$player/"))

  test("real balance, nothing open: the full amount credited to real balance under <reward>/credit, the reward closed"):
    val player = unique("st-p")
    val id     = rounds(player, 2, Destination.RealBalance)
    settle(player, id, "rd-1", 150L)
    settle(player, id, "rd-2", 50L)
    val s = until(id, RewardStatus.Closed)
    assertEquals(s.terminationReason, Some("free_spins_finalised"))
    val credit = keyed("provider-credits", s"$id/credit")
    assertEquals((credit.long("amount"), credit.text("providerRef")), (Some(200L), Some(id)))
    kit.eventually("credited")(Option.when(kit.wallet.realOf(player, "EUR") == 200L)(()))
    val fact = kit.eventually("free_spins_settled")(kit.facts(CasinoRewards).collectFirst { case f: FreeSpinsSettled if f.bonusId == id => f })
    assertEquals(fact.winCents.value, 200L)

  test("real balance with a reward active: the credit lands on the active record's restricted funds"):
    val player = unique("st-p")
    val funds  = activeFunds(player, 5L)
    val id     = rounds(player, 1, Destination.RealBalance)
    settle(player, id, "rd-1", 300L)
    keyed("provider-credits", s"$id/credit"): Unit
    kit.eventually("landed on the record")(kit.wallet.open(player, "EUR").find(_.grantRef == funds).filter(_.amount == 1300L))
    assertEquals(kit.wallet.realOf(player, "EUR"), 0L)
    assertEquals(until(id, RewardStatus.Closed).terminationReason, Some("free_spins_finalised"))

  test("restricted funds, nothing open: awarded under <reward>/winnings with the multiplier, the maximum convertible and 30 days"):
    val player = unique("st-p")
    val id     = rounds(player, 1, Destination.RestrictedFunds)
    settle(player, id, "rd-1", 400L)
    val award = keyed("awards", s"$id/winnings")
    assertEquals((award.long("amount"), award.long("wageringMultiplier"), award.long("maxWin"), award.long("priority")),
      (Some(400L), Some(20L), Some(3000L), None))
    val expires = Instant.parse(award.text("expiresAt").get)
    assert(JDuration.between(kit.now().plus(30, ChronoUnit.DAYS), expires).abs.compareTo(JDuration.ofMinutes(5)) < 0, s"$expires")
    assertEquals(until(id, RewardStatus.Closed).status, RewardStatus.Closed)
    // A reward of their own, active until the same deadline, so its claim timer forfeits them as any reward's.
    val own = until(s"$id/winnings", RewardStatus.Active)
    assertEquals((own.playerId, own.faceValue, own.source), (player, 400L, Source.System))
    assert(own.expiresAt.exists(e => JDuration.between(expires, e).abs.compareTo(JDuration.ofMinutes(1)) < 0), s"${own.expiresAt}")

  test("restricted funds with a reward active: the winnings stack beside it, below every open record"):
    val player = unique("st-p")
    val funds  = activeFunds(player, 5L)
    val id     = rounds(player, 1, Destination.RestrictedFunds)
    settle(player, id, "rd-1", 400L)
    val award = keyed("awards", s"$id/winnings")
    assertEquals(award.long("priority"), Some(4L))
    kit.eventually("both open")(Option.when(kit.wallet.open(player, "EUR").map(_.grantRef).toSet == Set(funds, s"$id/winnings"))(()))

  test("the last round with nothing won pays nothing and closes the reward"):
    val player = unique("st-p")
    val id     = rounds(player, 2, Destination.RestrictedFunds)
    settle(player, id, "rd-1", 0L)
    settle(player, id, "rd-2", 0L)
    assertEquals(until(id, RewardStatus.Closed).terminationReason, Some("free_spins_finalised"))
    Thread.sleep(500)
    assertEquals(onWallet(player), Vector.empty)

  test("past expiry with something won: paid in full at rounds-<reward>, and closed"):
    val player = unique("st-p")
    val id     = rounds(player, 10, Destination.RealBalance, kit.now().plus(Lead).minus(Windows.FreeRoundsDays.toLong, ChronoUnit.DAYS))
    settle(player, id, "rd-1", 250L)
    kit.eventually("counted")(reward(id).freeRounds.filter(_.tally.used == 1))
    val s = until(id, RewardStatus.Closed)
    assertEquals(s.terminationReason, Some("free_spins_finalised"))
    assertEquals(keyed("provider-credits", s"$id/credit").long("amount"), Some(250L))

  test("past expiry with nothing won: expired, nothing paid"):
    val player = unique("st-p")
    val id     = rounds(player, 10, Destination.RestrictedFunds, kit.now().plus(Lead).minus(Windows.FreeRoundsDays.toLong, ChronoUnit.DAYS))
    assertEquals(until(id, RewardStatus.Expired).terminationReason, Some("free_spins_expired_unused"))
    Thread.sleep(500)
    assertEquals(onWallet(player), Vector.empty)

  test("a round settling after finalisation is paid on its own under <reward>/late/<round>, once"):
    val player = unique("st-p")
    val id     = rounds(player, 1, Destination.RealBalance)
    settle(player, id, "rd-1", 100L)
    until(id, RewardStatus.Closed): Unit
    settle(player, id, "rd-late", 70L)
    settle(player, id, "rd-late", 70L)
    assertEquals(keyed("provider-credits", s"$id/late/rd-late").long("amount"), Some(70L))
    kit.eventually("both paid")(Option.when(kit.wallet.realOf(player, "EUR") == 170L)(()))
    Thread.sleep(500)
    assertEquals(kit.wallet.realOf(player, "EUR"), 170L)
    assertEquals(reward(id).freeRounds.flatMap(_.tally.paid), Some(100L))

  test("a late round to restricted funds is awarded on its own under <reward>/late/<round>"):
    val player = unique("st-p")
    val id     = rounds(player, 10, Destination.RestrictedFunds, kit.now().plus(Lead).minus(Windows.FreeRoundsDays.toLong, ChronoUnit.DAYS))
    until(id, RewardStatus.Expired): Unit
    settle(player, id, "rd-late", 90L)
    assertEquals(keyed("awards", s"$id/late/rd-late").long("amount"), Some(90L))

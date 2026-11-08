package io.eitheror.casino.engagement.it

import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.engagement.profile.{DepositDay, MoneyFact, Profile}
import io.eitheror.casino.engagement.reads.ProfilesView
import io.eitheror.casino.engagement.timers.DepositDayReset

import java.time.{Duration, Instant}
import scala.concurrent.duration.*

/** FR-009 through the timer: the clock past 03:05 UTC, the players above zero identified with 0, once. */
class DepositDaySuite extends munit.FunSuite:
  override val munitTimeout = 180.seconds
  private lazy val kit   = SharedKit.kit
  private lazy val facts = Facts(kit)
  private def timer      = DepositDayReset.timerName(kit.slug)

  override def beforeAll(): Unit =
    kit.reset()
    kit.configure(Map("DepositConfirmed" -> "7"))

  override def afterAll(): Unit = kit.setClock(Instant.now())

  private def deposited(playerId: String, times: Int): Unit =
    (1 to times).foreach { _ =>
      kit.components.forKeyValueEntity(EntityId(playerId)).call(Profile.money).invoke(MoneyFact(facts.deposit(playerId))): Unit
    }

  private def identifiedWithZero(playerId: String) =
    kit.crm.identifies(playerId).filter(b => kit.crm.attributes(b).get("daily_deposit_count").exists(v => BigDecimal(v.toString) == 0))

  test("the timer is set at start, recurring as deposit-day-<slug>") {
    assert(kit.timerExists(timer), timer)
  }

  test("past 03:05 the players above zero are identified with 0, the one at zero is not; a second firing identifies no one") {
    val twice = facts.player("dd")
    deposited(twice, 2)
    val zero = facts.player("dd")
    kit.components.forKeyValueEntity(EntityId(zero)).call(Profile.resetDepositDay)
      .invoke(DepositDay.of(Instant.now()).plusDays(1)): Unit
    val today = DepositDay.of(Instant.now())
    kit.eventually("the deposits in the view") {
      ProfilesView.dailyCountAbove(kit.views.forView(ProfilesView), today.plusDays(1)).find(_.playerId == twice).filter(_.dailyDepositCount == 3)
    }: Unit
    kit.crm.reset()

    kit.setClock(DepositDay.nextReset(Instant.now()).plus(Duration.ofMinutes(1)))
    kit.fireTimer(timer)
    kit.eventually("the identify with 0")(identifiedWithZero(twice).headOption): Unit
    assertEquals(kit.components.forKeyValueEntity(EntityId(twice)).call(Profile.get).invoke().dailyDepositCount, 0)
    assertEquals(kit.crm.identifies(zero), Vector.empty)

    kit.fireTimer(timer)
    Thread.sleep(3000)
    assertEquals(identifiedWithZero(twice).size, 1)
    assertEquals(kit.crm.identifies(zero), Vector.empty)
  }

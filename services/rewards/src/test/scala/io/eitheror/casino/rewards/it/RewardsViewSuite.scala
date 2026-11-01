package io.eitheror.casino.rewards.it

import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.contract.money.Currency
import io.eitheror.casino.contract.personal.Personal
import io.eitheror.casino.rewards.eligibility.Decision
import io.eitheror.casino.rewards.offers.PayloadType
import io.eitheror.casino.rewards.reads.{RewardRow, RewardsView}
import io.eitheror.casino.rewards.reward.*

import java.time.{Duration, Instant}
import java.util.UUID

/**
 * data-model.md's `RewardsView` over Postgres: a row per reward from its events, with no personal value; the player's
 * list newest first and their history of closed rewards; the operator's filters and pages; the free rounds the
 * provider may play. The view holds every suite's rewards, so each test's players are its own.
 */
class RewardsViewSuite extends munit.FunSuite:
  private lazy val kit = SharedKit.kit
  override def munitTimeout = scala.concurrent.duration.Duration(180, "s")
  override def beforeEach(context: BeforeEach): Unit = kit.reset()

  private val tag   = UUID.randomUUID().toString.take(6)
  /** Now, so no reward's deadline is already past and no timer fires during a test. */
  private val start = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
  private val eur   = Currency.unsafe("EUR")

  private def reward(id: String) = kit.components.forEventSourcedEntity(EntityId(id))
  private def view               = kit.views.forView(RewardsView)

  private def opening(player: String, offer: String, name: String = "Welcome", rounds: Option[FreeRoundsTerms] = None) =
    Opening(player, offer, name, if rounds.isDefined then "FREE_SPINS" else "DEPOSIT_MATCH",
      if rounds.isDefined then PayloadType.FreeSpins else PayloadType.BonusFunds, Decision(5000L, 100000L, 5000L, 20L, eur),
      source = Source.PlayerCode, freeRounds = rounds)

  private def open(id: String, o: Opening, minutes: Long, initial: RewardStatus = RewardStatus.Active): String =
    val at = start.plus(Duration.ofMinutes(minutes))
    val a  = reward(id).call(Reward.open).invoke(Open(o, initial, at, Option.when(initial == RewardStatus.Active)(WalletAward(None, 100000L))))
    assertEquals(a.refusal, None)
    id

  private def row(id: String, until: RewardRow => Boolean = _ => true): RewardRow =
    kit.eventually(s"the row of $id")(view.get(id).filter(until))

  private def mine(rows: Vector[RewardRow], player: String) = rows.filter(_.playerId == player).map(_.rewardId)

  test("a row holds the reward's columns and follows its events; no personal value is kept"):
    val p  = s"vp-$tag-1"
    val id = open(s"v-$tag-1", opening(p, "o-1"), 0, RewardStatus.Pending)
    val pending = row(id)
    assertEquals((pending.playerId, pending.offerId, pending.offerName, pending.offerType, pending.status, pending.faceValue,
      pending.currency, pending.source, pending.grantedAt, pending.closedAt),
      (p, "o-1", "Welcome", "DEPOSIT_MATCH", "PENDING", 5000L, "EUR", "player_code", None, None))
    reward(id).call(Reward.activate).invoke(Activate(Some(WalletAward(Some("rec"), 99000L)), start)): Unit
    val active = row(id, _.status == "ACTIVE")
    assertEquals((active.grantedAt, active.wageringRequirement), (Some(start), 99000L))
    reward(id).call(Reward.revoke).invoke(Revoke(RevokeMode.Forfeit, "op-1", Some(Personal("asked by phone")), 0L, start.plusSeconds(60))): Unit
    val revoked = row(id, _.status == "REVOKED")
    assertEquals(revoked.closedAt, Some(start.plusSeconds(60)))
    val text = String(RewardRow.given_Serializer_RewardRow.toBytes(revoked), java.nio.charset.StandardCharsets.UTF_8)
    assert(!text.contains("phone"), text)

  test("the player's rewards newest first; their history the closed ones, by when they closed"):
    val p   = s"vp-$tag-2"
    val ids = (0 until 3).toVector.map(i => open(s"v-$tag-2-$i", opening(p, s"o-$i"), i.toLong))
    reward(ids(0)).call(Reward.cancel).invoke(Cancel(CancelReason.PlayerCancel, 0L, start.plusSeconds(3600))): Unit
    reward(ids(2)).call(Reward.close).invoke(Close(CloseReason.RealBalanceZero, start.plusSeconds(1800))): Unit
    kit.eventually("both closed")(Option.when(RewardsView.history(view, p).size == 2)(()))
    assertEquals(RewardsView.byPlayer(view, p).map(_.rewardId), ids.reverse)
    assertEquals(RewardsView.history(view, p).map(_.rewardId), Vector(ids(0), ids(2)))

  test("the history is the newest 20"):
    val p   = s"vp-$tag-3"
    val ids = (0 until 22).toVector.map(i => open(s"v-$tag-3-$i", opening(p, "o-h"), i.toLong))
    ids.zipWithIndex.foreach((id, i) => reward(id).call(Reward.close).invoke(Close(CloseReason.RealBalanceZero, start.plusSeconds(i.toLong))): Unit)
    kit.eventually("all closed")(Option.when(view.count(RewardsView.Filter(playerId = Some(p), status = Some("closed")).condition) == 22L)(()))
    assertEquals(RewardsView.history(view, p).map(_.rewardId), ids.reverse.take(20))

  test("the operator's filters: search across the ids and the offer name, status (all is none), offer; pages of 1 to 200"):
    val p = s"vp-$tag-4"
    val a = open(s"v-$tag-4-a", opening(p, s"offer-$tag-x", name = s"Autumn $tag"), 0)
    val b = open(s"v-$tag-4-b", opening(p, s"offer-$tag-y", name = "Spring"), 1, RewardStatus.Available)
    val c = open(s"v-$tag-4-c", opening(p, s"offer-$tag-y", name = "Spring"), 2)
    kit.eventually("three rows")(Option.when(RewardsView.byPlayer(view, p).size == 3)(()))
    def ids(f: RewardsView.Filter) = mine(RewardsView.page(view, f, 1, 200).rows, p)
    assertEquals(ids(RewardsView.Filter(search = Some(p.toUpperCase))), Vector(c, b, a))
    assertEquals(ids(RewardsView.Filter(search = Some(s"autumn $tag"))), Vector(a))
    assertEquals(ids(RewardsView.Filter(search = Some(s"v-$tag-4-b"))), Vector(b))
    assertEquals(ids(RewardsView.Filter(search = Some(s"offer-$tag-y"))), Vector(c, b))
    assertEquals(ids(RewardsView.Filter(search = Some("%"), playerId = Some(p))), Vector.empty)
    assertEquals(ids(RewardsView.Filter(playerId = Some(p), status = Some("active"))), Vector(c, a))
    assertEquals(ids(RewardsView.Filter(playerId = Some(p), status = Some("all"))), Vector(c, b, a))
    assertEquals(ids(RewardsView.Filter(offerId = Some(s"offer-$tag-x"))), Vector(a))
    val f = RewardsView.Filter(playerId = Some(p))
    val one = RewardsView.page(view, f, 2, 1)
    assertEquals((one.rows.map(_.rewardId), one.total, one.page, one.pageSize), (Vector(b), 3L, 2, 1))
    assertEquals(RewardsView.page(view, f, 0, 5000).pageSize, 200)
    assertEquals(RewardsView.page(view, f).pageSize, 25)
    assertEquals((RewardsView.validPageSize(0), RewardsView.validPageSize(200), RewardsView.validPageSize(201)), (false, true, false))

  test("free rounds: the oldest active grant for the game the provider has not failed; spins remaining follow the rounds"):
    val p = s"vp-$tag-5"
    def spins(game: String) = Some(FreeRoundsTerms(game, "st8", 3, 10L, Destination.RealBalance))
    val failed = open(s"v-$tag-5-f", opening(p, "o-f", rounds = spins("g-1")), 0)
    val oldest = open(s"v-$tag-5-a", opening(p, "o-a", rounds = spins("g-1")), 1)
    val newer  = open(s"v-$tag-5-b", opening(p, "o-b", rounds = spins("g-1")), 2)
    val other  = open(s"v-$tag-5-c", opening(p, "o-c", rounds = spins("g-2")), 3)
    reward(failed).call(Reward.providerStatus).invoke(ProviderStatus("rejected", start)): Unit
    reward(oldest).call(Reward.providerStatus).invoke(ProviderStatus("active", start)): Unit
    reward(oldest).call(Reward.roundSettled).invoke(RoundSettled("rd-1", 5L, start)): Unit
    val played = row(oldest, _.freeRounds.exists(_.spinsRemaining == 2))
    assertEquals(played.freeRounds.map(f => (f.spinCount, f.gameId, f.provider, f.providerStatus)), Some((3, "g-1", "st8", "active")))
    row(failed, _.freeRounds.exists(_.providerStatus == "rejected")): Unit
    assertEquals(RewardsView.eligibleFreeRounds(view, p, "g-1").map(_.rewardId), Some(oldest))
    assertEquals(RewardsView.eligibleFreeRounds(view, p, "g-1", Some("other")).map(_.rewardId), None)
    assertEquals(RewardsView.eligibleFreeRounds(view, p, "g-2").map(_.rewardId), Some(other))
    assertEquals(RewardsView.eligibleFreeRounds(view, p, "g-3"), None)
    assertEquals(RewardsView.liveFreeRounds(view, p).map(_.rewardId), Vector(oldest, newer, other))
    assertEquals(RewardsView.liveFreeRounds(view, p, Some("USD")), Vector.empty)
    reward(oldest).call(Reward.cancel).invoke(Cancel(CancelReason.ProviderFailed, 0L, start)): Unit
    kit.eventually("the next oldest")(RewardsView.eligibleFreeRounds(view, p, "g-1").filter(_.rewardId == newer))


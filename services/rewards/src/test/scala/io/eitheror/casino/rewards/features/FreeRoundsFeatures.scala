package io.eitheror.casino.rewards.features

import io.eitheror.casino.contract.facts.{CasinoRounds, RoundSettled}
import io.eitheror.casino.contract.money.{Currency, MinorUnits, SignedMinorUnits}
import io.eitheror.casino.rewards.Json.*
import io.eitheror.casino.rewards.RewardsRuntime
import io.eitheror.casino.rewards.api.FreeRoundsGrants.Result
import io.eitheror.casino.rewards.clients.AwardTerms
import io.eitheror.casino.rewards.it.{Answer, FakeGameProvider}
import io.eitheror.casino.rewards.reward.{RewardState, RewardStatus}

import java.time.Duration as JDuration
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * features/rewards/free-rounds.feature (US5; FR-023 to FR-026, FR-022b). The offers are created through `/offers`; the
 * rounds are given by the campaign's award route (`POST /reward-campaigns/{playerId}/award`, the grant path every route
 * shares), its answer read back as the grant's `Result`; the provider is `FakeGameProvider`, the rounds its
 * `round_settled` facts on the kit's broker, the wallet `FakeWallet`, the expiry its `rounds-<reward>` timer made due
 * with the clock past it; the reads are their routes.
 */
class FreeRoundsFeatures extends RewardsFeatures("free-rounds.feature"):
  private val Eur = Currency.unsafe("EUR")

  private var offerId = ""
  private var game    = ""
  private var rewardId = ""
  private var result: Result = Result.Unavailable
  private var first   = ""
  private var funds   = ""
  private var before: Option[RewardState] = None
  private var count   = 0

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    offerId = ""; game = ""; rewardId = ""; result = Result.Unavailable; first = ""; funds = ""; before = None; count = 0

  private def unique(prefix: String) = s"$prefix-${UUID.randomUUID().toString.take(8)}"

  private def pollEvery(millis: Long): Unit =
    RewardsRuntime.wire(kit.settings.copy(pollEvery = JDuration.ofMillis(millis)), () => kit.now())

  /** A free-rounds offer of `rounds` on a new game the provider lists as eligible, at 0.20 it offers in euros. */
  private def spinsOffer(rounds: Int = 3, fields: Seq[(String, Any)] = Nil, value: String = "0.20"): String =
    game = unique("game")
    count = rounds
    kit.provider.game(game, "free_spins")
    kit.provider.spinValues("EUR", value)
    offerId = offer((Vector[(String, Any)]("name" -> unique("Spins"), "type" -> "FREE_SPINS", "currency" -> "EUR", "gameId" -> game,
      "gameName" -> "Book of Spins", "provider" -> "st8", "spinCount" -> rounds,
      "spinValue" -> io.eitheror.casino.contract.money.Amounts.parse(value, Eur).toOption.get.value) ++ fields)*)
    offerId

  /**
   * The campaign's award answer as the grant's `Result`: `{ok, grantId, providerStatus}` granted, 502 the provider's
   * rejection naming the grant, 503 unavailable, any other refusal its code and fields (the route answers a decline and
   * a refusal alike, 422 `{error}`, and no step here tells them apart).
   */
  private def asResult(r: io.eitheror.casino.rewards.it.Reply): Result =
    import scala.jdk.CollectionConverters.*
    (r.status, r.json.text("error")) match
      // The player still holds the offer: a step that gives it again waits for this to pass.
      case (200, _) if r.json.path("alreadyProcessed").asBoolean(false) => Result.Refused("already_processed", Vector.empty)
      case (200, _)   => Result.Granted(r.json.text("grantId").getOrElse(fail(r.text)), r.json.text("providerStatus").getOrElse(fail(r.text)))
      case (502, _)   => Result.ProviderRejected(r.json.text("grantId").getOrElse(fail(r.text)))
      case (503, _)   => Result.Unavailable
      case (_, Some(code)) =>
        Result.Refused(code, r.json.properties.asScala.toVector.filterNot(_.getKey == "error").map { e =>
          val v = e.getValue
          e.getKey -> (if v.isArray then v.elements.asScala.map(_.asLong).toVector else v.asText)
        })
      case _ => fail(s"not an answer of the award: ${r.status} ${r.text}")

  /**
   * The offer given to the player by the campaign's award, or, `byOperator`, by an operator's (`POST /reward-grants`):
   * the campaign answers a second give of an offer the player holds `alreadyProcessed`, the operator gives it again.
   */
  private def give(byOperator: Boolean = false): Result =
    val reply =
      if byOperator then kit.json("POST", kit.grantsPath(s"/$player"), json("offerId" -> offerId), operator)
      else kit.json("POST", kit.campaignsPath(s"/$player/award"), json("offerId" -> offerId), kit.asService("engagement"))
    result = asResult(reply)
    result match
      case Result.Granted(id, _)        => rewardId = id
      case Result.ProviderRejected(id)  => rewardId = id
      case _                            => ()
    result

  private def granted(byOperator: Boolean = false): String = give(byOperator) match
    case Result.Granted(id, _) => id
    case other                 => fail(s"the free rounds were not given: $other")

  private def until(status: RewardStatus): RewardState =
    eventually(s"$rewardId ${status.wire}")(Some(reward(rewardId)).filter(_.status == status))

  private def settle(round: String, win: Long): Unit =
    kit.publish(CasinoRounds, s"$player/EUR", RoundSettled(player, Eur, round, "st8", s"p-$round", game, "s-1", MinorUnits.unsafe(0),
      MinorUnits.unsafe(win), SignedMinorUnits(win), RoundSettled.Outcome.Win, Vector.empty, Vector.empty, Vector.empty, Some(rewardId),
      kit.now()))

  private def counted(n: Int): RewardState =
    eventually(s"$n rounds counted")(Some(reward(rewardId)).filter(_.freeRounds.exists(_.tally.seen.size >= n)))

  /** Live rounds of `rounds`, every round but the last settled with `win` each. */
  private def livePlayed(rounds: Int, win: Long, fields: Seq[(String, Any)] = Nil): Unit =
    spinsOffer(rounds, fields)
    granted(): Unit
    (1 until rounds).foreach(i => settle(s"rd-$i", win))
    counted(rounds - 1): Unit

  private def activeReward(): Unit =
    funds = unique("funds")
    RewardsRuntime.wallet(kit.services).award(player, Eur, funds, AwardTerms("o-funds", 1000L, 10L, 1000L, Some(5L), None)): Unit

  private val Restricted = Seq("winningsDestination" -> "bonus_balance", "maxConvertibleAmount" -> 5000L, "wageringMultiplier" -> 20L)

  private def keyed(route: String, key: String) =
    eventually(s"$route $key")(kit.wallet.calls(route).flatMap(_.json).find(_.text("key").contains(key)))

  private def onWallet = kit.wallet.received.filter(r => r.method == "POST" && r.path.contains(s"/wallets/$player/"))

  private def pastExpiry(): Unit =
    kit.advance(JDuration.ofDays(31))
    kit.fireTimer(s"rounds-$rewardId")

  // ── background ──────────────────────────────────────────────────────────────────────────────

  Given("a player of brand {string}") { (slug: String) => assertEquals(kit.slug, slug) }

  // ── giving free rounds ──────────────────────────────────────────────────────────────────────

  Given("a free rounds offer of {string} rounds on an eligible game with a spin value of {string} offered by the game provider in {string}") {
    (rounds: String, value: String, currency: String) =>
      assertEquals(currency, "EUR")
      spinsOffer(rounds.toInt, value = value): Unit
  }

  Given("a free rounds offer with a spin value the game provider does not offer") { () =>
    spinsOffer(): Unit
    kit.provider.spinValues("EUR", "0.50", "0.10")
  }

  Given("a free rounds offer on a game that is not eligible") { () =>
    spinsOffer(): Unit
    kit.provider.game(game)
  }

  Given("a free rounds offer on an eligible game") { () => spinsOffer(): Unit }

  Given("a game provider that answers that it is still processing them") { () =>
    pollEvery(200)
    kit.provider.createAnswer = Some(FakeGameProvider.status("processing"))
  }

  Given("a game provider that cannot list its spin values") { () =>
    kit.provider.spinValuesAnswer = Some(Answer(503, """{"error":"down"}"""))
  }

  When("the free rounds are given") { () => give(): Unit }

  Then("the game provider is asked to create {string} rounds under the reward's id") { (rounds: String) =>
    assert(result.isInstanceOf[Result.Granted], s"$result")
    val created = kit.provider.createCalls.flatMap(_.json).filter(_.text("grantId").contains(rewardId))
    assertEquals(created.map(b => (b.long("count"), b.strings("gameCodes"))), Vector((Some(rounds.toLong), Vector(game))))
  }

  Then("the game provider is asked to create them") { () =>
    assert(result.isInstanceOf[Result.Granted], s"$result")
    assertEquals(kit.provider.createCalls.flatMap(_.json).count(_.text("grantId").contains(rewardId)), 1)
  }

  Then("the reward is live with an expiry of the offer's days, {string} by default") { (days: String) =>
    val s = reward(rewardId)
    assertEquals(s.status, RewardStatus.Active)
    assertEquals(s.expiresAt, s.grantedAt.map(_.plus(days.toLong, ChronoUnit.DAYS)))
    val created = kit.provider.createCalls.flatMap(_.json).filter(_.text("grantId").contains(rewardId))
    assertEquals(created.map(_.long("durationSeconds")), Vector(Some(days.toLong * 86400L)))
  }

  Then("it is refused as {string} with the values the game provider offers") { (code: String) =>
    assertEquals(result, Result.Refused(code, Vector("allowedValues" -> Vector(50L, 10L))))
    assertEquals(kit.provider.createCalls.flatMap(_.json).count(_.text("playerId").contains(player)), 0)
  }

  Then("it is refused as {string}") { (code: String) =>
    assertEquals(result, Result.Refused(code))
    assertEquals(kit.provider.createCalls.flatMap(_.json).count(_.text("playerId").contains(player)), 0)
  }

  Then("it is answered {string}") { (status: String) =>
    result match
      case Result.Granted(_, s) => assertEquals(s, status)
      case other                => fail(s"$other")
  }

  Then("the game provider is asked again until it confirms") { () =>
    eventually("polled")(Option.when(kit.provider.polls(rewardId).nonEmpty)(()))
    kit.provider.statusFor(rewardId, "active")
    eventually("confirmed")(Option.when(reward(rewardId).freeRounds.flatMap(_.providerStatus).contains("active"))(()))
    assertEquals(kit.provider.createCalls.flatMap(_.json).count(_.text("grantId").contains(rewardId)), 1)
  }

  // ── the provider failing ────────────────────────────────────────────────────────────────────

  Given("free rounds the game provider does not confirm within the time allowed") { () =>
    pollEvery(50)
    spinsOffer(): Unit
    kit.provider.createAnswer = Some(FakeGameProvider.status("processing"))
    first = granted()
  }

  When("the polling fails") { () => until(RewardStatus.Cancelled): Unit }

  Then("the reward is cancelled as the game provider failed") { () =>
    assertEquals(reward(rewardId).terminationReason, Some("provider_failed"))
    assertEquals(kit.provider.polls(rewardId).size, kit.settings.pollLimit)
    assertEquals(onWallet, Vector.empty)
  }

  Then("the player's free rounds no longer list it") { () =>
    // The list is the view's, which follows the reward's events.
    val r = eventually("no longer listed")(Some(kit.request("GET", kit.rewardsPath(s"/$player/free-rounds"), None, kit.asPlayer(player)))
      .filter(r => r.status == 200 && r.json.size == 0))
    assertEquals(r.status, 200)
  }

  Then("the player may be given the offer again") { () =>
    kit.provider.createAnswer = None
    eventually("not a claim")(Some(give()).collect { case Result.Granted(id, _) => id })
    assertNotEquals(rewardId, first)
  }

  // ── rounds ──────────────────────────────────────────────────────────────────────────────────

  Given("live free rounds of the player") { () =>
    spinsOffer(): Unit
    granted(): Unit
  }

  When("a round settles with a win of {string}") { (win: String) => settle("rd-1", win.toLong) }

  Then("the reward has used one round") { () =>
    val s = counted(1)
    assertEquals(s.freeRounds.map(f => (f.tally.used, f.tally.remaining)), Some((1, count - 1)))
  }

  Then("it has accumulated {string}") { (amount: String) =>
    assertEquals(reward(rewardId).freeRounds.map(_.tally.accumulated), Some(amount.toLong))
  }

  Given("live free rounds of the player with a round {string} already settled") { (round: String) =>
    spinsOffer(): Unit
    granted(): Unit
    settle(round, 100L)
    before = Some(counted(1))
  }

  When("round {string} settles again") { (round: String) =>
    settle(round, 100L)
    settle("rd-marker", 0L)
    counted(2): Unit
  }

  Then("nothing changes") { () =>
    val s = reward(rewardId)
    assertEquals(s.freeRounds.map(f => (f.tally.used, f.tally.accumulated)), before.flatMap(_.freeRounds).map(f => (f.tally.used + 1, f.tally.accumulated)))
    assertEquals(onWallet, Vector.empty)
  }

  Given("live free rounds whose offer pays winnings to real balance") { () => livePlayed(2, 150L) }

  Given("live free rounds whose offer pays winnings to real balance, and an active reward already") { () =>
    activeReward()
    livePlayed(2, 150L)
  }

  Given("live free rounds of the player with nothing won") { () => livePlayed(2, 0L) }

  Given("live free rounds whose offer pays winnings to restricted funds") { () => livePlayed(2, 150L, Restricted) }

  Given("live free rounds whose offer pays winnings to restricted funds, and an active reward already") { () =>
    activeReward()
    livePlayed(2, 150L, Restricted)
  }

  When("the last round settles") { () =>
    val win = if reward(rewardId).freeRounds.exists(_.tally.accumulated > 0) then 50L else 0L
    settle(s"rd-$count", win)
  }

  Then("the full accumulated amount is credited to real balance") { () =>
    assertEquals(keyed("provider-credits", s"$rewardId/credit").long("amount"), Some(200L))
    eventually("on real balance")(Option.when(kit.wallet.realOf(player, "EUR") == 200L)(()))
    assertEquals(until(RewardStatus.Closed).terminationReason, Some("free_spins_finalised"))
  }

  Then("the full accumulated amount is credited to the active reward's restricted funds") { () =>
    assertEquals(keyed("provider-credits", s"$rewardId/credit").long("amount"), Some(200L))
    eventually("on the active record")(kit.wallet.open(player, "EUR").find(_.grantRef == funds).filter(_.amount == 1200L))
    assertEquals(kit.wallet.realOf(player, "EUR"), 0L)
  }

  Then("nothing is paid") { () =>
    until(RewardStatus.Closed): Unit
    Thread.sleep(300)
    assertEquals(onWallet, Vector.empty)
  }

  Then("the reward is completed") { () =>
    assertEquals(until(RewardStatus.Closed).terminationReason, Some("free_spins_finalised"))
  }

  Then("the accumulated amount is awarded as restricted funds with the offer's wagering multiplier and maximum convertible amount") { () =>
    val award = keyed("awards", s"$rewardId/winnings")
    assertEquals((award.long("amount"), award.long("wageringMultiplier"), award.long("maxWin")), (Some(200L), Some(20L), Some(5000L)))
  }

  Then("the award stacks beside the active reward") { () =>
    assertEquals(keyed("awards", s"$rewardId/winnings").long("priority"), Some(4L))
    eventually("both open")(Option.when(kit.wallet.open(player, "EUR").map(_.grantRef).toSet == Set(funds, s"$rewardId/winnings"))(()))
  }

  // ── expiry ──────────────────────────────────────────────────────────────────────────────────

  Given("live free rounds past their expiry with rounds unused and something won") { () =>
    spinsOffer(5): Unit
    granted(): Unit
    settle("rd-1", 300L)
    counted(1): Unit
  }

  Given("live free rounds past their expiry with rounds unused and nothing won") { () =>
    spinsOffer(5): Unit
    granted(): Unit
  }

  When("the expiry passes") { () => pastExpiry() }

  Then("whatever accumulated is awarded or credited in full") { () =>
    assertEquals(keyed("provider-credits", s"$rewardId/credit").long("amount"), Some(300L))
  }

  Then("the reward is expired") { () =>
    assertEquals(until(RewardStatus.Expired).terminationReason, Some("free_spins_expired_unused"))
    assertEquals(onWallet, Vector.empty)
  }

  Given("free rounds already expired") { () =>
    spinsOffer(5): Unit
    granted(): Unit
    pastExpiry()
    until(RewardStatus.Expired): Unit
  }

  When("a round taken before the expiry settles late") { () => settle("rd-late", 80L) }

  Then("it is still honoured") { () =>
    assertEquals(keyed("provider-credits", s"$rewardId/late/rd-late").long("amount"), Some(80L))
  }

  // ── spin values ─────────────────────────────────────────────────────────────────────────────

  Given("a brand with free rounds set up at the game provider") { () => kit.provider.spinValues("EUR", "0.50", "0.10", "0.20") }

  Given("a brand with no free rounds set up at the game provider") { () => kit.provider.notConfigured = true }

  When("an operator asks a game's spin values in {string}") { (currency: String) =>
    last = kit.request("GET", kit.offersPath(s"/free-rounds/spin-values?gameId=g-1&currency=$currency"), None, operator)
  }

  Then("the game provider's values are listed sorted") { () =>
    assertEquals((last.status, last.str("source"), last.json.get("values").toString), (200, "st8", "[10,20,50]"), last.text)
  }

  Then("no values are listed, the source named {string}") { (source: String) =>
    assertEquals((last.status, last.str("source"), last.json.get("values").size), (200, source, 0), last.text)
  }

  // ── a launch ────────────────────────────────────────────────────────────────────────────────

  Given("live free rounds of the player on a game") { () =>
    spinsOffer(): Unit
    first = granted()
    granted(byOperator = true): Unit
    eventually("both in the view")(Option.when(rows().count(_.offerId == offerId) == 2)(()))
  }

  When("the game provider asks which free rounds the player may play on that game") { () =>
    last = kit.request("GET", kit.rewardsPath(s"/$player/free-rounds/eligible?provider=st8&gameCode=$game"), None,
      kit.asService("game-provider"))
  }

  Then("it is answered the oldest live free rounds for that game") { () =>
    assertEquals((last.status, last.str("grantId")), (200, first), last.text)
  }

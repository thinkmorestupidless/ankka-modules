package io.eitheror.casino.rewards.features

import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.contract.money.Currency
import io.eitheror.casino.rewards.eligibility.{Anchor, Evaluator, Mode, Request}
import io.eitheror.casino.rewards.it.{ClaimPaths, Claimed}
import io.eitheror.casino.rewards.player.{ClaimOffer, Outcome, PlayerRewards}
import io.eitheror.casino.rewards.reward.{RewardStatus, Source}

import java.time.Duration as JDuration

/**
 * features/rewards/eligibility.feature (US2; FR-005, FR-006, FR-008): one evaluator on every path. The paths built so
 * far are driven through the service: the player's selection, a code, a confirming deposit; each answers a decline in
 * its own shape (`ClaimPaths`). The player's duplicate signals arrive on `casino.players` as `players` publishes them,
 * and the brand's detection switch is `FakePlayers`'. The campaign's award route and the operator's award are tasks
 * T059 and T060: their scenarios are pending on them.
 *
 * Two rows of the outline name rules that are dormant (research R4): nothing writes a player's segments or a tier rank,
 * so no path can reach them. Those rows are bound on `Evaluator` itself, with the player's record as the service holds
 * it and the dormant input given, which is the one place the rule lives. The code row is bound on `PlayerRewards.claim`,
 * the award path's evaluation, since the select, code and deposit paths verify the code before they evaluate.
 */
class EligibilityFeatures extends RewardsFeatures("eligibility.feature"):
  override protected def ranElsewhere: Map[String, String] = EligibilityFeatures.Pending

  private def paths = ClaimPaths(kit)
  private val Eur   = Currency.unsafe("EUR")

  private var claims: Vector[Claimed] = Vector.empty
  private var claim: () => Claimed    = () => throw AssertionError("no claim set up")
  private var offerId                 = ""

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    // The switch read last is the brand's until a check fact reads it again: a scenario turning it off leaves it read.
    io.eitheror.casino.rewards.consumers.Detection.forget()
    claims = Vector.empty
    claim = () => throw AssertionError("no claim set up")
    offerId = ""

  private def one(c: Claimed): Unit = claims = Vector(c)

  private def refused(reason: String): Unit =
    assert(claims.nonEmpty, "nothing was claimed")
    claims.foreach(c => assertEquals(c.refusal, Some(reason), s"${c.path}: ${c.reply.map(_.text).getOrElse("")}"))

  private def proceeds(): Unit =
    assert(claims.nonEmpty, "nothing was claimed")
    claims.foreach(c => assertEquals(c.refusal, None, s"${c.path}: ${c.reply.map(_.text).getOrElse("")}"))

  // ── steps ──────────────────────────────────────────────────────────────────────────────────

  Given("brand {string}") { (slug: String) => assertEquals(kit.slug, slug) }

  Given("a player flagged as a suspected duplicate") { () =>
    paths.checked(player)
    paths.flagged(player)
  }

  When("they claim an offer on any path") { () =>
    val p = paths
    val code = p.unique("DUP")
    claims = Vector(
      p.viaSelect(player, p.noDeposit()),
      { p.depositMatch(p.codes(code)): Unit; p.viaCode(player, code) },
      p.viaDeposit(player, p.depositMatch())
    )
  }

  Then("they are refused as {string}") { (reason: String) => refused(reason) }

  Given("a player flagged as a suspected duplicate at a brand that turned duplicate detection off") { () =>
    kit.players.multiAccountDetection = false
    paths.checkStarted(player, kit.now())
    paths.checkEnded(player, "skipped")
    paths.flagged(player)
    paths.awaitRecord(player, "detection off")(!_.detectionOn): Unit
  }

  When("they claim an offer") { () =>
    offerId = paths.noDeposit()
    one(paths.viaSelect(player, offerId))
  }

  Then("the duplicate rule is skipped") { () =>
    proceeds()
    paths.active(player, offerId): Unit
  }

  Given("a player with an active reward") { () => paths.holdingActive(player): Unit }

  Given("a player with two active rewards") { () =>
    paths.holdingActive(player): Unit
    paths.holdingActive(player): Unit
  }

  When("they claim another offer allowing one open reward") { () =>
    offerId = paths.noDeposit("maxConcurrentGrants" -> 1)
    one(paths.viaSelect(player, offerId))
  }

  When("they claim an offer allowing two open rewards") { () =>
    offerId = paths.noDeposit("maxConcurrentGrants" -> 2)
    one(paths.viaSelect(player, offerId))
  }

  When("they claim an offer that sets no maximum of open rewards") { () =>
    offerId = paths.noDeposit()
    one(paths.viaSelect(player, offerId))
  }

  Then("the claim proceeds") { () =>
    proceeds()
    paths.active(player, offerId): Unit
  }

  Given("a player registered five minutes ago whose duplicate check is in progress") { () =>
    val at = paths.ago(JDuration.ofMinutes(5))
    paths.registered(player, at)
    paths.checkStarted(player, at)
  }

  Given("a player registered eleven minutes ago whose duplicate check never ended") { () =>
    val at = paths.ago(JDuration.ofMinutes(11))
    paths.registered(player, at)
    paths.checkStarted(player, at)
  }

  When("they claim an offer with no deposit required") { () =>
    offerId = paths.noDeposit()
    one(paths.viaSelect(player, offerId))
  }

  Then("the duplicate check is not what refuses them") { () =>
    claims.foreach(c => assertNotEquals(c.refusal, Some("ELIGIBILITY_CHECK_UNAVAILABLE"), c.path))
    proceeds()
  }

  Given("a player whose no-deposit offers are blocked by a weak duplicate signal") { () =>
    paths.checkStarted(player, paths.ago(JDuration.ofMinutes(20)))
    paths.blocked(player)
    paths.checkEnded(player, "weak")
  }

  Given("a player whose duplicate check is in progress and whose no-deposit offers are blocked") { () =>
    paths.registered(player, kit.now())
    paths.checkStarted(player, kit.now())
    paths.blocked(player)
  }

  When("they claim an offer requiring a deposit") { () =>
    offerId = paths.depositMatch()
    one(paths.viaDeposit(player, offerId))
  }

  Then("neither refuses them") { () =>
    proceeds()
    paths.active(player, offerId): Unit
  }

  Given("an offer that conflicts with an offer the player holds open") { () =>
    val held = paths.holdingActive(player)
    offerId = paths.noDeposit("conflictsWith" -> io.eitheror.casino.rewards.Json.arr(held))
  }

  When("the player claims it") { () => one(paths.viaSelect(player, offerId)) }

  Given("an offer the player has claimed the maximum times per player") { () =>
    offerId = paths.holdingActive(player, "maxClaimsPerPlayer" -> 1)
  }

  When("they claim it again") { () => one(paths.viaSelect(player, offerId)) }

  Given("an offer excluded by one the player already claimed") { () =>
    val held = paths.holdingActive(player)
    offerId = paths.noDeposit("excludedOfferIds" -> io.eitheror.casino.rewards.Json.arr(held))
  }

  // ── the outline: each situation on the path that reaches its rule ─────────────────────────

  Given("an offer code the offer does not carry") { () =>
    val id = paths.noDeposit(paths.codes(paths.unique("RIGHT")))
    claim = () =>
      val answer = kit.components.forEventSourcedEntity(EntityId(player)).call(PlayerRewards.claim)
        .invoke(ClaimOffer(paths.offerOf(id), operator = false, None, Source.CustomerioWebhook, Some("WRONG"), None, 0, Eur, kit.now()))
      answer.outcome match
        case Outcome.Declined(_, reason) => Claimed("award", Some(reason))
        case _                           => Claimed("award", None)
  }

  Given("a deposit below the offer's minimum deposit") { () =>
    val id = paths.depositMatch("minDepositAmount" -> 2000L)
    claim = () => paths.viaDeposit(player, id, amount = 1000L)
  }

  Given("a deposit that is not the offer's deposit number") { () =>
    val id = paths.depositMatch("depositNumberTarget" -> 2)
    claim = () => paths.viaDeposit(player, id, ordinal = Some(1))
  }

  Given("a claim after the claim window counted from registration") { () =>
    paths.registered(player, paths.ago(JDuration.ofDays(2)))
    val id = paths.noDeposit("claimWindowDays" -> 1)
    claim = () => paths.viaSelect(player, id)
  }

  Given("a player outside the offer's segment") { () =>
    val id = paths.noDeposit("segmentPredicate" -> "high_rollers")
    claim = () => dormant(id, _.copy(segments = Some(Set("casual"))))
  }

  Given("a player below the offer's tier floor") { () =>
    val id = paths.noDeposit("vipTierFloor" -> 3)
    claim = () => dormant(id, _.copy(tierRank = Some(1)))
  }

  /** A dormant rule: the evaluator over the player's record as the service holds it, with the input nothing writes. */
  private def dormant(id: String, input: io.eitheror.casino.rewards.eligibility.PlayerSnapshot => io.eitheror.casino.rewards.eligibility.PlayerSnapshot): Claimed =
    val record = paths.record(player)
    val request = Request(Mode.Standard, Anchor.registration(record.registeredAt, kit.now()), Eur, detection = record.detectionOn,
      codeVerifiedAtTargeting = true, enforceMinDeposit = Some(false))
    Claimed("evaluator", Evaluator.evaluate(paths.offerOf(id), input(record.snapshot), 0, request, kit.now()).left.toOption.map(_.reason))

  When("the player claims the offer") { () => one(claim()) }

  // ── a code verified at targeting ───────────────────────────────────────────────────────────

  Given("an offer code verified when the offer was targeted at the player") { () =>
    val code = paths.unique("VERIFIED")
    offerId = paths.depositMatch(paths.codes(code))
    val entered = paths.viaCode(player, code.toLowerCase)
    assertEquals(entered.refusal, None, entered.reply.map(_.text).getOrElse(""))
    assertEquals(paths.rewardOf(player, offerId).status, RewardStatus.Pending)
  }

  When("the deposit confirms") { () => paths.deposit(player, 3000L): Unit }

  Then("the code is not demanded again") { () =>
    val r = paths.active(player, offerId)
    assertEquals(r.lastDecline, None)
  }

object EligibilityFeatures:
  val Pending: Map[String, String] =
    RewardsFeatures.pending("T059", "an award by the brand's engagement during the duplicate check is answered to try again later") ++
      RewardsFeatures.pending("T060",
        "an operator's award proceeds past every rule but the duplicate checks and the open reward maximum")

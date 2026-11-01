package io.eitheror.casino.rewards.features

import com.fasterxml.jackson.databind.JsonNode
import io.eitheror.casino.contract.facts.{DepositConfirmed, MoneyTransactions}
import io.eitheror.casino.contract.money.{Currency, MinorUnits, SignedMinorUnits}
import io.eitheror.casino.rewards.Json
import io.eitheror.casino.rewards.Json.*
import io.eitheror.casino.rewards.it.Received
import io.eitheror.casino.rewards.reward.RewardStatus

import java.time.Duration as JDuration
import java.util.UUID

/**
 * features/rewards/offers.feature (US1; FR-001 to FR-003, FR-011 to FR-013). The operator creates offers through
 * `/offers`; the player selects through `/rewards`; a deposit arrives as the wallet's `deposit_confirmed` on the
 * broker, and the award is the wallet's as `FakeWallet` keeps it. Making an offer visible to the player is the
 * campaign route's story (US6): here it is done on `PlayerRewards` itself, as the scene for selecting.
 */
class OffersFeatures extends RewardsFeatures("offers.feature"):
  override protected def ranElsewhere: Map[String, String] = OffersFeatures.Pending

  private val Eur = Currency.unsafe("EUR")

  private var offerId              = ""
  private var sent: Vector[(String, Any)] = Vector.empty
  private var lastDeposit: Option[DepositConfirmed] = None
  private var ordinal              = 0

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    offerId = ""
    sent = Vector.empty
    lastDeposit = None
    ordinal = 0

  private def unique(prefix: String) = s"$prefix-${UUID.randomUUID().toString.take(8)}"

  private def depositMatch(percentage: Long, max: Option[Long], multiplier: Long, minDeposit: Option[Long],
      claimWindowDays: Option[Int] = Some(7)): Vector[(String, Any)] =
    Vector[(String, Any)]("name" -> unique("Welcome"), "description" -> "A match on the next deposit", "type" -> "DEPOSIT_MATCH",
      "bonusPercentage" -> percentage * 100, "maxBonusValue" -> max, "wageringMultiplier" -> multiplier, "minDepositAmount" -> minDeposit,
      "currency" -> "EUR", "claimWindowDays" -> claimWindowDays, "wageringWindowDays" -> 30, "maxClaimsPerPlayer" -> 1,
      "depositNumberTarget" -> "ANY",
      "redemptionCodes" -> Json.arr(Json.obj("code" -> unique("WELCOME"), "isAffiliate" -> false)))

  private val noDeposit: Vector[(String, Any)] =
    Vector[(String, Any)]("type" -> "NO_DEPOSIT", "noDepositRequired" -> true, "maxBonusValue" -> 1000L, "wageringMultiplier" -> 10L,
      "currency" -> "EUR")

  /** A pure free-rounds offer on a game the provider lists as eligible, at a spin value it offers. */
  private def freeRounds(fields: (String, Any)*): Vector[(String, Any)] =
    val game = unique("g")
    kit.provider.game(game, "free_spins")
    kit.provider.spinValues("EUR", "0.10", "0.20")
    Vector[(String, Any)]("name" -> unique("Spins"), "type" -> "FREE_SPINS", "currency" -> "EUR", "gameId" -> game, "gameName" -> "Book",
      "provider" -> "st8", "spinCount" -> 10, "spinValue" -> 20L) ++ fields

  private def named(fields: Vector[(String, Any)]) = fields.prepended("name" -> unique("Offer"))

  // ── the player ─────────────────────────────────────────────────────────────────────────────

  /** The offer made visible to the player, as a campaign or a code does (US6, US3). */
  private def target(id: String): Unit = Scene.target(kit, player, id)

  private def select(id: String): Unit =
    last = kit.json("POST", kit.rewardsPath(s"/$player/select"), json("offerId" -> id), kit.asPlayer(player))

  private def selected(id: String): Unit =
    target(id)
    select(id)
    assertEquals(last.status, 200, last.text)

  private def deposit(amount: Long, again: Boolean = false): Unit =
    val fact = lastDeposit.filter(_ => again).getOrElse {
      ordinal += 1
      DepositConfirmed(player, Eur, unique("txn"), MinorUnits.unsafe(amount), unique("pay"), ordinal, SignedMinorUnits(amount),
        MinorUnits.unsafe(0L), kit.now())
    }
    lastDeposit = Some(fact)
    kit.publish(MoneyTransactions, player, fact)

  private def awards: Vector[Received] = kit.wallet.calls("awards").filter(_.path.contains(s"/wallets/$player/"))

  private def statusOf(id: String): RewardStatus = rewardOf(id).status

  private def until(status: RewardStatus) =
    eventually(s"the reward $status")(Some(rewardOf(offerId)).filter(_.status == status))

  // ── steps ──────────────────────────────────────────────────────────────────────────────────

  Given("brand {string}") { (slug: String) => assertEquals(kit.slug, slug) }

  Given("an operator with a role for the brand") { () => () }

  When("they create an offer of type {string} with percentage {string}, maximum value {string}, wagering multiplier {string} and minimum deposit {string}") {
    (t: String, pct: String, max: String, mult: String, min: String) =>
      sent = depositMatch(pct.toLong, Some(max.toLong), mult.toLong, Some(min.toLong)).map {
        case ("type", _) => "type" -> t
        case other       => other
      }
      createOffer(sent*)
  }

  Then("the offer is listed as active for the brand with every field as given") { () =>
    assertEquals(last.status, 201, last.text)
    val id    = last.str("id")
    val found = offers.find(_.text("id").contains(id)).getOrElse(fail(s"$id is not listed"))
    assertEquals(found.bool("active"), Some(true))
    sent.foreach {
      case ("redemptionCodes", codes: JsonNode) =>
        assertEquals(found.objects("redemptionCodes").flatMap(_.text("code")), Vector(codes.get(0).get("code").asText))
      case (field, Some(v)) => assertEquals(found.text(field), Some(v.toString), field)
      case (field, None)    => assertEquals(found.text(field), None, field)
      case (field, v)       => assertEquals(found.text(field), Some(v.toString), field)
    }
  }

  When("they create an offer of type {string} without a maximum value") { (t: String) =>
    createOffer(depositMatch(100, None, 30, Some(2000L)).map { case ("type", _) => "type" -> t; case o => o }*)
  }

  Then("it is refused naming the maximum value as required") { () => refusedWith(400, "bonus_amount_required"): Unit }

  When("they create a free rounds offer without a maximum value") { () => createOffer(freeRounds()*) }

  Then("the offer is created") { () =>
    assertEquals(last.status, 201, last.text)
    assert(offers.exists(_.text("id").contains(last.str("id"))))
  }

  When("they create a free rounds offer paying winnings to restricted funds without a maximum convertible amount") { () =>
    createOffer(freeRounds("winningsDestination" -> "bonus_balance", "wageringMultiplier" -> 5L)*)
  }

  Then("it is refused naming the maximum convertible amount as required") { () =>
    refusedWith(400, "max_convertible_amount_required"): Unit
  }

  Given("an active offer with no deposit required") { () => offerId = offer(named(noDeposit)*) }

  Given("an active offer requiring a deposit") { () => offerId = offer(depositMatch(100, Some(50000L), 30, Some(2000L))*) }

  When("an eligible player selects it") { () =>
    target(offerId)
    select(offerId)
  }

  Then("the reward activates at once with the offer's fixed amount and no deposit") { () =>
    assertEquals((last.status, last.json.bool("ok")), (200, Some(true)), last.text)
    val r = until(RewardStatus.Active)
    assertEquals(r.faceValue, 1000L)
    assertEquals(awards.flatMap(_.json.flatMap(_.long("amount"))), Vector(1000L))
    assertEquals(kit.wallet.open(player, "EUR").map(_.amount), Vector(1000L))
  }

  Then("the reward is pending") { () =>
    assertEquals((last.status, last.json.bool("ok")), (200, Some(true)), last.text)
    assert(last.json.text("expiresAt").isDefined, last.text)
    assertEquals(statusOf(offerId), RewardStatus.Pending)
    assertEquals(awards, Vector.empty)
  }

  Given("a pending selection of a deposit match offer with percentage {string} and maximum value {string}") {
    (pct: String, max: String) =>
      offerId = offer(depositMatch(pct.toLong, Some(max.toLong), 30, None)*)
      selected(offerId)
  }

  When("the player's next confirmed deposit of {string} arrives") { (amount: String) => deposit(amount.toLong) }

  Then("the reward activates with restricted funds of {string}") { (amount: String) =>
    val r = until(RewardStatus.Active)
    assertEquals(r.faceValue, amount.toLong)
    assertEquals(kit.wallet.open(player, "EUR").map(_.amount), Vector(amount.toLong))
    assertEquals(kit.wallet.open(player, "EUR").map(_.requirement), Vector(amount.toLong * 30))
  }

  Then("the pending selection is cleared") { () =>
    val state = eventually("not pending")(Some(kit.request("GET", kit.rewardsPath(s"/$player/state"), None, kit.asPlayer(player)))
      .filter(r => r.status == 200 && r.str("state") == "active"))
    assertEquals(state.json.get("selected"), null)
  }

  Given("a pending selection of an offer with minimum deposit {string}") { (min: String) =>
    offerId = offer(depositMatch(100, Some(50000L), 30, Some(min.toLong))*)
    selected(offerId)
  }

  When("a deposit of {string} arrives") { (amount: String) => deposit(amount.toLong) }

  Then("the reward stays pending") { () =>
    val r = eventually("the decline recorded")(Some(rewardOf(offerId)).filter(_.lastDecline.isDefined))
    assertEquals((r.status, r.lastDecline), (RewardStatus.Pending, Some("BELOW_MIN_DEPOSIT")))
  }

  Then("the deposit is credited to real balance") { () =>
    // The deposit is the wallet's to credit; rewards withholds nothing and asks the wallet for nothing.
    assertEquals(awards, Vector.empty)
    assertEquals(kit.wallet.received.filter(_.path.contains(s"/wallets/$player/")).filter(_.method == "POST"), Vector.empty)
  }

  Then("the player may deposit again") { () =>
    deposit(3000L)
    assertEquals(until(RewardStatus.Active).faceValue, 3000L)
  }

  Given("a pending selection older than the deposit window of {string} days from selection") { (days: String) =>
    offerId = offer(depositMatch(100, Some(50000L), 30, None, claimWindowDays = Some(days.toInt))*)
    selected(offerId)
    kit.advance(JDuration.ofDays(days.toLong).plusHours(1))
  }

  When("a deposit arrives") { () => deposit(5000L) }

  Then("the reward is expired") { () => until(RewardStatus.Expired): Unit }

  Then("nothing is awarded") { () => assertEquals(awards, Vector.empty) }

  Given("an active reward awarded on a deposit") { () =>
    offerId = offer(depositMatch(100, Some(50000L), 30, None)*)
    selected(offerId)
    deposit(5000L)
    until(RewardStatus.Active): Unit
  }

  When("the same deposit is reported again under the same payment id") { () =>
    deposit(0L, again = true)
    // Nothing is written for a deposit already seen, so there is nothing to wait for but the consumer's turn.
    Thread.sleep(2000)
  }

  Then("nothing is awarded twice") { () =>
    assertEquals(awards.size, 1, awards.mkString("\n"))
    assertEquals(kit.wallet.open(player, "EUR").size, 1)
    assertEquals(statusOf(offerId), RewardStatus.Active)
  }

  Given("an offer with rewards given") { () =>
    offerId = offer(named(noDeposit)*)
    selected(offerId)
    until(RewardStatus.Active): Unit
  }

  When("an operator deletes it") { () => last = kit.request("DELETE", kit.offersPath(s"/$offerId"), None, operator) }

  Then("the offer is listed as not active") { () =>
    assertEquals((last.status, last.str("status")), (200, "deactivated"), last.text)
    val r = kit.request("GET", kit.offersPath(s"/$offerId"), None, operator)
    assertEquals((r.status, r.json.bool("active")), (200, Some(false)), r.text)
  }

  Then("the rewards already given are unchanged") { () =>
    assertEquals(statusOf(offerId), RewardStatus.Active)
    assertEquals(kit.wallet.open(player, "EUR").size, 1)
  }

object OffersFeatures:
  // Here, not in the class: GherkinSuite reads ranElsewhere while it is constructed, before the class's own vals.
  val Pending: Map[String, String] =
    RewardsFeatures.pending("T059 and T061",
      "an offer the brand's engagement targeted at a player appears in their eligible list with the reason it is visible",
      "targeting an offer at a player again changes nothing",
      "a player with rewards available or pending sees only those offers as selectable",
      "a player with no reward sees the offers visible to them by code or targeting",
      "targeting an offer of free rounds, or of a deposit match with free rounds, is refused") ++
      RewardsFeatures.pending("T061", "offers reordered by an operator are seen by players in that order") ++
      RewardsFeatures.pending("T060",
        "an operator reading a player's rewards sees every reward with its state, face value, source and clocks",
        "an operator reading the brand's rewards sees them across players, filterable by state",
        "an operator giving a free bonus with no offer awards restricted funds with the amount and wagering they set",
        "an operator giving free rounds with no offer creates them at the game provider") ++
      RewardsFeatures.pending("T049 and T061",
        "a player reading their reward state sees the active reward's balance, requirement and progress and their history")

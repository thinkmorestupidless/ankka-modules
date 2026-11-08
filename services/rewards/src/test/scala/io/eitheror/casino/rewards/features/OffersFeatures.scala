package io.eitheror.casino.rewards.features

import com.fasterxml.jackson.databind.JsonNode
import io.eitheror.casino.contract.facts.{DepositConfirmed, MoneyTransactions}
import io.eitheror.casino.contract.money.{Currency, MinorUnits, SignedMinorUnits}
import io.eitheror.casino.rewards.Json
import io.eitheror.casino.rewards.Json.*
import io.eitheror.casino.rewards.it.{Answer, Received, Reply}
import io.eitheror.casino.rewards.reward.RewardStatus

import java.time.Duration as JDuration
import java.util.UUID

/**
 * features/rewards/offers.feature (US1; FR-001 to FR-003, FR-011 to FR-013). The operator creates offers through
 * `/offers`; the player selects through `/rewards`; a deposit arrives as the wallet's `deposit_confirmed` on the
 * broker, and the award is the wallet's as `FakeWallet` keeps it. An offer is made visible to the player as the brand's
 * `engagement` does, through the target route (US6).
 */
class OffersFeatures extends RewardsFeatures("offers.feature"):
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
  private def target(id: String): Unit = Scene.target(kit, player, id): Unit

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

  // ── US6: targeting ─────────────────────────────────────────────────────────────────────────

  private var targetedReward = ""

  private def targetRole(role: String): Reply =
    kit.json("POST", kit.campaignsPath(s"/$player/target"), json("role" -> role), kit.asService("engagement"))

  Given("an offer the brand's engagement targeted at a player") { () =>
    offerId = offer(named(noDeposit)*)
    targetedReward = Scene.target(kit, player, offerId)
  }

  When("it is targeted at the player again") { () => last = targetRole(Scene.roleOf(kit, offerId)) }

  Then("nothing changes") { () =>
    assertEquals((last.status, last.json.bool("ok")), (200, Some(true)), last.text)
    Thread.sleep(500)
    assertEquals(rewardOf(offerId).rewardId, targetedReward)
    assertEquals(rows().filter(_.offerId == offerId).map(_.rewardId), Vector(targetedReward))
    assertEquals(statusOf(offerId), RewardStatus.Available)
  }

  Given("an offer of a deposit match with free rounds") { () =>
    offerId = offer(freeRounds("type" -> "DEPOSIT_MATCH_WITH_FREE_SPINS", "bonusPercentage" -> 10000L, "maxBonusValue" -> 50000L,
      "wageringMultiplier" -> 30L)*)
  }

  When("the brand's engagement targets it at a player") { () => last = targetRole(Scene.roleOf(kit, offerId)) }

  Then("it is refused as {string}") { (code: String) =>
    refusedWith(422, code): Unit
    assertEquals(rows(), Vector.empty)
  }

  // ── US6: the operator's lists and awards ───────────────────────────────────────────────────

  private var otherPlayer = ""

  private def grants(query: String): Reply = kit.request("GET", kit.grantsPath(query), None, operator)
  private def items(r: Reply): Vector[JsonNode] =
    assertEquals(r.status, 200, r.text)
    (0 until r.json.get("items").size).map(r.json.get("items").get).toVector

  Given("a player with rewards") { () =>
    offerId = offer(named(noDeposit)*)
    selected(offerId)
    until(RewardStatus.Active): Unit
    target(offer(named(noDeposit)*))
    eventually("both listed")(Some(rows()).filter(_.size == 2))
  }

  When("an operator reads the player's rewards") { () => last = grants(s"?playerId=$player") }

  Then("every reward is listed with its state, its face value in minor units, its source and its clocks") { () =>
    val listed = items(last)
    assertEquals(listed.flatMap(_.text("status")), Vector("AVAILABLE", "ACTIVE"), last.text)
    // Amounts are minor units on every route (FR-032): 1000 is the offer's 10.00.
    assertEquals(listed.flatMap(_.long("faceValue")), Vector(1000L, 1000L))
    assertEquals(listed.flatMap(_.text("source")).toSet, Set("customerio_webhook"))
    val active = listed(1)
    assert(active.text("grantedAt").isDefined && active.text("expiresAt").isDefined, active.toString)
    assert(listed.head.has("grantedAt") && listed.head.has("expiresAt"), listed.head.toString)
  }

  private var roundsGame = ""

  When("an operator gives a player {string} free rounds on an eligible game") { (rounds: String) =>
    roundsGame = s"game-${UUID.randomUUID().toString.take(8)}"
    kit.provider.game(roundsGame, "free_spins")
    kit.provider.spinValues("EUR", "0.20")
    last = kit.json("POST", kit.grantsPath(s"/$player"), json("type" -> "FREE_SPINS", "gameId" -> roundsGame, "provider" -> "st8",
      "spinCount" -> rounds.toInt, "spinValue" -> 20L), operator)
  }

  Then("the game provider is asked to create {string} rounds under the reward's id") { (rounds: String) =>
    assertEquals((last.status, last.str("status"), last.str("type")), (200, "granted", "FREE_SPINS"), last.text)
    val id      = last.str("grantId")
    val created = kit.provider.createCalls.flatMap(_.json).filter(_.text("grantId").contains(id))
    assertEquals(created.map(b => (b.long("count"), b.strings("gameCodes"))), Vector((Some(rounds.toLong), Vector(roundsGame))))
    val s = reward(id)
    assertEquals((s.status, s.offerType, s.playerId), (RewardStatus.Active, "MANUAL", player))
    assert(s.offerId.startsWith("manual:"), s.offerId)
  }

  Given("rewards across the brand's players") { () =>
    offerId = offer(named(noDeposit)*)
    selected(offerId)
    until(RewardStatus.Active): Unit
    otherPlayer = newPlayer()
    Scene.target(kit, otherPlayer, offerId): Unit
    eventually("the other's listed")(Some(rows(otherPlayer)).filter(_.nonEmpty))
  }

  When("an operator reads the brand's rewards") { () => last = grants(s"?offerId=$offerId") }

  Then("every reward is listed across players, filterable by state") { () =>
    assertEquals(items(last).flatMap(_.text("playerId")).toSet, Set(player, otherPlayer), last.text)
    assertEquals(items(grants(s"?offerId=$offerId&status=ACTIVE")).flatMap(_.text("playerId")), Vector(player))
    assertEquals(items(grants(s"?offerId=$offerId&status=available")).flatMap(_.text("playerId")), Vector(otherPlayer))
  }

  When("an operator gives a player a free bonus of {string} at a wagering multiplier of {string}") { (amount: String, wager: String) =>
    last = kit.json("POST", kit.grantsPath(s"/$player"), json("bonusAmount" -> amount.toLong, "wager" -> wager.toLong,
      "currency" -> "EUR"), operator)
  }

  Then("the wallet is asked to award {string} as restricted funds with a wagering requirement of {string}") {
    (amount: String, requirement: String) =>
      assertEquals((last.status, last.str("status")), (200, "granted"), last.text)
      assertEquals(awards.flatMap(_.json.flatMap(_.long("amount"))), Vector(amount.toLong))
      assertEquals(kit.wallet.open(player, "EUR").map(r => (r.amount, r.requirement)), Vector((amount.toLong, requirement.toLong)))
  }

  Then("the reward is active, with a wagering window of {string} days") { (days: String) =>
    val row = eventually("the reward active")(rows().find(_.status == RewardStatus.Active.wire))
    val r   = reward(row.rewardId)
    assertEquals((row.offerType, r.snapshot.wageringWindowDays), ("MANUAL", Some(days.toInt)))
    assertEquals(JDuration.between(r.grantedAt.get, r.expiresAt.get).toDays, days.toLong)
  }

  // ── US6: the player's lists ────────────────────────────────────────────────────────────────

  private var visible: Vector[String] = Vector.empty
  private var hidden                  = ""
  private var state: JsonNode         = null

  private def readState(): JsonNode =
    val r = kit.request("GET", kit.rewardsPath(s"/$player/state"), None, kit.asPlayer(player))
    assertEquals(r.status, 200, r.text)
    r.json

  private def elements(n: JsonNode): Vector[JsonNode] = (0 until n.size).map(n.get).toVector

  /** The offers the state lists: the selection first, then the rest. */
  private def listed(s: JsonNode): Vector[JsonNode] =
    Option(s.get("selected")).toVector ++ Vector("visibleOffers", "competingOffers").flatMap(f => Option(s.get(f)).toVector.flatMap(elements))

  When("the player lists their eligible offers") { () => state = readState() }
  When("they list their eligible offers") { () => state = readState() }

  Then("the offer appears with the reason it is visible") { () =>
    val found = listed(state).find(_.text("offerId").contains(offerId)).getOrElse(fail(s"$offerId not listed: $state"))
    assertEquals(found.text("source"), Some("customerio_webhook"))
  }

  Given("a player with rewards available or pending") { () =>
    val pendingOffer = offer(depositMatch(100, Some(50000L), 30, None)*)
    selected(pendingOffer)
    val available = offer(named(noDeposit)*)
    target(available)
    hidden = offer(named(noDeposit)*)
    visible = Vector(pendingOffer, available)
  }

  Then("only those offers are selectable") { () =>
    assertEquals(state.text("state"), Some("pending"), state.toString)
    assertEquals(listed(state).flatMap(_.text("offerId")), visible)
    assert(!state.toString.contains(hidden), state.toString)
  }

  Given("a player with no reward available or pending") { () =>
    // Visibility is a reward held AVAILABLE (research R3): what a code or a campaign made visible is what is listed.
    // A code selects what it matches (research, "a matched code selects"), so it is listed as the selection.
    val code = unique("SEEN")
    val byCode = offer((depositMatch(100, Some(50000L), 30, None).filterNot(_._1 == "redemptionCodes") :+
      ("redemptionCodes" -> Json.arr(Json.obj("code" -> code, "isAffiliate" -> false))))*)
    val targeted = offer(named(noDeposit)*)
    hidden = offer(named(noDeposit)*)
    assertEquals(readState().text("state"), Some("available"))
    assertEquals(elements(readState().get("visibleOffers")), Vector.empty)
    target(targeted)
    val entered = kit.json("POST", kit.rewardsPath(s"/$player/codes"), json("code" -> code), kit.asPlayer(player))
    assertEquals(entered.status, 200, entered.text)
    visible = Vector(byCode, targeted)
  }

  Then("the offers visible to them by offer code or targeting are listed") { () =>
    val offers = listed(state)
    assertEquals(offers.flatMap(_.text("offerId")), visible, state.toString)
    assertEquals(offers.flatMap(_.text("source")), Vector("player_code", "customerio_webhook"))
    assert(!state.toString.contains(hidden), state.toString)
  }

  Given("the brand's offers") { () =>
    visible = Vector.fill(3)(offer(named(noDeposit)*))
    visible.foreach(target)
  }

  When("an operator reorders them") { () =>
    visible = visible.reverse
    last = kit.json("PUT", kit.offersPath("/order"), json("orderedOfferIds" -> Json.arr(visible*)), operator)
    assertEquals(last.status, 200, last.text)
  }

  Then("players see them in that order") { () =>
    assertEquals(elements(readState().get("visibleOffers")).flatMap(_.text("offerId")), visible)
  }

  // ── the player's reward state (US4's lifecycle, read through the wallet) ─────────────────

  private var closedReward = ""

  Given("a player with an active reward") { () =>
    val first = offer(named(noDeposit)*)
    selected(first)
    closedReward = eventually("the first active")(Some(rewardOf(first)).filter(_.status == RewardStatus.Active)).rewardId
    val cancelled = kit.json("POST", kit.rewardsPath(s"/$player/cancel"), json(), kit.asPlayer(player))
    assertEquals(cancelled.status, 200, cancelled.text)
    eventually("the first cancelled")(Some(reward(closedReward)).filter(_.status == RewardStatus.Cancelled))
    offerId = offer(named(noDeposit)*)
    selected(offerId)
    val id = until(RewardStatus.Active).rewardId
    kit.wallet.routeAnswer("restricted-funds", Answer.ok(Json.obj("restrictedBalance" -> 800L, "records" -> Json.arr(
      Json.obj("recordId" -> id, "grantRef" -> id, "bonusRef" -> offerId, "status" -> "active", "priority" -> 1L, "balance" -> 800L,
        "wageringRequirement" -> 10000L, "releaseProgress" -> 2500L, "maxConvertible" -> 1000L)))))
  }

  When("they read their reward state") { () => state = readState() }

  Then("the active reward's balance, wagering requirement and release progress are shown as the wallet reports them") { () =>
    assertEquals((state.text("state"), state.long("bonusBalance"), state.long("wageringRequirement"), state.long("wageredAmount")),
      (Some("active"), Some(800L), Some(10000L), Some(2500L)), state.toString)
    assertEquals(elements(state.get("activeBonuses")).flatMap(_.text("grantRef")), Vector(until(RewardStatus.Active).rewardId))
  }

  Then("their history of rewards is shown") { () =>
    val r = eventually("the history")(Some(kit.request("GET", kit.rewardsPath(s"/$player/history"), None, kit.asPlayer(player)))
      .filter(r => r.status == 200 && r.json.size == 1))
    val h = r.json.get(0)
    assertEquals((h.text("grantRef"), h.text("status"), h.long("faceValue")), (Some(closedReward), Some("CANCELLED"), Some(1000L)))
    kit.wallet.clearRoute("restricted-funds")
  }

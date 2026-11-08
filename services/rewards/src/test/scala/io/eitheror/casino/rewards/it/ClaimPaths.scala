package io.eitheror.casino.rewards.it

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.contract.facts.*
import io.eitheror.casino.contract.money.{Currency, MinorUnits, SignedMinorUnits}
import io.eitheror.casino.rewards.Json
import io.eitheror.casino.rewards.features.Scene
import io.eitheror.casino.rewards.offers.{Offer, OfferBook}
import io.eitheror.casino.rewards.player.{PlayerRewards, PlayerRewardsState}
import io.eitheror.casino.rewards.reads.RewardsView
import io.eitheror.casino.rewards.reward.{Reward, RewardState, RewardStatus}

import java.time.{Duration as JDuration, Instant}
import java.util.UUID

/**
 * What a claim came to on one path, in the path's own shape (contracts/routes.md): `None` when it proceeded, the
 * reason or code when it was refused, and the reply when there was one.
 */
final case class Claimed(path: String, refusal: Option[String], reply: Option[Reply] = None)

/**
 * The paths to a reward, over the whole service: the player's selection (`POST /rewards/{p}/select`), a code
 * (`POST /rewards/{p}/codes`), a confirming deposit (the wallet's `deposit_confirmed`), the campaign's award
 * (`POST /reward-campaigns/{p}/award`) and the operator's (`POST /reward-grants/{p}`). An offer is made visible as a
 * campaign does, through the target route (`Scene.target`). The player's own facts arrive on `casino.players`, as
 * `players` publishes them.
 */
final class ClaimPaths(kit: Kit):
  private val Eur = Currency.unsafe("EUR")

  def unique(prefix: String): String = s"$prefix-${UUID.randomUUID().toString.take(8)}"

  // ── offers ──────────────────────────────────────────────────────────────────────────────────

  /** An offer created as an operator does, its id. */
  def offer(fields: (String, Any)*): String =
    val r = kit.json("POST", kit.offersPath(""), Json.obj(fields*), kit.asOperator())
    if r.status != 201 then throw AssertionError(s"the offer was not created (${r.status}): ${r.text}")
    r.str("id")

  /** A free bonus of 10.00 at ×10 with no deposit, `fields` overriding. */
  def noDeposit(fields: (String, Any)*): String =
    offer((Vector[(String, Any)]("name" -> unique("Free"), "type" -> "NO_DEPOSIT", "noDepositRequired" -> true,
      "maxBonusValue" -> 1000L, "wageringMultiplier" -> 10L, "currency" -> "EUR") ++ fields)*)

  /** A 100% match up to 500.00 at ×30, `fields` overriding. */
  def depositMatch(fields: (String, Any)*): String =
    offer((Vector[(String, Any)]("name" -> unique("Match"), "type" -> "DEPOSIT_MATCH", "bonusPercentage" -> 10000L,
      "maxBonusValue" -> 50000L, "wageringMultiplier" -> 30L, "currency" -> "EUR") ++ fields)*)

  def codes(cs: String*): (String, Any) = "redemptionCodes" -> Json.arr(cs.map(c => Json.obj("code" -> c, "isAffiliate" -> false))*)

  def book = kit.components.forEventSourcedEntity(EntityId(kit.slug)).call(OfferBook.get).invoke()
  def offerOf(id: String): Offer = book.offer(id).getOrElse(throw AssertionError(s"no offer $id"))

  /** Every active offer carrying `code`, in any case, deactivated: codes are the brand's, and outlive a scenario. */
  def freeCode(code: String): Unit =
    val n = code.trim.toLowerCase
    book.offers.values.filter(o => o.active && o.redemptionCodes.exists(_.code.trim.toLowerCase == n)).foreach { o =>
      kit.request("DELETE", kit.offersPath(s"/${o.id}"), None, kit.asOperator()): Unit
    }

  // ── the player's facts on casino.players ────────────────────────────────────────────────────

  def newPlayer(prefix: String = "cp-p"): String = unique(prefix)

  def record(player: String): PlayerRewardsState =
    kit.components.forEventSourcedEntity(EntityId(player)).call(PlayerRewards.get).invoke()

  def awaitRecord(player: String, what: String)(p: PlayerRewardsState => Boolean): PlayerRewardsState =
    kit.eventually(s"$player: $what")(Some(record(player)).filter(p))

  def players[F <: CasinoPlayersFact](player: String, fact: F): Unit = kit.publish(CasinoPlayers, player, fact)

  def registered(player: String, at: Instant): Unit =
    players(player, PlayerRegistered(player, at, None, None, None, Some("GB"), Some("EUR"), None, at))
    awaitRecord(player, "registered")(_.registeredAt.contains(at)): Unit

  def checkStarted(player: String, registeredAt: Instant): Unit =
    players(player, PlayerDuplicateCheckStarted(player, registeredAt, kit.now()))
    awaitRecord(player, "check in progress")(_.checkInProgress.isDefined): Unit

  def checkEnded(player: String, outcome: String = "clear"): Unit =
    players(player, PlayerDuplicateCheckEnded(player, outcome, kit.now()))
    awaitRecord(player, "check ended")(_.checkInProgress.isEmpty): Unit

  /** A player whose registration and duplicate check have run, clear: the switch is read on the check's facts. */
  def checked(player: String): Unit =
    checkStarted(player, kit.now())
    checkEnded(player)

  def flagged(player: String): Unit =
    players(player, PlayerFraudFlagged(player, unique("case"), "multi_account", kit.now()))
    awaitRecord(player, "flagged")(_.suspectedDuplicate): Unit

  def blocked(player: String): Unit =
    players(player, PlayerNoDepositBlocked(player, kit.now()))
    awaitRecord(player, "blocked")(_.noDepositBlocked): Unit

  // ── the player's rewards ────────────────────────────────────────────────────────────────────

  def reward(id: String): RewardState = kit.components.forEventSourcedEntity(EntityId(id)).call(Reward.get).invoke()

  /** The player's newest reward of the offer, once the view has it. */
  def rewardOf(player: String, offerId: String): RewardState =
    val id = kit.eventually(s"a reward of $offerId")(RewardsView.byPlayer(kit.views.forView(RewardsView), player)
      .find(_.offerId == offerId).map(_.rewardId))
    reward(id)

  def until(id: String, what: String)(p: RewardState => Boolean): RewardState = kit.eventually(s"reward $id $what")(Some(reward(id)).filter(p))

  /** The offer made visible to the player, as a campaign does: the reward's id. */
  def target(player: String, offerId: String): String = Scene.target(kit, player, offerId)

  def select(player: String, offerId: String): Reply =
    kit.json("POST", kit.rewardsPath(s"/$player/select"), Json.obj("offerId" -> offerId), kit.asPlayer(player))

  def enterCode(player: String, code: String, preview: Boolean = false): Reply =
    kit.json("POST", kit.rewardsPath(s"/$player/codes${if preview then "/preview" else ""}"), Json.obj("code" -> code), kit.asPlayer(player))

  def state(player: String): JsonNode = kit.request("GET", kit.rewardsPath(s"/$player/state"), None, kit.asPlayer(player)).json

  private var ordinals = Map.empty[String, Int]

  /** The wallet's `deposit_confirmed` for the player, the next ordinal unless one is given. */
  def deposit(player: String, amount: Long, ordinal: Option[Int] = None): DepositConfirmed =
    val n = ordinal.getOrElse(ordinals.getOrElse(player, 0) + 1)
    ordinals = ordinals.updated(player, n)
    val fact = DepositConfirmed(player, Eur, unique("txn"), MinorUnits.unsafe(amount), unique("pay"), n, SignedMinorUnits(amount),
      MinorUnits.unsafe(0L), kit.now())
    kit.publish(MoneyTransactions, player, fact)
    fact

  // ── the paths, each answered in its own shape ───────────────────────────────────────────────

  /** The player's selection of an offer made visible to them: `{error: <reason>}` at 409, else it proceeds. */
  def viaSelect(player: String, offerId: String, visible: Boolean = false): Claimed =
    if !visible then target(player, offerId): Unit
    val r = select(player, offerId)
    Claimed("select", Option.when(r.status != 200)(r.str("error")), Some(r))

  /** A code entered: `{status: "ineligible", reason}` at 409, `invalid_code` at 422, else it proceeds. */
  def viaCode(player: String, code: String): Claimed =
    val r = enterCode(player, code)
    val refusal = r.status match
      case 200 => None
      case 409 if r.str("status") == "ineligible" => Some(r.str("reason"))
      case _   => Some(r.str("error"))
    Claimed("code", refusal, Some(r))

  /**
   * A deposit offer selected, then a confirming deposit: the reward activates (it proceeded), records the decline and
   * stays pending (the reason), or expires (`CLAIM_WINDOW_EXPIRED`, as a deposit past the window is answered).
   */
  def viaDeposit(player: String, offerId: String, amount: Long = 3000L, ordinal: Option[Int] = None): Claimed =
    val id = target(player, offerId)
    val s  = select(player, offerId)
    if s.status != 200 then Claimed("deposit", Some(s.str("error")), Some(s))
    else
      deposit(player, amount, ordinal): Unit
      val r = until(id, "answered")(r => r.status != RewardStatus.Pending || r.lastDecline.isDefined)
      r.status match
        case RewardStatus.Active  => Claimed("deposit", None)
        case RewardStatus.Expired => Claimed("deposit", Some("CLAIM_WINDOW_EXPIRED"))
        case _                    => Claimed("deposit", r.lastDecline)

  /** The campaign's award (FR-022): `{error: <reason>}` at 422, or 503 for a check in progress; else it proceeds. */
  def viaCampaign(player: String, offerId: String, fields: (String, Any)*): Claimed =
    val r = kit.json("POST", kit.campaignsPath(s"/$player/award"), Json.obj((("offerId" -> offerId) +: fields)*),
      kit.asService("engagement"))
    Claimed("campaign", Option.when(r.status != 200)(r.str("error")), Some(r))

  /** The operator's award of the offer (FR-017): `{status: "ineligible", reason}` at 409; else it proceeds. */
  def viaOperator(player: String, offerId: String, fields: (String, Any)*): Claimed =
    val r = kit.json("POST", kit.grantsPath(s"/$player"), Json.obj((("offerId" -> offerId) +: fields)*), kit.asOperator())
    val refusal = r.status match
      case 200 => None
      case 409 if r.str("status") == "ineligible" => Some(r.str("reason"))
      case _   => Some(r.str("error"))
    Claimed("operator", refusal, Some(r))

  /**
   * The reward activated, once the wallet answered and the player's record knows it (`IndexConsumer`): until then the
   * record holds the claim as open, and a pending one as a pending selection, which a next claim would see.
   */
  def active(player: String, offerId: String): RewardState =
    val r = kit.eventually(s"$offerId active")(Some(rewardOf(player, offerId)).filter(_.status == RewardStatus.Active))
    awaitRecord(player, s"${r.rewardId} answered")(_.rewards.get(r.rewardId).exists(h =>
      !h.claiming && h.status != RewardStatus.Available && h.status != RewardStatus.Pending)): Unit
    r

  /** A no-deposit offer the player holds active, its id. */
  def holdingActive(player: String, fields: (String, Any)*): String =
    val id = noDeposit(fields*)
    val c  = viaSelect(player, id)
    if c.refusal.isDefined then throw AssertionError(s"$id not claimed: $c")
    active(player, id): Unit
    id

  def ago(d: JDuration): Instant = kit.now().minus(d)

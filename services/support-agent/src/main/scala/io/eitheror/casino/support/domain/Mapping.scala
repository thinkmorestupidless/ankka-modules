package io.eitheror.casino.support.domain

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.eitheror.casino.support.Json
import io.eitheror.casino.support.Json.*

/**
 * Each neighbour's answer in the names the original gave the model (contracts/neighbours.md *Reads*). Only the
 * fields named there are passed: a transaction's `reason` never, the player id never, and a personal field only
 * when the brand names it (FR-018).
 */
object Mapping:

  private def str(n: JsonNode, field: String): Any = n.text(field).getOrElse(null)

  private def num(n: JsonNode, field: String): Any = Option(n.get(field)).filterNot(_.isNull) match
    case Some(v) if v.isNumber => v.asLong
    case Some(v)               => v.asText.trim.toLongOption.getOrElse(null)
    case None                  => null

  /** players' status, its currency (P2) and the profile's named fields (P3). */
  def playerState(status: JsonNode, profile: Option[JsonNode]): ObjectNode =
    val o = Json.obj("status" -> str(status, "status"), "verification" -> str(status, "verification"),
      "displayName" -> str(status, "displayName"), "currency" -> str(status, "currency"))
    profile.foreach(_.properties.forEach(e => if e.getValue.isValueNode then o.put(e.getKey, e.getValue.asText): Unit))
    o

  /** The wallet's balance in minor units; a wallet that does not exist yet (404) is zeros, as the original. */
  def walletBalance(answer: Option[JsonNode], currency: String): ObjectNode = answer match
    case Some(b) => Json.obj("real_balance" -> num(b, "realBalance"), "restricted_balance" -> num(b, "restrictedBalance"),
        "currency" -> b.text("currency").getOrElse(currency))
    case None => Json.obj("real_balance" -> 0L, "restricted_balance" -> 0L, "currency" -> currency)

  /** rewards' grants that are active. */
  def activeBonuses(grants: JsonNode): Vector[ObjectNode] =
    grants.objects("items").filter(_.text("status").exists(_.equalsIgnoreCase("active"))).map { g =>
      Json.obj("bonus_id" -> str(g, "rewardId"), "name" -> str(g, "offerName"), "wagering_requirement" -> num(g, "wageringRequirement"),
        "wagering_progress" -> num(g, "wageredAmount"), "expires_at" -> str(g, "expiresAt"), "status" -> str(g, "status"))
    }

  def transactions(page: JsonNode): Vector[ObjectNode] =
    page.objects("transactions").map { t =>
      Json.obj("transaction_id" -> str(t, "transactionId"), "type" -> str(t, "type"), "amount" -> num(t, "amount"),
        "currency" -> str(t, "currency"), "status" -> str(t, "status"), "created_at" -> str(t, "createdAt"),
        "reference" -> str(t, "paymentReference"))
    }

  /** players' game sessions; `game_id` where the original had `game_name`, which players does not hold. */
  def sessions(answer: JsonNode): Vector[ObjectNode] =
    answer.objects("sessions").map { s =>
      Json.obj("session_id" -> str(s, "sessionId"), "game_id" -> str(s, "gameId"), "status" -> str(s, "status"),
        "started_at" -> str(s, "launchedAt"), "closed_at" -> str(s, "endedAt"))
    }

  private val Periods = Map("DEPOSIT_DAILY" -> "daily", "DEPOSIT_WEEKLY" -> "weekly", "DEPOSIT_MONTHLY" -> "monthly")

  /** Spec 010's rule on RG's status (R1): a first or lower deposit limit of the period applies at once; a higher one 24 hours later. */
  def depositAppliesAtOnce(status: JsonNode, period: String, amount: Long): Boolean =
    status.objects("limits").find(_.text("limitType").flatMap(Periods.get).contains(period)).flatMap(_.long("value")).forall(amount <= _)

  /** RG's status (R1) in the original's fields: the deposit limit, its pending change, the break, the exclusion. */
  def rgStatus(status: JsonNode): ObjectNode =
    def deposit(n: JsonNode) = n.text("limitType").exists(Periods.contains)
    val limit   = status.objects("limits").find(deposit)
    val pending = status.objects("pendingChanges").find(deposit)
    val restrictions = status.strings("activeRestrictions")
    Json.obj(
      "deposit_limit_amount"            -> limit.map(num(_, "value")).orNull,
      "deposit_limit_period"            -> limit.flatMap(_.text("limitType")).flatMap(Periods.get).orNull,
      "deposit_limit_pending"           -> pending.isDefined,
      "deposit_limit_pending_amount"    -> pending.map(num(_, "pendingValue")).orNull,
      "deposit_limit_pending_effective" -> pending.map(str(_, "appliesAt")).orNull,
      "cool_off_until"                  -> str(status, "coolingOffUntil"),
      "self_excluded"                   -> restrictions.contains("SELF_EXCLUDED")
    )

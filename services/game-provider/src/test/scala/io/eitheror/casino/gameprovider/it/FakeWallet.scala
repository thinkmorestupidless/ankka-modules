package io.eitheror.casino.gameprovider.it

import io.eitheror.casino.gameprovider.Json
import io.eitheror.casino.gameprovider.Json.*

import scala.collection.mutable

/**
 * The wallet of spec 002 as far as this service uses it (contracts/neighbours.md): six routes over a
 * ledger of real and restricted balance and open bets, keyed and idempotent as 002 FR-002 is. It counts
 * the calls it receives, can refuse a key or go down on purpose, and lists open bets so a test can
 * prove none leaked (SC-003).
 */
final class FakeWallet extends FakeServer:

  final case class Bet(key: String, roundId: String, amount: Long, gameId: String, gameCategory: Option[String], provider: Option[String])
  final class Account(var real: Long, var restricted: Long):
    val open = mutable.LinkedHashMap.empty[String, Bet] // by the wallet's round id

  private val accounts = mutable.Map.empty[(String, String), Account]
  private val answered = mutable.Map.empty[(String, String, String), Answer] // (wallet, route, key)
  private val refusals = mutable.Map.empty[String, (Int, String)]            // key -> (status, code)
  private var nextId   = 0
  @volatile var down   = false
  /** Answers the next n money commands with a 503, as a wallet mid-restart would. */
  @volatile var failNext = 0

  def set(player: String, currency: String, real: Long, restricted: Long = 0): Unit = synchronized {
    val a = account(player, currency); a.real = real; a.restricted = restricted
  }

  def balance(player: String, currency: String): (Long, Long) = synchronized {
    val a = account(player, currency); (a.real, a.restricted)
  }

  def openBets(player: String, currency: String): Vector[Bet] = synchronized(account(player, currency).open.values.toVector)

  def allOpenBets: Vector[Bet] = synchronized(accounts.values.flatMap(_.open.values).toVector)

  /** Closes a bet in the wallet without this service, as a settlement elsewhere would: its round is then unknown. */
  def closeBet(player: String, currency: String, roundId: String): Unit = synchronized(account(player, currency).open.remove(roundId): Unit)

  /** Refuses the money command under `key` with 002's code, every time it is sent. */
  def refuse(key: String, code: String, status: Int = 409): Unit = synchronized(refusals.update(key, status -> code))

  /** The money commands received on one route (`bets`, `wins`, `losses`, `rollbacks`, `provider-credits`). */
  def calls(route: String): Vector[Received] = received.filter(r => r.method == "POST" && r.path.endsWith(s"/$route"))

  def moneyCalls: Vector[Received] = received.filter(_.method == "POST")

  def reset(): Unit = synchronized {
    accounts.clear(); answered.clear(); refusals.clear(); down = false; failNext = 0; clearReceived()
  }

  private def account(player: String, currency: String) = accounts.getOrElseUpdate((player, currency), Account(0, 0))

  private val Base = """/v1/casino/brands/([^/]+)/wallets/([^/]+)/([^/]+)/([a-z-]+)""".r

  protected def route(r: Received): Answer = synchronized {
    r.path match
      case Base(_, player, currency, "balance") if r.method == "GET" =>
        if down then Answer(503, """{"error":"UNAVAILABLE"}""") else balanceAnswer(player, currency)
      case Base(_, player, currency, command) if r.method == "POST" =>
        if down then Answer(503, """{"error":"UNAVAILABLE"}""")
        else if failNext > 0 then
          failNext -= 1
          Answer(503, """{"error":"UNAVAILABLE"}""")
        else
          val body = r.json.get
          val key  = body.text("key").get
          answered.get((s"$player/$currency", command, key)) match
            case Some(first) => first
            case None =>
              val a = refusals.get(key) match
                case Some((status, code)) => Answer.json(status, Json.obj("error" -> code, "message" -> s"refused $code"))
                case None                 => move(player, currency, command, body)
              if a.status / 100 == 2 || a.status == 409 && !a.body.contains("PLAYER_LOCKED") then
                answered.update((s"$player/$currency", command, key), a)
              a
      case _ => Answer(404, """{"error":"NO_ROUTE"}""")
  }

  private def balanceAnswer(player: String, currency: String): Answer =
    val a = account(player, currency)
    Answer.ok(Json.obj("realBalance" -> a.real, "restrictedBalance" -> a.restricted, "currency" -> currency))

  private def moved(player: String, currency: String): Answer =
    nextId += 1
    val a = account(player, currency)
    Answer.ok(Json.obj("txnId" -> s"wtx-$nextId", "realBalance" -> a.real, "bonusBalance" -> a.restricted, "currency" -> currency))

  private def roundNotFound = Answer.json(404, Json.obj("error" -> "ROUND_NOT_FOUND", "message" -> "no open bet for the round"))

  private def move(player: String, currency: String, command: String, body: com.fasterxml.jackson.databind.JsonNode): Answer =
    val a       = account(player, currency)
    val roundId = body.text("roundId").getOrElse("")
    val amount  = body.long("amount").getOrElse(0L)
    command match
      case "bets" =>
        val allowNegative = body.bool("allowNegative").getOrElse(false)
        if amount > a.real + a.restricted && !allowNegative then
          Answer.json(409, Json.obj("error" -> "INSUFFICIENT_FUNDS", "message" -> "Insufficient funds in real and bonus wallets"))
        else
          val fromRestricted = if allowNegative then 0L else math.max(0L, amount - math.max(a.real, 0L))
          a.real -= amount - fromRestricted
          a.restricted -= fromRestricted
          a.open.update(roundId, Bet(body.text("key").get, roundId, amount, body.textOr("gameId", ""), body.text("category").filter(_.nonEmpty), body.text("provider")))
          moved(player, currency)
      case "wins" =>
        a.open.remove(roundId) match
          case None    => roundNotFound
          case Some(_) => a.real += amount; moved(player, currency)
      case "losses" =>
        a.open.remove(roundId) match
          case None    => roundNotFound
          case Some(_) => moved(player, currency)
      case "rollbacks" =>
        a.open.remove(roundId) match
          case None      => roundNotFound
          case Some(bet) => a.real += bet.amount; moved(player, currency)
      case "provider-credits" =>
        a.real += amount
        moved(player, currency)
      case other => Answer(404, s"""{"error":"NO_COMMAND","message":"$other"}""")

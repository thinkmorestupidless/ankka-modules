package io.eitheror.casino.gameprovider.clients

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.sdk.ServiceClients
import io.eitheror.casino.gameprovider.Json
import io.eitheror.casino.gameprovider.Json.*
import io.eitheror.casino.gameprovider.provider.st8.Status

/** A wallet's real and restricted balance in minor units; the provider is told their sum (FR-005). */
final case class Balances(real: Long, restricted: Long, currency: String):
  def total: Long = real + restricted

/** What the wallet did with one money command (contracts/neighbours.md, spec 002 routes). */
enum WalletAnswer:
  /**
   * Moved, with the wallet's transaction id and the balances after, when the answer carried them, and the
   * real-money part of the movement (`real`): the deposit and converted parts the wallet attributes it to,
   * whose sum is its answer's `realLeg` for a bet, a win and a rollback, the amount for a provider credit
   * that landed on real balance and zero for one that landed on a record or a loss (spec 016 G7). Absent
   * when the answer did not say.
   */
  case Moved(transactionId: String, balances: Option[Balances], real: Option[Long] = None)
  /** Refused with one of spec 002's codes, classified for the provider (research R7). */
  case Refused(code: String, refusal: WalletRefusal, message: String)
  /** No answer, a 5xx, or a refusal that says nothing about the money: the provider will retry. */
  case Unacknowledged(error: String)

/** The provider's reading of a wallet refusal (research R7, FR-012). */
enum WalletRefusal:
  case InsufficientFunds
  case Locked
  case SpendingLimit
  case RoundNotFound
  case Other

  /** The status word a debit or buyin is answered with; none means the call is unacknowledged. */
  def word: Option[String] = this match
    case InsufficientFunds => Some(Status.NotEnoughMoney)
    case Locked            => Some(Status.PlayerLocked)
    case SpendingLimit     => Some(Status.SpendingLimit)
    case RoundNotFound     => None
    case Other             => None

object WalletRefusal:
  /** Spec 002's codes for a bet (FR-006, FR-007, FR-007a) and the responsible-gambling family (FR-022). */
  def of(code: String): WalletRefusal = code match
    case "INSUFFICIENT_FUNDS"                                => InsufficientFunds
    case "PLAYER_LOCKED" | "EXCLUDED" | "COOLING_OFF"         => Locked
    case "SCOPE_BLOCKED" | "MAX_STAKE_EXCEEDED" | "OVER_LIMIT" => SpendingLimit
    case "ROUND_NOT_FOUND"                                   => RoundNotFound
    case _                                                   => Other

/**
 * The round a money move belongs to (spec 016 G4): `gameRoundId` is the round's id as `casino.rounds` carries
 * it (`<provider>/<provider round id>`, `Round.entityId`), `sessionId` the game session's id, empty when no
 * ticket named the round. The wallet puts both on the fact it publishes for the move.
 */
final case class RoundRef(gameRoundId: String, sessionId: String):
  def fields: Seq[(String, Any)] = Seq("gameRoundId" -> gameRoundId, "sessionId" -> Option(sessionId).filter(_.nonEmpty))

/**
 * The wallet's commands as this service uses them (contracts/neighbours.md, spec 002 as built), every
 * one keyed by the key this service passes, which is the provider's own id or one derived from it
 * (FR-011), and sent from the bet workflow (research R18). Bodies are camelCase as the wallet reads
 * them; the bet carries the provider and the game's category, as `gameType` the way the original sent
 * it and as `category` (FR-012).
 */
final class WalletClient(services: ServiceClients, brand: String):
  private val http = Http(services, "wallet")

  private def base(player: String, currency: String) =
    s"/v1/casino/brands/${Http.segment(brand)}/wallets/${Http.segment(player)}/${Http.segment(currency)}"

  /** `sessionId` is the game session's, as the bet always sent it; `gameRoundId` is the round's (spec 016 G4). */
  def betBody(key: String, roundId: String, gameId: String, sessionId: String, amount: Long, allowNegative: Boolean,
      gameCategory: Option[String], provider: String, gameRoundId: Option[String] = None): JsonNode =
    Json.obj("key" -> key, "roundId" -> roundId, "gameId" -> gameId, "sessionId" -> sessionId, "amount" -> amount,
      "allowNegative" -> allowNegative, "provider" -> provider, "gameType" -> gameCategory.getOrElse(""),
      "category" -> gameCategory.getOrElse(""), "gameRoundId" -> gameRoundId)

  def placeBet(player: String, currency: String, key: String, roundId: String, gameId: String, sessionId: String,
      amount: Long, allowNegative: Boolean, gameCategory: Option[String], provider: String, gameRoundId: Option[String] = None): WalletAnswer =
    move(http.post(s"${base(player, currency)}/bets",
      betBody(key, roundId, gameId, sessionId, amount, allowNegative, gameCategory, provider, gameRoundId)), currency, _.long("realLeg"))

  def creditWin(player: String, currency: String, key: String, roundId: String, amount: Long, round: Option[RoundRef] = None): WalletAnswer =
    move(http.post(s"${base(player, currency)}/wins",
      Json.obj((Seq("key" -> key, "roundId" -> roundId, "amount" -> amount) ++ round.toSeq.flatMap(_.fields))*)), currency, _.long("realLeg"))

  def settleLoss(player: String, currency: String, key: String, roundId: String, round: Option[RoundRef] = None): WalletAnswer =
    move(http.post(s"${base(player, currency)}/losses",
      Json.obj((Seq("key" -> key, "roundId" -> roundId) ++ round.toSeq.flatMap(_.fields))*)), currency, _ => Some(0L))

  def rollbackBet(player: String, currency: String, key: String, roundId: String, round: Option[RoundRef] = None): WalletAnswer =
    move(http.post(s"${base(player, currency)}/rollbacks",
      Json.obj((Seq("key" -> key, "roundId" -> roundId) ++ round.toSeq.flatMap(_.fields))*)), currency, _.long("realLeg"))

  /** A provider credit's real part is its amount when it landed on real balance, else none of it (spec 025 FR-010). */
  def providerCredit(player: String, currency: String, key: String, amount: Long, providerRef: String, reason: String): WalletAnswer =
    move(http.post(s"${base(player, currency)}/provider-credits",
      Json.obj("key" -> key, "amount" -> amount, "providerRef" -> providerRef, "reason" -> reason, "actor" -> "game-provider")), currency,
      _.text("landedOn").map(l => if l == "real" then amount else 0L))

  /** The wallet's balances, or why they could not be read. */
  def balance(player: String, currency: String): Either[String, Balances] =
    http.get(s"${base(player, currency)}/balance") match
      case Reply.Answered(s, Some(body), _) if s / 100 == 2 => balancesOf(body, currency).toRight("the balance answer carried no balances")
      case Reply.Answered(s, _, text)                      => Left(s"the wallet answered $s: ${text.take(200)}")
      case Reply.Unreachable(reason)                       => Left(reason)

  /** A movement answers `realBalance` and `bonusBalance`, the balance read `realBalance` and `restrictedBalance`. */
  private def balancesOf(body: JsonNode, currency: String): Option[Balances] =
    for
      real <- body.long("realBalance")
    yield Balances(real, body.long("restrictedBalance").orElse(body.long("bonusBalance")).getOrElse(0L),
      body.text("currency").getOrElse(currency))

  private def move(reply: Reply, currency: String, real: JsonNode => Option[Long]): WalletAnswer = reply match
    case Reply.Unreachable(reason) => WalletAnswer.Unacknowledged(reason)
    case Reply.Answered(s, body, text) if s / 100 == 2 =>
      body.flatMap(_.text("txnId")) match
        case Some(id) => WalletAnswer.Moved(id, body.flatMap(balancesOf(_, currency)), body.flatMap(real))
        case None     => WalletAnswer.Unacknowledged(s"the wallet answered $s with no transaction id: ${text.take(200)}")
    case Reply.Answered(s, body, text) if s / 100 == 5 =>
      WalletAnswer.Unacknowledged(s"the wallet answered $s: ${body.flatMap(_.text("error")).getOrElse(text.take(200))}")
    case Reply.Answered(s, body, text) =>
      val code = body.flatMap(_.text("error")).getOrElse(s"HTTP_$s")
      WalletRefusal.of(code) match
        case WalletRefusal.Other => WalletAnswer.Unacknowledged(s"the wallet refused $code: ${body.flatMap(_.text("message")).getOrElse(text.take(200))}")
        case refusal             => WalletAnswer.Refused(code, refusal, body.flatMap(_.text("message")).getOrElse(""))

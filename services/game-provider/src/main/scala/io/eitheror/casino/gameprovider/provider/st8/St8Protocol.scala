package io.eitheror.casino.gameprovider.provider.st8

import com.fasterxml.jackson.databind.node.ObjectNode
import io.eitheror.casino.gameprovider.Json
import io.eitheror.casino.gameprovider.Json.*

/** The provider's callbacks, by the name in their path (contracts/callbacks.md). */
enum Callback(val wire: String):
  case Check         extends Callback("check")
  case Balance       extends Callback("balance")
  case PlayerProfile extends Callback("player_profile")
  case Debit         extends Callback("debit")
  case Credit        extends Callback("credit")
  case Cancel        extends Callback("cancel")
  case Buyin         extends Callback("buyin")
  case Payout        extends Callback("payout")

object Callback:
  def from(wire: String): Option[Callback] = values.find(_.wire == wire)

  /** Whether the callback moves money, so is keyed by the provider's id (FR-011). */
  def movesMoney(c: Callback): Boolean = c match
    case Debit | Credit | Cancel | Buyin | Payout => true
    case _                                        => false

/** The words a callback is answered with, always under HTTP 200 unless the body was unreadable (FR-004). */
object Status:
  val Ok                  = "ok"
  val Error               = "error"
  val AuthFailed          = "auth_failed"
  val SessionExpired      = "session_expired"
  val PlayerLocked        = "player_locked"
  val PlayerNotFound      = "player_not_found"
  val NotEnoughMoney      = "not_enough_money"
  val SpendingLimit       = "spending_limit"
  val TransactionNotFound = "transaction_not_found"

  val all: Set[String] =
    Set(Ok, Error, AuthFailed, SessionExpired, PlayerLocked, PlayerNotFound, NotEnoughMoney, SpendingLimit, TransactionNotFound)

/** The kinds of transaction a debit, credit, buyin or payout names in `provider_kind`. */
object Kind:
  val Debit           = "debit"
  val FreeDebit       = "free_debit"
  val CorrectionDebit = "correction_debit"
  val Credit          = "credit"
  val FreeCredit      = "free_credit"
  val Buyin           = "buyin"
  val Payout          = "payout"

  def isCredit(kind: String): Boolean = kind == Credit || kind == FreeCredit

/**
 * One callback's body, read as the protocol names its fields (FR-004): strings also accepted as
 * numbers, unknown fields ignored, an empty string absent.
 */
final case class CallbackBody(callback: Callback, node: ObjectNode):
  def token: Option[String]         = node.text("token")
  def player: Option[String]        = node.text("player")
  def transactionId: Option[String] = node.text("transaction_id")
  def cancelId: Option[String]      = node.text("cancel_id")
  def round: Option[String]         = node.text("round").orElse(node.text("round_id"))
  def currency: Option[String]      = node.text("currency")
  def gameCode: Option[String]      = node.text("game_code")

  /** `provider_kind`, defaulting to the callback's own kind. */
  def kind: String = node.text("provider_kind").getOrElse(callback match
    case Callback.Credit => Kind.Credit
    case Callback.Buyin  => Kind.Buyin
    case Callback.Payout => Kind.Payout
    case _               => Kind.Debit)

object St8Protocol:

  /** HTTP 400's word for a body that is not a JSON object: `player_not_found` for the profile, else `session_expired`. */
  def malformedWord(c: Callback): String = if c == Callback.PlayerProfile then Status.PlayerNotFound else Status.SessionExpired

  def status(word: String): ObjectNode = Json.obj("status" -> word)

  /** A money answer: `{status, balance, currency}`, the balance already rendered (contracts/callbacks.md). */
  def money(word: String, balance: Option[String], currency: Option[String]): ObjectNode =
    Json.obj("status" -> word, "balance" -> balance, "currency" -> currency)

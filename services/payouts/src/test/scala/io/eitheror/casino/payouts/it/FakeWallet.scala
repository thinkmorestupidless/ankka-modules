package io.eitheror.casino.payouts.it

import com.fasterxml.jackson.databind.node.ObjectNode
import io.eitheror.casino.payouts.Json
import io.eitheror.casino.payouts.Json.*

import scala.collection.concurrent.TrieMap

/**
 * The brand's wallet's hold routes, as spec 002 built them: the preview answers 200 with its refusal in the
 * body, the hold answers its `MoneyReply` or a 409 refusal, and release, confirm and reverse answer the same
 * shape. Each answer is scriptable: the preview per player, the rest per request id. A request id held
 * once and not yet settled is pending; the hold of another while one is pending is refused, as the wallet's.
 */
final class FakeWallet(brand: String) extends FakeServer():
  private val previews = TrieMap.empty[String, Long => Answer]
  private val holds    = TrieMap.empty[String, Answer]
  private val steps    = TrieMap.empty[(String, String), Answer]
  private val pending  = TrieMap.empty[String, String]
  private val balances = TrieMap.empty[String, Long]
  private val heldBy   = TrieMap.empty[String, Long]
  private val bonuses  = TrieMap.empty[String, String]
  /** When set, every call answers this, as a wallet that is down or slow would. */
  @volatile var down: Option[Answer] = None
  @volatile var delayMillis: Long     = 0L

  def previewFor(player: String, answer: ObjectNode): Unit = previews.update(player, _ => Answer.ok(answer))
  /** The player's preview as a function of the amount asked, as the wallet's own reading of its balances is. */
  def previewBy(player: String)(answer: Long => ObjectNode): Unit = previews.update(player, a => Answer.ok(answer(a)))
  def clearPreview(player: String): Unit                         = previews.remove(player): Unit
  /** The player's active bonus, which the next hold forfeits (spec 002) and so ends. */
  def bonusFor(player: String, bonusId: String): Unit = bonuses.update(player, bonusId)
  def clearBonus(player: String): Unit                = bonuses.remove(player): Unit
  def holdFor(requestId: String, answer: Answer): Unit     = holds.update(requestId, answer)
  def stepFor(requestId: String, step: String, answer: Answer): Unit = steps.update((requestId, step), answer)
  /** The player's real balance: a preview of more is refused `INSUFFICIENT_FUNDS`, a hold takes from it, a release gives it back. */
  def balanceFor(player: String, amount: Long): Unit = balances.update(player, amount)
  def balance(player: String): Option[Long]         = balances.get(player)
  def reset(): Unit =
    previews.clear(); holds.clear(); steps.clear(); pending.clear(); balances.clear(); heldBy.clear(); bonuses.clear(); down = None; delayMillis = 0L
    clearReceived()

  def calls(step: String): Vector[Received] = received.filter(r => r.method == "POST" && r.path.endsWith(s"/holds$step"))

  private val Prefix = s"/v1/casino/brands/$brand/wallets/"

  protected def route(r: Received): Answer =
    if delayMillis > 0 then Thread.sleep(delayMillis)
    down.getOrElse {
      r.path.stripPrefix(Prefix).split('/').toVector match
        case Vector(player, _, "holds", "preview") if r.method == "GET" =>
          val amount = r.query.get("amount").flatMap(_.toLongOption).getOrElse(0L)
          previews.get(player).map(_(amount)).getOrElse {
            if balances.get(player).exists(_ < amount) then Answer.ok(FakeWallet.preview(refusal = Some("INSUFFICIENT_FUNDS")))
            else Answer.ok(FakeWallet.preview())
          }
        case Vector(player, _, "holds") if r.method == "POST" =>
          val body = r.json.get
          val id   = body.textOr("key", "")
          holds.get(id).getOrElse {
            if pending.get(player).exists(_ != id) then FakeWallet.refusal("WITHDRAWAL_REQUEST_ALREADY_PENDING")
            else if balances.get(player).exists(_ < body.long("amount").getOrElse(0L)) then FakeWallet.refusal("INSUFFICIENT_FUNDS")
            else
              if !pending.get(player).contains(id) then
                val amount = body.long("amount").getOrElse(0L)
                balances.updateWith(player)(_.map(_ - amount)): Unit
                heldBy.update(id, amount)
              pending.update(player, id)
              val forfeited = bonuses.remove(player).toVector
              if forfeited.nonEmpty then previews.remove(player): Unit
              Answer.ok(Json.obj("paidAmount" -> body.long("amount").getOrElse(0L), "voidedAmount" -> 0L, "clawbackAmount" -> 0L,
                "forfeited" -> Json.arr(forfeited.map(Json.mapper.getNodeFactory.textNode)*), "txnId" -> s"txn-$id", "realBalance" -> 0L, "currency" -> "EUR"))
          }
        case Vector(player, _, "holds", step) if r.method == "POST" =>
          val id = r.json.get.textOr("requestId", "")
          steps.get((id, step)).getOrElse {
            if pending.get(player).contains(id) then pending.remove(player): Unit
            if step == "release" then heldBy.remove(id).foreach(a => balances.updateWith(player)(_.map(_ + a)): Unit)
            Answer.ok(Json.obj("realBalance" -> 0L, "currency" -> "EUR"))
          }
        case _ => Answer(404, """{"error":"NOT_FOUND"}""")
    }

object FakeWallet:
  def preview(refusal: Option[String] = None, confirmationRequired: Boolean = false, bonusBalance: Long = 0L, clawback: Long = 0L,
      totalForfeit: Long = 0L, mostWithdrawable: Long = 1000000L): ObjectNode =
    Json.obj("refusal" -> refusal, "confirmationRequired" -> confirmationRequired, "bonusBalance" -> bonusBalance,
      "clawback" -> clawback, "totalForfeit" -> totalForfeit, "mostWithdrawable" -> mostWithdrawable, "forfeits" -> Json.arr())

  def refusal(code: String, status: Int = 409): Answer =
    Answer.json(status, Json.obj("error" -> code, "message" -> code, "details" -> Json.obj()))

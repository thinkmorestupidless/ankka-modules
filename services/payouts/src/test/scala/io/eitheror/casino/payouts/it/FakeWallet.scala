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
  private val previews = TrieMap.empty[String, Answer]
  private val holds    = TrieMap.empty[String, Answer]
  private val steps    = TrieMap.empty[(String, String), Answer]
  private val pending  = TrieMap.empty[String, String]
  /** When set, every call answers this, as a wallet that is down or slow would. */
  @volatile var down: Option[Answer] = None
  @volatile var delayMillis: Long     = 0L

  def previewFor(player: String, answer: ObjectNode): Unit = previews.update(player, Answer.ok(answer))
  def holdFor(requestId: String, answer: Answer): Unit     = holds.update(requestId, answer)
  def stepFor(requestId: String, step: String, answer: Answer): Unit = steps.update((requestId, step), answer)
  def reset(): Unit =
    previews.clear(); holds.clear(); steps.clear(); pending.clear(); down = None; delayMillis = 0L; clearReceived()

  def calls(step: String): Vector[Received] = received.filter(r => r.method == "POST" && r.path.endsWith(s"/holds$step"))

  private val Prefix = s"/v1/casino/brands/$brand/wallets/"

  protected def route(r: Received): Answer =
    if delayMillis > 0 then Thread.sleep(delayMillis)
    down.getOrElse {
      r.path.stripPrefix(Prefix).split('/').toVector match
        case Vector(player, _, "holds", "preview") if r.method == "GET" =>
          previews.getOrElse(player, Answer.ok(FakeWallet.preview()))
        case Vector(player, _, "holds") if r.method == "POST" =>
          val body = r.json.get
          val id   = body.textOr("key", "")
          holds.get(id).getOrElse {
            if pending.get(player).exists(_ != id) then FakeWallet.refusal("WITHDRAWAL_REQUEST_ALREADY_PENDING")
            else
              pending.update(player, id)
              Answer.ok(Json.obj("paidAmount" -> body.long("amount").getOrElse(0L), "voidedAmount" -> 0L, "clawbackAmount" -> 0L,
                "forfeited" -> Json.arr(), "txnId" -> s"txn-$id", "realBalance" -> 0L, "currency" -> "EUR"))
          }
        case Vector(player, _, "holds", step) if r.method == "POST" =>
          val id = r.json.get.textOr("requestId", "")
          steps.get((id, step)).getOrElse {
            if pending.get(player).contains(id) then pending.remove(player): Unit
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

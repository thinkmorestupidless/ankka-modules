package io.eitheror.casino.payouts.it

import com.fasterxml.jackson.databind.node.ObjectNode
import io.eitheror.casino.payouts.Json
import io.eitheror.casino.payouts.Json.*

import scala.collection.concurrent.TrieMap

/**
 * `payments`/`psp-gateway`'s three payout routes as spec 005 built them (its recordings and contracts/payouts.md):
 * the brand's withdrawal terminals, the payout, and its status. The terminals are set per currency; the payout's
 * and the status's answers are scriptable per request id, and a repeat of a payout answers its first answer, as
 * the gateway's session does.
 */
final class FakeGateway extends FakeServer():
  private val listings  = TrieMap.empty[String, Vector[ObjectNode]]
  private val initiates = TrieMap.empty[String, Answer]
  private val refusing  = TrieMap.empty[String, String]
  private val statuses  = TrieMap.empty[String, Answer]
  private val answered  = TrieMap.empty[String, Answer]
  @volatile var listingDown: Boolean = false

  def terminals(currency: String, rows: ObjectNode*): Unit = listings.update(currency, rows.toVector)
  def initiateFor(requestId: String, answer: Answer): Unit = initiates.update(requestId, answer)
  /** Every payout to the terminal is rejected at initiation with `reason`, as a provider without the terminal's credentials does. */
  def rejectAt(terminalId: String, reason: String): Unit  = refusing.update(terminalId, reason)
  def statusFor(requestId: String, answer: Answer): Unit   = statuses.update(requestId, answer)
  def reset(): Unit =
    listings.clear(); initiates.clear(); refusing.clear(); statuses.clear(); answered.clear(); listingDown = false; clearReceived()

  def payouts: Vector[Received] = received.filter(r => r.method == "POST" && r.path == "/v1/payments/withdrawals")
  def statusReads(requestId: String): Vector[Received] =
    received.filter(r => r.method == "GET" && r.path == s"/v1/payments/withdrawals/$requestId")

  protected def route(r: Received): Answer = (r.method, r.path) match
    case ("GET", "/v1/payments/withdrawal-terminals") =>
      if listingDown then Answer(503, """{"error":"unavailable"}""")
      else Answer.ok(Json.obj("terminals" -> Json.arr(listings.getOrElse(r.query.getOrElse("currency", ""), Vector.empty)*)))
    case ("POST", "/v1/payments/withdrawals") =>
      val id = r.json.get.textOr("requestId", "")
      val refused = refusing.get(r.json.get.textOr("terminalId", "")).map(reason => FakeGateway.answer(id, "rejected", reason = Some(reason)))
      answered.getOrElseUpdate(id, initiates.get(id).orElse(refused).getOrElse(FakeGateway.answer(id, "initiated", Some(s"ref-$id"))))
    case ("GET", path) if path.startsWith("/v1/payments/withdrawals/") =>
      val id = path.stripPrefix("/v1/payments/withdrawals/")
      statuses.getOrElse(id, FakeGateway.answer(id, "pending", Some(s"ref-$id")))
    case _ => Answer(404, """{"error":"not_found"}""")

object FakeGateway:
  def answer(requestId: String, status: String, reference: Option[String] = None, reason: Option[String] = None): Answer =
    Answer.ok(Json.obj("requestId" -> requestId, "status" -> status, "providerReference" -> reference, "reason" -> reason))

  def terminal(id: String, gateway: String, name: String = "", scheme: String = "EUBANKING", active: Boolean = true,
      destination: Boolean = false, coins: Vector[String] = Vector.empty, hasStatus: Boolean = false,
      reviewWindow: Option[String] = None, withdrawals: Boolean = true): ObjectNode =
    Json.obj("id" -> id, "subBrandId" -> "sb-1", "name" -> (if name.isEmpty then id else name), "scheme" -> scheme,
      "gateway" -> gateway, "active" -> active, "supportsWithdrawal" -> withdrawals, "requiresPayoutDestination" -> destination,
      "coins" -> Json.arr(coins*), "hasStatus" -> hasStatus, "reviewWindow" -> reviewWindow)

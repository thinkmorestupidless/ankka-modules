package io.eitheror.casino.merchant.it

import io.eitheror.casino.merchant.Json
import io.eitheror.casino.merchant.Json.*

import scala.collection.mutable

/**
 * A brand's wallet as far as the shared payments use it (contracts/wallet.md, research R5): the two
 * granted routes. A deposit is keyed by its payment id, so a repeat answers as the first did and the
 * balance moves once, as spec 002's deposit workflow does. A test stops it, delays it, makes it answer
 * `OUTCOME_PENDING`, loses an answer in flight, or refuses a payment finally, and reads the balances,
 * the deposit counts and every outcome reported.
 */
final class FakeWallet(bind: Int = 0) extends FakeServer(bind):

  final case class Reported(brand: String, paymentId: String, outcome: String, playerId: String, paymentProvider: String,
      code: Option[String], reason: Option[String], amount: Option[Long], currency: Option[String])

  private val balances  = mutable.Map.empty[(String, String, String), Long]
  private val counts    = mutable.Map.empty[(String, String, String), Int]
  private val answered  = mutable.Map.empty[String, Answer]
  private val refusals  = mutable.Map.empty[String, Answer]
  private val lose      = mutable.Set.empty[String]
  private val outcomes  = mutable.LinkedHashMap.empty[String, Reported]
  @volatile var down     = false
  @volatile var pending  = 0
  @volatile var delayMs  = 0L
  @volatile var refuseReports = false

  def balance(brand: String, player: String, currency: String): Long = synchronized(balances.getOrElse((brand, player, currency), 0L))
  def depositCount(brand: String, player: String, currency: String): Int = synchronized(counts.getOrElse((brand, player, currency), 0))

  /** Refuses the deposit of this payment finally, every time, with spec 002's code and details. */
  def refuse(paymentId: String, code: String, status: Int, details: (String, Any)*): Unit = synchronized {
    refusals.update(paymentId, Answer.json(status, Json.obj("error" -> code, "message" -> s"refused $code", "details" -> Json.obj(details*))))
  }

  /** Applies the next deposit of this payment and loses the answer in flight: the caller sees a 503. */
  def loseAnswer(paymentId: String): Unit = synchronized(lose += paymentId: Unit)

  def deposits: Vector[Received] = received.filter(r => r.method == "POST" && r.path.endsWith("/deposits"))
  def depositsOf(paymentId: String): Vector[Received] = deposits.filter(_.json.flatMap(_.text("paymentId")).contains(paymentId))
  def reported: Vector[Reported] = synchronized(outcomes.values.toVector)
  def reportsOf(paymentId: String): Vector[Received] = received.filter(_.path.endsWith(s"/payments/$paymentId/outcome"))

  def reset(): Unit = synchronized {
    balances.clear(); counts.clear(); answered.clear(); refusals.clear(); lose.clear(); outcomes.clear()
    down = false; pending = 0; delayMs = 0; refuseReports = false; clearReceived()
  }

  private val Deposits = """/v1/casino/brands/([^/]+)/wallets/([^/]+)/([^/]+)/deposits""".r
  private val Outcome  = """/v1/casino/brands/([^/]+)/payments/([^/]+)/outcome""".r

  protected def route(r: Received): Answer =
    if delayMs > 0 then Thread.sleep(delayMs)
    synchronized {
      if down then Answer(503, """{"error":"UNAVAILABLE"}""")
      else
        r.path match
          case Deposits(brand, player, currency) if r.method == "POST" =>
            val body = r.json.get
            val id   = body.textOr("paymentId", "")
            if pending > 0 then
              pending -= 1
              Answer(503, """{"error":"OUTCOME_PENDING","message":"The outcome is still being decided; retry"}""")
            else
              val a = answered.getOrElseUpdate(id, refusals.getOrElse(id, deposit(brand, player, currency, id, body.long("amount").getOrElse(0L))))
              if lose.remove(id) then Answer(503, """{"error":"UNAVAILABLE","message":"lost in flight"}""") else a
          case Outcome(brand, paymentId) if r.method == "POST" =>
            if refuseReports then Answer(503, """{"error":"UNAVAILABLE"}""")
            else
              val b     = r.json.get
              val key   = s"$paymentId/${b.textOr("outcome", "")}"
              val first = !outcomes.contains(key)
              if first then
                outcomes.update(key, Reported(brand, paymentId, b.textOr("outcome", ""), b.textOr("playerId", ""), b.textOr("paymentProvider", ""),
                  b.text("code"), b.text("reason"), b.long("amount"), b.text("currency")))
              Answer.ok(Json.obj("recorded" -> true, "firstRecorded" -> first))
          case _ => Answer(404, """{"error":"NO_ROUTE"}""")
    }

  private def deposit(brand: String, player: String, currency: String, paymentId: String, amount: Long): Answer =
    val k = (brand, player, currency)
    balances.update(k, balances.getOrElse(k, 0L) + amount)
    counts.update(k, counts.getOrElse(k, 0) + 1)
    Answer.ok(Json.obj("realBalance" -> balances(k), "bonusBalance" -> 0L, "currency" -> currency, "txnId" -> s"txn-$paymentId",
      "depositOrdinal" -> counts(k)))

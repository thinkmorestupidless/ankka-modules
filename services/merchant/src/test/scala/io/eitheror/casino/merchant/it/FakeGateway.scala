package io.eitheror.casino.merchant.it

import io.eitheror.casino.merchant.Json
import io.eitheror.casino.merchant.Json.*

import scala.collection.mutable

/**
 * `psp-gateway` as far as the cashier uses it (contracts/gateway.md, research R3): the hosted-page
 * initiate and the session status read. An initiate repeated under a payment id answers as the first
 * did and asks no provider again (spec 005 FR-008). A test scripts a player-safe refusal, a failure,
 * a crypto destination, or a session's state, and reads back what was asked.
 */
object FakeGateway:
  enum Session:
    case Pending, Resolved, Undelivered, Refused, Unknown, Down

final class FakeGateway(bind: Int = 0) extends FakeServer(bind):
  import FakeGateway.Session

  private val initiated = mutable.Map.empty[String, Answer]
  private val sessions  = mutable.Map.empty[String, Session]
  @volatile var refusePlayer: Option[String] = None
  @volatile var failWith: Option[Int]        = None
  @volatile var crypto: Boolean              = false
  @volatile var down: Boolean                = false

  def session(paymentId: String, s: Session): Unit = synchronized(sessions.update(paymentId, s))

  def initiates: Vector[Received] = received.filter(r => r.method == "POST" && r.path == "/v1/psp/hpp/initiate")
  def sessionReads: Vector[Received] = received.filter(_.path.startsWith("/v1/psp/sessions/"))

  def reset(): Unit = synchronized {
    initiated.clear(); sessions.clear(); refusePlayer = None; failWith = None; crypto = false; down = false; clearReceived()
  }

  protected def route(r: Received): Answer = synchronized {
    if down then Answer(503, """{"error":"UNAVAILABLE"}""")
    else if r.method == "POST" && r.path == "/v1/psp/hpp/initiate" then
      val body = r.json.get
      val id   = body.textOr("paymentId", "")
      initiated.getOrElseUpdate(id, initiate(id, body))
    else if r.method == "GET" && r.path.startsWith("/v1/psp/sessions/") then
      val id = r.path.stripPrefix("/v1/psp/sessions/")
      sessions.getOrElse(id, Session.Pending) match
        case Session.Pending     => Answer.ok(Json.obj("state" -> "pending", "delivered" -> false, "undelivered" -> false))
        case Session.Resolved    => Answer.ok(Json.obj("state" -> "resolved", "outcome" -> "failed", "delivered" -> true, "undelivered" -> false))
        case Session.Undelivered => Answer.ok(Json.obj("state" -> "resolved", "outcome" -> "captured", "delivered" -> false, "undelivered" -> true))
        case Session.Refused     => Answer.ok(Json.obj("state" -> "resolved", "outcome" -> "captured", "delivered" -> false, "undelivered" -> false, "refused" -> true))
        case Session.Unknown     => Answer(404, """{"error":"NOT_FOUND"}""")
        case Session.Down        => Answer(503, """{"error":"UNAVAILABLE"}""")
    else Answer(404, """{"error":"NO_ROUTE"}""")
  }

  private def initiate(id: String, body: com.fasterxml.jackson.databind.JsonNode): Answer =
    refusePlayer match
      case Some(message) => Answer.json(400, Json.obj("error" -> "player_error", "message" -> message))
      case None =>
        failWith match
          case Some(status) => Answer(status, """{"error":"psp_error"}""")
          case None =>
            val o = Json.obj("redirectUrl" -> s"https://gateway.example/pay/$id", "pspTransactionId" -> s"psp-$id")
            if crypto then
              Json.set(o, "paymentAddress", s"bc1-$id")
              Json.set(o, "cryptoAmount", "0.0021")
              Json.set(o, "cryptoCurrency", body.textOr("method", "BTC"))
              Json.set(o, "validUntil", "2026-10-12T11:00:00Z")
            Answer.ok(o)

package io.eitheror.casino.support.it

import io.eitheror.casino.support.Json
import io.eitheror.casino.support.Json.*

import scala.collection.concurrent.TrieMap

/**
 * One neighbour the tools call (contracts/neighbours.md), at its paths: an answer scripted for a method and a path
 * (the path after the brand, as `/players/p-1/status`), or the neighbour's default, or 404. Everything received is
 * kept, with the key a write carries. A local service call carries no identity (deployed, it is the workload's
 * certificate), so the fake checks the other half of FR-005: no player token is forwarded. The neighbours' own
 * suites hold their `serviceOf("support-agent")` ACLs.
 */
final class FakeNeighbour(val name: String, defaults: PartialFunction[(String, String), Answer]) extends FakeServer():
  private val scripted = TrieMap.empty[(String, String), Answer]

  /** Answers `method path` (the path as `calls` names it, without the query) with `answer` until reset. */
  def answer(method: String, path: String, answer: Answer): Unit = scripted.update((method, path), answer)

  def reset(): Unit =
    scripted.clear()
    resetServer()

  /** The path after the brand's prefix, without the query, as the fake routes it. */
  def pathOf(r: Received): String = FakeNeighbours.Prefixes.foldLeft(r.path)((p, prefix) => p.replaceFirst(prefix, ""))

  def calls: Vector[Received]                 = received
  def calls(path: String): Vector[Received]   = received.filter(pathOf(_) == path)
  def writes: Vector[Received]                = received.filter(_.method != "GET")

  /** A bearer token the call carried: none, for a call made by identity. */
  def bearer(r: Received): Option[String] = r.headers.get("authorization")

  /** The key a write carried: `key`, or players' `approvalId`. */
  def key(r: Received): Option[String] = r.json.flatMap(j => j.text("key").orElse(j.text("approvalId")))

  protected def route(r: Received): Answer =
    val at = (r.method, pathOf(r))
    scripted.get(at).orElse(defaults.lift(at)).getOrElse(Answer(404, """{"error":"not_found"}"""))

object FakeNeighbours:
  val Brand = "goldvibe"

  private[it] val Prefixes = Vector(s"^/v1/casino/brands/$Brand", s"^/v1/casino/rg-service/$Brand", s"^/v1/casino/payouts-service/$Brand")

  /** An active, verified player in GBP, with no open session. */
  def players(): FakeNeighbour = FakeNeighbour("players", {
    case ("GET", s"/players/$_/status") =>
      Answer.ok(Json.obj("status" -> "ACTIVE", "verification" -> "VERIFIED", "displayName" -> "Ann", "currency" -> "GBP"))
    case ("GET", s"/players/$_/agent-profile") =>
      Answer.ok(Json.obj("email" -> "ann@example.com", "firstName" -> "Ann", "lastName" -> "Lee", "country" -> "Iceland",
        "language" -> "en", "dateOfBirth" -> "1990-01-01"))
    case ("GET", s"/players/$_/game-sessions") => Answer.ok(Json.obj("sessions" -> Json.arr()))
    case ("POST", s"/players/game-sessions/$s/end") => Answer.ok(Json.obj("sessionId" -> s, "status" -> "ENDED"))
  })

  /** £100.00 real, nothing restricted, no transactions, and no forfeit on a withdrawal. */
  def wallet(): FakeNeighbour = FakeNeighbour("wallet", {
    case ("GET", s"/wallets/$_/$c/balance") => Answer.ok(Json.obj("realBalance" -> 10000L, "restrictedBalance" -> 0L, "currency" -> c))
    case ("GET", s"/wallets/$_/$_/transactions") => Answer.ok(Json.obj("transactions" -> Json.arr()))
    case ("GET", s"/wallets/$_/$_/holds/preview") => Answer.ok(Json.obj("forfeits" -> Json.arr()))
  })

  def payouts(): FakeNeighbour = FakeNeighbour("payouts", {
    case ("POST", s"/players/$_/withdrawal-requests") => Answer.ok(Json.obj("requestId" -> "wr-1", "status" -> "PENDING"))
  })

  def rewards(): FakeNeighbour = FakeNeighbour("rewards", {
    case ("GET", s"/rewards/$_/grants") => Answer.ok(Json.obj("items" -> Json.arr()))
  })

  /** No limit, no break, no exclusion; each write applied at once. */
  def rg(): FakeNeighbour = FakeNeighbour("responsible-gambling", {
    case ("GET", s"/$_/limits") =>
      Answer.ok(Json.obj("limits" -> Json.arr(), "pendingChanges" -> Json.arr(), "activeRestrictions" -> Json.arr()))
    case ("PUT", s"/$_/limits/$t") => Answer.ok(Json.obj("limitType" -> t, "appliesAt" -> null))
    case ("PUT", s"/$_/cooling-off") => Answer.ok(Json.obj("status" -> "cooling_off"))
    case ("PUT", s"/$_/permanent-exclusion") => Answer.ok(Json.obj("status" -> "self_excluded"))
  })

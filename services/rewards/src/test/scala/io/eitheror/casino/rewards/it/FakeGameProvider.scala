package io.eitheror.casino.rewards.it

import io.eitheror.casino.rewards.Json
import io.eitheror.casino.rewards.Json.*

import scala.collection.concurrent.TrieMap

/**
 * `game-provider`'s free-rounds routes for one provider (spec 003 FR-025, and G1's game read), as research R8 reads
 * them: known answers 204; a create answers `active` unless scripted, and the bonus then reads as its create's status
 * until `statusFor` says otherwise; a cancel answers `cancelled`; spin values are set per currency; a game is set
 * with its bonus types, and one not set is `404 game_not_found`. `notConfigured` answers as a brand with no
 * provider configuration does.
 */
final class FakeGameProvider(brand: String, provider: String = "st8") extends FakeServer():
  private val creates  = TrieMap.empty[String, Answer]
  private val statuses = TrieMap.empty[String, String]
  private val cancels  = TrieMap.empty[String, Answer]
  private val values   = TrieMap.empty[String, Vector[String]]
  private val games    = TrieMap.empty[String, Vector[String]]
  @volatile var notConfigured: Boolean = false
  @volatile var knownAnswer: Option[Answer] = None

  def createFor(grantId: String, answer: Answer): Unit  = creates.update(grantId, answer)
  def createStatus(grantId: String, status: String): Unit = createFor(grantId, FakeGameProvider.status(status))
  /** What a poll of the bonus reads from now on. */
  def statusFor(grantId: String, status: String): Unit  = statuses.update(grantId, status)
  def cancelFor(grantId: String, answer: Answer): Unit  = cancels.update(grantId, answer)
  def spinValues(currency: String, offered: String*): Unit = values.update(currency, offered.toVector)
  def game(code: String, bonusTypes: String*): Unit     = games.update(code, bonusTypes.toVector)
  def reset(): Unit =
    creates.clear(); statuses.clear(); cancels.clear(); values.clear(); games.clear(); notConfigured = false; knownAnswer = None
    resetServer()

  private val Base = s"/v1/casino/brands/$brand/game-provider/$provider"

  def knownCalls: Vector[Received]                  = received.filter(r => r.method == "POST" && r.path.startsWith(s"$Base/players/") && r.path.endsWith("/known"))
  def createCalls: Vector[Received]                 = received.filter(r => r.method == "POST" && r.path == s"$Base/bonuses")
  def polls(grantId: String): Vector[Received]      = received.filter(r => r.method == "GET" && r.path == s"$Base/bonuses/$grantId")
  def cancelCalls(grantId: String): Vector[Received] = received.filter(r => r.method == "POST" && r.path == s"$Base/bonuses/$grantId/cancel")

  protected def route(r: Received): Answer =
    if !r.path.startsWith(Base) then FakeGameProvider.notFound
    else
      (r.method, r.path.stripPrefix(Base).split('/').toVector.drop(1)) match
        case ("POST", Vector("players", _, "known")) => knownAnswer.getOrElse(Answer(204, ""))
        case ("POST", Vector("bonuses")) =>
          val grantId = r.json.flatMap(_.text("grantId")).getOrElse("")
          if notConfigured then FakeGameProvider.status("not_configured")
          else
            val a = creates.getOrElse(grantId, FakeGameProvider.status("active"))
            a.parsed.flatMap(_.text("status")).foreach(s => statuses.putIfAbsent(grantId, s): Unit)
            a
        case ("GET", Vector("bonuses", grantId)) =>
          FakeGameProvider.status(if notConfigured then "not_configured" else statuses.getOrElse(grantId, "processing"))
        case ("POST", Vector("bonuses", grantId, "cancel")) =>
          if notConfigured then FakeGameProvider.status("not_configured")
          else cancels.getOrElse(grantId, { statuses.update(grantId, "cancelled"); FakeGameProvider.status("cancelled") })
        case ("GET", Vector("spin-values")) =>
          if notConfigured then FakeGameProvider.status("not_configured")
          else Answer.ok(Json.obj("values" -> values.getOrElse(r.query.getOrElse("currency", ""), Vector.empty)))
        case ("GET", Vector("games", code)) =>
          games.get(code).fold(Answer.json(404, Json.obj("error" -> "game_not_found")))(types =>
            Answer.ok(Json.obj("gameCode" -> code, "bonusTypes" -> types)))
        case _ => FakeGameProvider.notFound

object FakeGameProvider:
  def status(s: String): Answer = Answer.ok(Json.obj("status" -> s))
  val notFound: Answer          = Answer(404, """{"error":"not_found"}""")

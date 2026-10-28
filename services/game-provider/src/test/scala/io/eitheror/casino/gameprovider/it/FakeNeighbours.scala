package io.eitheror.casino.gameprovider.it

import io.eitheror.casino.gameprovider.Json
import io.eitheror.casino.gameprovider.Json.*
import io.eitheror.casino.gameprovider.clients.NeighbourPaths

import scala.collection.mutable

/**
 * `players`, `kyc`, `responsible-gambling` and `rewards` at the paths of `NeighbourPaths`, one server
 * for all four (each service name is mapped to it). Every answer is scripted per player; a player with
 * nothing scripted is active, KYC-cleared, free to play, has a profile and no eligible grant.
 */
final class FakeNeighbours(brand: String) extends FakeServer:

  import FakeNeighbours.Profile

  val accountStatus = mutable.Map.empty[String, String]
  val profiles      = mutable.Map.empty[String, Profile]
  val unknown       = mutable.Set.empty[String]
  val kycBlocked    = mutable.Map.empty[String, String]                  // player -> kyc status
  val rgRefused     = mutable.Map.empty[String, (String, Option[Long], Option[Long])] // reason, limit, used
  val grants        = mutable.Map.empty[(String, String), String]        // (player, game) -> grant id
  val down          = mutable.Set.empty[String]                          // "players", "kyc", "responsible-gambling", "rewards"
  val betRefused    = mutable.Map.empty[String, String]                  // player -> may-bet decision: locked, excluded, cooling_off
  /** Players for whom `may-bet` gives no answer in time (it answers after 1.5 s). */
  val betSilent     = mutable.Set.empty[String]
  @volatile var nextSession = 0

  def reset(): Unit = synchronized {
    accountStatus.clear(); profiles.clear(); unknown.clear(); kycBlocked.clear(); rgRefused.clear(); grants.clear(); down.clear()
    betRefused.clear(); betSilent.clear()
    clearReceived()
  }

  def sessionsOpened: Vector[Received] = received.filter(r => r.method == "POST" && r.path.endsWith("/game-sessions"))
  def recentlyPlayed: Vector[Received] = received.filter(r => r.method == "POST" && r.path.endsWith("/recently-played"))
  def mayBetAsked: Vector[Received]    = received.filter(r => r.method == "POST" && r.path.endsWith("/rg/may-bet"))

  private def unavailable = Answer(503, """{"error":"UNAVAILABLE"}""")

  private val Players = """/v1/casino/brands/[^/]+/players/([^/]+)/([a-z-]+)""".r
  private val Kyc     = """/v1/casino/brands/[^/]+/kyc/([^/]+)/play-check""".r
  private val Rg      = """/v1/casino/brands/[^/]+/rg/([^/]+)/may-play""".r
  private val MayBet  = """/v1/casino/brands/[^/]+/rg/may-bet""".r
  private val Rewards = """/v1/casino/brands/[^/]+/rewards/([^/]+)/free-rounds/eligible""".r

  protected def route(r: Received): Answer =
    val player = r.json.flatMap(_.text("playerId")).getOrElse("")
    // Outside the lock: a silent `may-bet` must not hold up the other neighbours.
    if MayBet.matches(r.path) && synchronized(betSilent(player)) then
      Thread.sleep(1500)
      unavailable
    else answer(r, player)

  private def answer(r: Received, player: String): Answer = synchronized {
    r.path match
      case MayBet() if down("responsible-gambling") => unavailable
      case MayBet() =>
        betRefused.get(player) match
          case None           => Answer.ok(Json.obj("decision" -> "allowed"))
          case Some(decision) => Answer.ok(Json.obj("decision" -> decision))
      case Players(_, _) if down("players") => unavailable
      case Players(player, "account-status") =>
        Answer.ok(Json.obj("status" -> accountStatus.getOrElse(player, "active")))
      case Players(player, "profile") =>
        if unknown(player) then Answer(404, """{"error":"PLAYER_NOT_FOUND"}""")
        else
          val p = profiles.getOrElse(player, Profile())
          Answer.ok(Json.obj("defaultCurrency" -> p.defaultCurrency, "country" -> p.country, "dateOfBirth" -> p.dateOfBirth,
            "registeredAt" -> p.registeredAt, "dailyDepositLimit" -> p.dailyDepositLimit))
      case Players(_, "game-sessions") =>
        nextSession += 1
        Answer.ok(Json.obj("sessionId" -> s"gs-$nextSession"))
      case Players(_, "recently-played") => Answer(204, "")
      case Kyc(_) if down("kyc")         => unavailable
      case Kyc(player) =>
        kycBlocked.get(player) match
          case None         => Answer.ok(Json.obj("allowed" -> true))
          case Some(status) => Answer.json(403, Json.obj("allowed" -> false, "error" -> "PLAY_BLOCKED_KYC", "kycStatus" -> status, "message" -> "verify first"))
      case Rg(_) if down("responsible-gambling") => unavailable
      case Rg(player) =>
        rgRefused.get(player) match
          case None => Answer.ok(Json.obj("allowed" -> true, "minutesUsed" -> 0L))
          case Some((reason, limit, used)) =>
            Answer.json(403, Json.obj("allowed" -> false, "reason" -> reason, "coolingOffUntil" -> Option.when(reason == "cooling_off")("2026-10-10T10:00:00Z"),
              "limitMinutes" -> limit, "usedMinutes" -> used))
      case Rewards(_) if down("rewards") => unavailable
      case Rewards(player) =>
        grants.get(player -> r.query.getOrElse("gameCode", "")) match
          case Some(g) => Answer.ok(Json.obj("grantId" -> g))
          case None    => Answer(404, """{"error":"NO_ELIGIBLE_GRANT"}""")
      case other => Answer(404, s"""{"error":"NO_ROUTE","path":"$other"}""")
  }

  /** The paths the fake serves are the clients' own constants (contracts/neighbours.md). */
  def servesThePathsTheClientsCall: Boolean =
    Seq(NeighbourPaths.accountStatus(brand, "p"), NeighbourPaths.profile(brand, "p"), NeighbourPaths.playCheck(brand, "p"),
      NeighbourPaths.mayPlay(brand, "p"), NeighbourPaths.mayBet(brand))
      .forall(p => Players.matches(p) || Kyc.matches(p) || Rg.matches(p) || MayBet.matches(p))

object FakeNeighbours:
  final case class Profile(defaultCurrency: String = "EUR", country: String = "mt", dateOfBirth: String = "1990-01-01",
      registeredAt: String = "2026-01-02T10:00:00Z", dailyDepositLimit: Option[Long] = None)

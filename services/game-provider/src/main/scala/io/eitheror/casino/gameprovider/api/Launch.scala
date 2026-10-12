package io.eitheror.casino.gameprovider.api

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.http.{Bytes, Respond}
import io.eitheror.casino.contract.money.Currency
import io.eitheror.casino.gameprovider.Json
import io.eitheror.casino.gameprovider.Json.*
import io.eitheror.casino.gameprovider.api.Context.*
import io.eitheror.casino.gameprovider.api.callbacks.Callbacks
import io.eitheror.casino.gameprovider.clients.*
import io.eitheror.casino.gameprovider.players.*
import io.eitheror.casino.gameprovider.provider.LaunchRequest
import io.eitheror.casino.gameprovider.tickets.*

import java.util.UUID

/**
 * A player's launch (FR-007 to FR-009, research R9), in the original's order (`SessionRoutes.scala:257-565`):
 * the body and the player's own id; 20 launches a minute; `players`, `kyc` and `responsible-gambling`, each
 * failing closed; the provider's configuration; the grant; the ticket; the provider's launch; the game
 * session and recently played, best effort.
 */
final class Launch(ctx: Context):

  private val Restricted = Set("suspended", "self_excluded", "blocked", "closed")

  def apply(body: String, caller: String): Respond[Bytes] =
    val node     = Json.parseObject(body).getOrElse(badRequest("the body is not a JSON object"))
    val playerId = node.text("playerId").getOrElse(return refuse(400, "playerId required"))
    val gameId   = node.text("gameId").getOrElse(return refuse(400, "gameId required"))
    if playerId != caller then return refuse(403, "player_id_mismatch")
    val demo = node.bool("demo").getOrElse(false)

    val window = ctx.components.forKeyValueEntity(EntityId(playerId)).call(LaunchWindow.launch).invoke(ctx.now())
    if !window.admitted then return refuse(429, "RATE_LIMITED", "action" -> "launch", "retryAfterSeconds" -> window.retryAfterSeconds)

    ctx.players.accountStatus(playerId) match
      case Left(down)                         => return unavailable(down)
      case Right(status) if Restricted(status) => return refuse(403, "account_restricted", "reason" -> status)
      case Right(_)                           => ()
    ctx.kyc.playCheck(playerId) match
      case Left(down)                                => return unavailable(down)
      case Right(PlayCheck.Blocked(status, message)) => return refuse(403, "PLAY_BLOCKED_KYC", "kycStatus" -> status, "message" -> message)
      case Right(PlayCheck.Allowed)                  => ()
    ctx.rg.mayPlay(playerId) match
      case Left(down) => return unavailable(down)
      case Right(MayPlay.Refused("cooling_off", until, _, _)) => return refuse(403, "cooling_off_active", "coolingOffUntil" -> until)
      case Right(MayPlay.Refused("session_limit_reached", _, limit, used)) =>
        return refuse(403, "session_limit_reached", "limitMinutes" -> limit, "usedMinutes" -> used)
      case Right(MayPlay.Refused(reason, _, _, _)) => return refuse(403, "account_restricted", "reason" -> reason)
      case Right(MayPlay.Allowed(_, _))            => ()

    // The game by provider and code (FR-008): the provider named, else the catalogue's, else the one configured.
    val named = node.text("provider")
    val game = ctx.catalogue.resolve(gameId, named) match
      case Left(ambiguous) => return refuse(400, "provider_required", "providers" -> ambiguous.providers)
      case Right(found)    => found
    val provider = named.orElse(game.map(_.provider)).orElse(ctx.configs.providers.headOption.filter(_ => ctx.configs.providers.size == 1))
      .getOrElse("st8")
    val configuration = ctx.configs.get(provider).getOrElse(return refuse(503, "st8_not_configured", "provider" -> provider))
    val adapter       = ctx.settings.adapter(provider).getOrElse(return refuse(503, "st8_not_configured", "provider" -> provider))

    val profile = ctx.players.profile(playerId) match
      case ProfileAnswer.Found(p) => Some(p)
      case _                      => None
    val currency = node.text("currency").orElse(profile.flatMap(_.defaultCurrency)).filter(c => Currency.from(c).isRight).getOrElse("EUR")

    val grant =
      if demo then None
      else
        node.text("freeSpinsGrantRef") match
          case Some(ref) =>
            val id = ref.stripPrefix("free-spins-grants/").trim
            if id.isEmpty || id.contains("/") then return refuse(400, "free_spins_grant_invalid")
            Some(id)
          case None => ctx.rewards.oldestEligibleGrant(playerId, provider, gameId)

    val token  = UUID.randomUUID().toString
    val ticket = ctx.components.forKeyValueEntity(EntityId(token))
    ticket.call(Ticket.issue).invoke(IssueTicket(provider, playerId, gameId, game.flatMap(_.fields.map(_.category)), "", currency, grant, demo, ctx.now())): Unit

    val request = LaunchRequest(
      gameCode = gameId, currency = currency, token = token, playerId = playerId, funMode = demo, siteId = configuration.site,
      lobbyUrl = configuration.lobbyUrl, depositUrl = configuration.depositUrl, walletUrl = ctx.walletUrl(provider),
      defaultCurrency = profile.flatMap(_.defaultCurrency).getOrElse(currency), regCountry = profile.flatMap(_.country).map(_.toUpperCase).getOrElse(""),
      jurisdiction = configuration.jurisdiction, birthDate = profile.flatMap(_.dateOfBirth).getOrElse(""),
      regDate = profile.flatMap(_.registeredAt).map(Callbacks.calendarDay).getOrElse(""),
      betLimits = BetLimits.band(profile.flatMap(_.dailyDepositLimit), Currency.unsafe(currency))
    )
    adapter.launch(configuration, request) match
      case Left(error) =>
        ticket.call(Ticket.discard).invoke(): Unit
        refuse(500, "launch_failed", "message" -> error)
      case Right(gameUrl) =>
        val sessionId = ctx.players.openGameSession(playerId, gameId, game.flatMap(_.fields.map(_.name)), game.flatMap(_.fields.map(_.category)),
          currency, demo, None).getOrElse(UUID.randomUUID().toString)
        ticket.call(Ticket.attachSession).invoke(sessionId): Unit
        ctx.players.recentlyPlayed(playerId, gameId)
        ok(Json.obj("launchToken" -> token, "gameUrl" -> gameUrl, "sessionId" -> sessionId, "freeSpinsGrantRef" -> grant))

  private def unavailable(down: ServiceUnavailable): Respond[Bytes] =
    refuse(503, "service_unavailable", "service" -> down.service)

package io.eitheror.casino.gameprovider.port

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.contract.facts.{PlayerReinstated, PlayerSuspended}
import io.eitheror.casino.contract.money.Currency
import io.eitheror.casino.contract.port.Recording
import io.eitheror.casino.gameprovider.Json.*
import io.eitheror.casino.gameprovider.it.{FakeNeighbours, Kit}
import io.eitheror.casino.gameprovider.money.Amounts
import io.eitheror.casino.gameprovider.players.{KnownPlayer, MakeKnown}
import io.eitheror.casino.gameprovider.tickets.{CheckTicket, IssueTicket, Ticket}

/**
 * The original's test-control routes (`/st8/test/...`, `St8Routes.scala:284-545`), which this service does not
 * serve (Clarifications), as the kit setup each stands for (research R15). A recording's control step is
 * replayed as setup and answered with what the original answered, so it is not compared.
 */
final class TestControl(kit: Kit):
  /** Set by `signature-mode`: the original's switch to accept or refuse every signature. */
  var signatureValid: Boolean = true

  private def currencyOf(b: JsonNode) = b.textOr("currency", "EUR")
  private def minor(amount: String, currency: String) =
    Amounts.parse(amount, Currency.unsafe(currency)).map(_.value).getOrElse(0L)

  /** Applies the control step, or answers why it cannot be stood in for. */
  def apply(request: Recording.Request): Either[String, Unit] =
    val segments = request.path.stripPrefix("/st8/test/").split('/').toList
    val body     = request.body.getOrElse(io.eitheror.casino.gameprovider.Json.obj())
    def known(player: String, currency: String) =
      kit.testKit.componentClient.forKeyValueEntity(EntityId(player)).call(KnownPlayer.makeKnown).invoke(MakeKnown("st8", Some(currency), kit.clock)): Unit
    def ticket(token: String, checked: Boolean) =
      val t = kit.testKit.componentClient.forKeyValueEntity(EntityId(token))
      val player = body.textOr("playerId", "")
      t.call(Ticket.issue).invoke(IssueTicket("st8", player, body.textOr("gameCode", ""), None, body.textOr("sessionId", ""),
        currencyOf(body), body.text("freeSpinsGrantRef"), false, kit.clock)): Unit
      if checked then
        t.call(Ticket.check).invoke(CheckTicket(None, kit.clock)): Unit
        known(player, currencyOf(body))
    segments match
      case "reset" :: Nil                    => Right(kit.reset())
      case "set-public-key" :: Nil           => Right(()) // the kit registers the fixture key as the brand's (FR-030)
      case "signature-mode" :: Nil =>
        signatureValid = !body.textOr("mode", "VALID").equalsIgnoreCase("INVALID")
        Right(())
      case "setup-brand" :: _ :: Nil         => Right(())   // the provider configuration the kit wrote carries the lobby and deposit links
      case "setup-launch-token" :: token :: Nil => Right(ticket(token, checked = false))
      case "setup-wallet-token" :: token :: Nil => Right(ticket(token, checked = true))
      case "setup-player" :: player :: Nil =>
        known(player, currencyOf(body))
        status(player, body.textOr("status", "ACTIVE"))
        Right(())
      case "update-player-status" :: player :: Nil => Right(status(player, body.textOr("status", "ACTIVE")))
      case "setup-balance" :: player :: Nil =>
        val (_, restricted) = kit.wallet.balance(player, currencyOf(body))
        Right(kit.wallet.set(player, currencyOf(body), minor(body.textOr("balance", "0"), currencyOf(body)), restricted))
      case "setup-bonus-balance" :: player :: Nil =>
        val (real, _) = kit.wallet.balance(player, currencyOf(body))
        Right(kit.wallet.set(player, currencyOf(body), real, minor(body.textOr("balance", "0"), currencyOf(body))))
      case "setup-player-profile" :: player :: Nil =>
        Right(kit.neighbours.profiles.update(player, FakeNeighbours.Profile(dateOfBirth = body.textOr("dob", "1990-01-01"),
          registeredAt = body.textOr("registrationDate", "2024-01-01"))))
      case other =>
        Left(s"no kit setup stands for /st8/test/${other.mkString("/")}: force-set transaction and cancel state has no equivalent here; extend TestControl when a recording needs it")

  private def status(player: String, status: String): Unit =
    status.toUpperCase match
      case "ACTIVE" => kit.publishPlayers(PlayerReinstated(player, "suspended", "active", "test", None, None, kit.clock))
      case _        => kit.publishPlayers(PlayerSuspended(player, "active", status.toLowerCase, "test", None, None, kit.clock))

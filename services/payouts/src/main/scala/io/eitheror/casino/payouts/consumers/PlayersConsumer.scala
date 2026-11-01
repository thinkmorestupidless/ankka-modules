package io.eitheror.casino.payouts.consumers

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.contract.facts.*
import io.eitheror.casino.payouts.player.*

/**
 * `casino.players` into the player's payout record (data-model.md): the KYC status, the profile's names, the
 * primary email, the registration's currency (the wallet's), and the account's closure. Each command writes nothing when the record already holds what it
 * says, so a redelivery changes nothing; a fact for a player with no record starts one.
 */
final class PlayersConsumer(client: ComponentClient) extends Consumer[Option[CasinoPlayersFact], Nothing]:
  def onMessage(fact: Option[CasinoPlayersFact]): Effect = fact match
    case Some(f) =>
      PlayersConsumer.commands(f) match
        case Vector() => effects.ignore()
        case commands =>
          val player = client.forEventSourcedEntity(EntityId(f.playerId))
          commands.foreach {
            case c: SeeKyc    => player.call(PlayerPayouts.seeKyc).invoke(c): Unit
            case c: SeeNames  => player.call(PlayerPayouts.seeNames).invoke(c): Unit
            case c: SeeEmail  => player.call(PlayerPayouts.seeEmail).invoke(c): Unit
            case c: SeeClosed => player.call(PlayerPayouts.seeClosed).invoke(c): Unit
            case c: SeeCurrency => player.call(PlayerPayouts.seeCurrency).invoke(c): Unit
          }
          effects.done()
    case None =>
      Read.unreadable(CasinoPlayers.name, messageContext.subject)
      effects.ignore()

object PlayersConsumer extends Consumer.Companion[PlayersConsumer, Option[CasinoPlayersFact], Nothing](
      ComponentId("payouts-players-consumer"), Read.from(CasinoPlayers)):
  def create(ctx: ConsumerContext) = new PlayersConsumer(ctx.componentClient)

  type Command = SeeKyc | SeeNames | SeeEmail | SeeClosed | SeeCurrency

  /**
   * The commands a fact makes, in order; none for a family this service does not read. The names are taken
   * only when the fact carries both, since the payee's holder is checked against the pair (R6).
   */
  def commands(fact: CasinoPlayersFact): Vector[Command] = fact match
    case f: PlayerRegistered =>
      names(f.givenName, f.familyName, f) ++ f.email.map(SeeEmail(_, f.occurredAt)).toVector ++
        f.currency.filter(_.trim.nonEmpty).map(SeeCurrency(_, f.occurredAt)).toVector
    case f: PlayerProfileUpdated => names(f.givenName, f.familyName, f)
    case f: PlayerEmailChanged   => Vector(SeeEmail(f.email, f.occurredAt))
    case f: PlayerClosed         => Vector(SeeClosed(f.occurredAt))
    case f: PlayerKycSubmitted   => Vector(SeeKyc(Kyc.Submitted, f.occurredAt))
    case f: PlayerKycPending     => Vector(SeeKyc(Kyc.Pending, f.occurredAt))
    case f: PlayerKycVerified    => Vector(SeeKyc(Kyc.Verified, f.occurredAt))
    case f: PlayerKycRejected    => Vector(SeeKyc(Kyc.Rejected, f.occurredAt))
    case _                       => Vector.empty

  private def names(first: Option[io.eitheror.casino.contract.personal.Personal[String]],
      last: Option[io.eitheror.casino.contract.personal.Personal[String]], f: CasinoPlayersFact): Vector[Command] =
    (first, last) match
      case (Some(g), Some(l)) => Vector(SeeNames(g, l, f.occurredAt))
      case _                  => Vector.empty

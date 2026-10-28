package io.eitheror.casino.attribution.inputs

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId}
import io.eitheror.casino.attribution.Wiring
import io.eitheror.casino.attribution.record.{AttributionRecord, CloseRecord, Record}
import io.eitheror.casino.attribution.tag.TagCheck
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.contract.facts.*
import org.slf4j.LoggerFactory

/**
 * `casino.players` (data-model.md *Consumers*): `player_registered` makes the record, `player_closed` and
 * an indefinite `player_self_excluded` close it; a period exclusion and every other family (`player_reopened`
 * among them: a player reported closed stays closed to the programme) are ignored and call nothing.
 */
final class PlayersFacts(context: ConsumerContext) extends Consumer[Option[CasinoPlayersFact], Unit]:
  private def registrar = Registrar(context.componentClient, context.secrets,
    TagCheck.live(context.componentClient, () => Wiring.networks.get()()), () => Wiring.settings.get().reportingZone,
    () => Wiring.clock.get().instant())

  def onMessage(fact: Option[CasinoPlayersFact]): Effect = fact match
    case Some(r: PlayerRegistered) =>
      context.componentClient.forEventSourcedEntity(EntityId(r.playerId)).call(AttributionRecord.registered)
        .invoke(registrar.resolve(r)): Unit
      effects.done()
    case Some(c: PlayerClosed) => close(c.playerId, Record.Category.Closed, c.occurredAt)
    case Some(x: PlayerSelfExcluded) if x.until.isEmpty => close(x.playerId, Record.Category.SelfExcluded, x.occurredAt)
    case Some(_)                     => effects.ignore()
    case None =>
      PlayersFacts.log.warn("a casino.players fact this service cannot read was skipped (subject {})", messageContext.subject)
      effects.ignore()

  /** Closed for the protocol, once (research R11): the category only, never the reason the fact carries. */
  private def close(player: String, category: String, at: java.time.Instant): Effect =
    context.componentClient.forEventSourcedEntity(EntityId(player)).call(AttributionRecord.close).invoke(CloseRecord(category, at)): Unit
    effects.done()

object PlayersFacts extends Consumer.Companion[PlayersFacts, Option[CasinoPlayersFact], Unit](
      componentId = ComponentId("players-facts"),
      source = ChangeSource.fromTopic(CasinoPlayers.name, Lenient.serializer(CasinoPlayers.serializer), StartFrom.Earliest,
        TopicOptions(Some(CasinoPlayers.contract), None, false))
    ):
  private val log = LoggerFactory.getLogger(classOf[PlayersFacts])

  def create(ctx: ConsumerContext) = new PlayersFacts(ctx)

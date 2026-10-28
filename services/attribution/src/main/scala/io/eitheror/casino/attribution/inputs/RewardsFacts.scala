package io.eitheror.casino.attribution.inputs

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId}
import io.eitheror.casino.attribution.{Binding, Wiring}
import io.eitheror.casino.attribution.record.{AttributionRecord, Touches}
import io.eitheror.casino.attribution.tag.TagCheck

import java.time.Duration
import scala.util.{Failure, Success, Try}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.contract.facts.*
import org.slf4j.LoggerFactory

/**
 * `casino.rewards`, registered only when the brand runs the casino module: `bonus_code_entered` binds
 * within the bonus code window or is a touch (research R7); every other family is ignored.
 */
final class RewardsFacts(context: ConsumerContext) extends Consumer[Option[CasinoRewardsFact], Unit]:
  private val sources = TagCheck.live(context.componentClient, () => Wiring.networks.get()())
  private def registrar = Registrar(context.componentClient, context.secrets, sources, () => Wiring.settings.get().reportingZone,
    () => Wiring.clock.get().instant())

  def onMessage(fact: Option[CasinoRewardsFact]): Effect = fact match
    case Some(e: BonusCodeEntered) =>
      entered(e)
      effects.done()
    case Some(_)                   => effects.ignore()
    case None =>
      RewardsFacts.log.warn("a casino.rewards fact this service cannot read was skipped (subject {})", messageContext.subject)
      effects.ignore()

  /**
   * A code entered after registration (research R7): an unbound player within the bonus code window of
   * their registration is bound at the entry (`later_code`); anyone else, or a code that binds nothing,
   * is a touch with what became of it.
   */
  private def entered(e: BonusCodeEntered): Unit =
    val record = context.componentClient.forEventSourcedEntity(EntityId(e.playerId))
    val state  = record.call(AttributionRecord.get).invoke()
    val window = Duration.ofDays(sources.settings().bonusCodeWindowDays.toLong)
    val within = state.registeredAt.exists(r => !e.occurredAt.isAfter(r.plus(window)))
    def touch(outcome: String): Unit =
      record.call(AttributionRecord.touch).invoke(Touches.ofCode(e.code, outcome, e.occurredAt)): Unit
    registrar.byCode(e.playerId, e.code, e.occurredAt, Binding.LaterCode) match
      case Left(outcome)                          => touch(outcome)
      case Right(_) if state.bound || !within     => touch(Touches.Later)
      case Right(b) =>
        Try(record.call(AttributionRecord.bind).invoke(b)) match
          case Success(_) => ()
          case Failure(_) => touch(Touches.Later) // bound meanwhile: the binding is set once (FR-007)

object RewardsFacts extends Consumer.Companion[RewardsFacts, Option[CasinoRewardsFact], Unit](
      componentId = ComponentId("rewards-facts"),
      source = ChangeSource.fromTopic(CasinoRewards.name, Lenient.serializer(CasinoRewards.serializer), StartFrom.Earliest,
        TopicOptions(Some(CasinoRewards.contract), None, false))
    ):
  private val log = LoggerFactory.getLogger(classOf[RewardsFacts])

  def create(ctx: ConsumerContext) = new RewardsFacts(ctx)

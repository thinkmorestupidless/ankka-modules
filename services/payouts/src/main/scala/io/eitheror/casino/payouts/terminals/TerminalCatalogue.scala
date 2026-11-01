package io.eitheror.casino.payouts.terminals

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.contract.money.Currency
import io.eitheror.casino.payouts.{PayoutsCodecs, PayoutsRuntime}
import io.eitheror.casino.payouts.clients.Gateway
import org.slf4j.LoggerFactory

import java.time.Instant
import scala.concurrent.duration.*

/** The gateway's withdrawal terminals per currency code, as last read, and when (data-model.md). */
final case class CatalogueState(terminals: Map[String, Vector[Terminal]] = Map.empty, refreshedAt: Map[String, Instant] = Map.empty):
  def of(currency: String): Option[Vector[Terminal]] = terminals.get(currency)

final case class Refresh(currency: String, terminals: Vector[Terminal], at: Instant)

/**
 * The brand's withdrawal terminals as the gateway lists them (R5), keyed by the slug: held here so the
 * wallet alone is in a request's path (SC-005), and refreshed every 60 seconds by `CatalogueTimer`. A
 * refresh replaces one currency's list.
 */
final class TerminalCatalogue(@scala.annotation.unused context: KeyValueEntityContext) extends KeyValueEntity[CatalogueState]:
  def emptyState: CatalogueState = CatalogueState()

  def refresh(c: Refresh): Effect[CatalogueState] =
    if currentState.of(c.currency).contains(c.terminals) then
      effects.updateState(currentState.copy(refreshedAt = currentState.refreshedAt.updated(c.currency, c.at))).thenReplyState
    else
      effects.updateState(CatalogueState(currentState.terminals.updated(c.currency, c.terminals),
        currentState.refreshedAt.updated(c.currency, c.at))).thenReplyState

  def get: ReadOnlyEffect[CatalogueState] = effects.reply(currentState)

object TerminalCatalogue
    extends KeyValueEntity.Companion[TerminalCatalogue, CatalogueState](
      componentId = ComponentId("terminal-catalogue"),
      stateSerializer = PayoutsCodecs.serializer[CatalogueState]("catalogue-state")
    ):
  given Serializer[CatalogueState] = PayoutsCodecs.serializer[CatalogueState]("catalogue-reply")
  given Serializer[Refresh]        = PayoutsCodecs.serializer[Refresh]("catalogue-refresh")

  def create(context: KeyValueEntityContext) = new TerminalCatalogue(context)

  val refresh = command("refresh")(_.refresh)
  val get     = query("get")(_.get)

/** The catalogue as the request path and the listing read it: a currency not yet held is read from the gateway once. */
object Catalogue:
  private val log = LoggerFactory.getLogger("payouts.catalogue")

  /** Each held currency's list from the gateway; one the gateway could not give keeps its last list. */
  def refreshes(held: CatalogueState, read: Currency => Either[String, Vector[Terminal]], at: Instant): Vector[Refresh] =
    held.terminals.keys.toVector.sorted.flatMap { code =>
      read(Currency.unsafe(code)) match
        case Right(terminals) => Some(Refresh(code, terminals, at))
        case Left(why) =>
          log.warn(s"the $code withdrawal terminals were not refreshed, the last list is kept: $why")
          None
    }

  /** The currency's terminals; a currency not held asks the gateway once, and an unreadable answer is none. */
  def read(components: ComponentClient, gateway: => Gateway, slug: String, currency: Currency, at: Instant): Vector[Terminal] =
    val entity = components.forKeyValueEntity(EntityId(slug))
    entity.call(TerminalCatalogue.get).invoke().of(currency.code).getOrElse {
      gateway.terminals(currency) match
        case Right(terminals) =>
          entity.call(TerminalCatalogue.refresh).invoke(Refresh(currency.code, terminals, at)): Unit
          terminals
        case Left(why) =>
          log.warn(s"the ${currency.code} withdrawal terminals could not be read: $why")
          Vector.empty
    }

/** The recurring timer `terminals-<slug>`, every 60 s: every held currency refreshed in turn. Always done. */
final class CatalogueTimer(context: TimedActionContext) extends TimedAction:
  def run: Effect =
    val slug   = PayoutsRuntime.settings.brand
    val entity = context.componentClient.forKeyValueEntity(EntityId(slug))
    val gateway = PayoutsRuntime.gateway(context.services)
    Catalogue.refreshes(entity.call(TerminalCatalogue.get).invoke(), gateway.terminals, PayoutsRuntime.now())
      .foreach(r => entity.call(TerminalCatalogue.refresh).invoke(r): Unit)
    effects.done()

object CatalogueTimer extends TimedAction.Companion[CatalogueTimer](ComponentId("catalogue-timer")):
  def name(slug: String): String = s"terminals-$slug"

  def create(context: TimedActionContext) = new CatalogueTimer(context)
  val refresh = handler("refresh")(_.run)

  /** Scheduled once at start; a restart replaces it under the same name. */
  def schedule(timers: TimerScheduler, slug: String, every: FiniteDuration = 60.seconds): Unit =
    timers.createRecurringTimer(name(slug), every, every, refresh.deferred)

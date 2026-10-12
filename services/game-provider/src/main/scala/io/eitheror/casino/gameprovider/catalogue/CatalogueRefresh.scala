package io.eitheror.casino.gameprovider.catalogue

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId, Serializers}
import com.thinkmorestupidless.ankka.runtime.ViewClient
import com.thinkmorestupidless.ankka.runtime.SqlSyntax.*
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.gameprovider.config.ProviderConfigurations
import io.eitheror.casino.gameprovider.provider.ProviderAdapter

import java.time.{Duration, Instant, LocalTime, ZoneOffset}
import scala.concurrent.duration.*

import Serializers.given

/**
 * One provider's catalogue refresh (FR-021, research R11): fetch the provider's list for the brand's
 * site, upsert every game under `<provider>/<code>`, mark removed the provider's games a non-empty list
 * no longer names, change nothing on an empty list, retry three times at 10, 30 and 60 seconds, and put
 * the outcome on `RefreshStatus`. A failure is reported, not thrown, so a timer that runs this is done.
 */
final class CatalogueRefresher(
    components: ComponentClient,
    views: ViewClient,
    adapters: String => Option[ProviderAdapter],
    configs: ProviderConfigurations,
    sleep: Duration => Unit = d => Thread.sleep(d.toMillis),
    now: () => Instant = () => Instant.now()
):
  private def status(provider: String) = components.forKeyValueEntity(EntityId(provider))

  def run(provider: String): RefreshState =
    val started = now()
    def set(s: RefreshState) = status(provider).call(RefreshStatus.set).invoke(s)
    (configs.get(provider), adapters(provider)) match
      case (None, _) | (_, None) =>
        set(RefreshState(provider, "failed", Some(started), Some(now()), None, None, Some("st8_not_configured: no configuration for the provider"), 0))
      case (Some(configuration), Some(adapter)) =>
        set(RefreshState(provider, "running", Some(started), None, None, None, None, 1))
        val attempts = LazyList(Duration.ZERO) ++ CatalogueRefresher.Retries
        val outcome = attempts.zipWithIndex.map { (delay, i) =>
          if !delay.isZero then sleep(delay)
          if i > 0 then set(RefreshState(provider, "running", Some(started), None, None, None, None, i + 1))
          adapter.games(configuration)
        }.find(_.isRight).getOrElse(Left("every attempt failed"))
        outcome match
          case Left(error) =>
            val last = adapter.games(configuration).left.getOrElse(error)
            set(RefreshState(provider, "failed", Some(started), Some(now()), None, None, Some(last), 1 + CatalogueRefresher.Retries.size))
          case Right(listed) =>
            listed.foreach { g =>
              components.forKeyValueEntity(EntityId(Game.entityId(provider, g.code))).call(Game.upsert)
                .invoke(UpsertGame(provider, g.code, g.fields, now())): Unit
            }
            val removed =
              if listed.isEmpty then 0
              else
                val names = listed.map(_.code).toSet
                val gone  = views.forView(Catalogue).where(jsonText("provider") ++ sql" = $provider", 100_000).map(_.code).filterNot(names)
                gone.foreach(code => components.forKeyValueEntity(EntityId(Game.entityId(provider, code))).call(Game.markRemoved).invoke(): Unit)
                gone.size
            set(RefreshState(provider, "succeeded", Some(started), Some(now()), Some(listed.size), Some(removed), None, 1))

object CatalogueRefresher:
  val Retries: Vector[Duration] = Vector(Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofSeconds(60))

  /** The next 03:00 UTC after `now` (research R11). */
  def nextNightly(now: Instant): Instant =
    val today = now.atZone(ZoneOffset.UTC).toLocalDate.atTime(LocalTime.of(3, 0)).toInstant(ZoneOffset.UTC)
    if today.isAfter(now) then today else today.plus(Duration.ofDays(1))

/**
 * Where the timed action finds what it needs: the scheduler, the adapters and the configuration cache
 * the service built at start. ankka 0.11.0 has no recurring timer (032), so the nightly timer reschedules
 * itself from its handler, the pattern 032 replaces.
 */
object RefreshRuntime:
  /** Read when used: the timer runtime's scheduler exists only once the service has started. */
  @volatile var scheduler: Option[() => TimerScheduler]           = None
  @volatile var adapters: String => Option[ProviderAdapter]       = _ => None
  @volatile var configurations: Option[SecretStore => ProviderConfigurations] = None
  @volatile var sleep: Duration => Unit                           = d => Thread.sleep(d.toMillis)
  @volatile var now: () => Instant                                = () => Instant.now()
  @volatile var views: Option[ViewClient]                         = None

  def nightlyTimer(provider: String): String = s"catalogue-nightly-$provider"
  def nowTimer(provider: String): String     = s"catalogue-now-$provider"

  /** Schedules a provider's nightly refresh, idempotently: scheduling again under the name replaces it. */
  def scheduleNightly(provider: String): Unit =
    scheduler.map(_()).foreach { s =>
      val delay = Duration.between(now(), CatalogueRefresher.nextNightly(now()))
      s.createSingleTimer(nightlyTimer(provider), delay.toMillis.millis, CatalogueRefresh.nightly.deferred(provider))
    }

  /** Runs a provider's refresh now, durably: a timer due at once. */
  def refreshNow(provider: String): Unit =
    scheduler.map(_()).foreach(_.createSingleTimer(nowTimer(provider), 0.millis, CatalogueRefresh.once.deferred(provider)))

final class CatalogueRefresh(context: TimedActionContext) extends TimedAction:
  private def refresher =
    val configs = RefreshRuntime.configurations.map(_(context.secrets)).getOrElse(ProviderConfigurations(context.secrets))
    CatalogueRefresher(context.componentClient, RefreshRuntime.views.get, RefreshRuntime.adapters, configs, RefreshRuntime.sleep, RefreshRuntime.now)

  /** The nightly refresh of one provider, which then schedules the next night's. */
  def nightly(provider: String): Effect =
    refresher.run(provider): Unit
    RefreshRuntime.scheduleNightly(provider)
    effects.done()

  /** A refresh an operator asked for. */
  def once(provider: String): Effect =
    refresher.run(provider): Unit
    effects.done()

object CatalogueRefresh extends TimedAction.Companion[CatalogueRefresh](ComponentId("catalogue-refresh")):
  def create(context: TimedActionContext) = new CatalogueRefresh(context)
  val nightly = handler("nightly")(_.nightly)
  val once    = handler("once")(_.once)

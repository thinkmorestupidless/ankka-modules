package io.eitheror.casino.merchant.hierarchy

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.merchant.SeededBrand
import io.eitheror.casino.merchant.deposits.DepositRuntime

import java.nio.charset.StandardCharsets.UTF_8
import java.util.UUID
import scala.concurrent.duration.*

/**
 * Each brand's holding, company and sub-brand (FR-003, research R15), made on start from
 * `MERCHANT_BRANDS` under the ids the original derives (decision 0050:
 * `UUID.nameUUIDFromBytes("seed.<holding|company|sub-brand>.<slug>")`), named by the slug as the
 * original's `seedHierarchy` names them, the sub-brand of type Casino naming its brand. A timer is at
 * least once and the entities record nothing for a create they already hold, so a second start, or a
 * second apply of the manifest, changes nothing. A failure fails the run and the timer runs it again.
 */
final class Seed(context: TimedActionContext) extends TimedAction:
  def run: Effect =
    val at = DepositRuntime.now()
    DepositRuntime.settings.brands.foreach { b =>
      val (h, c, s) = Seed.ids(b.slug)
      context.componentClient.forEventSourcedEntity(EntityId(h)).call(Holding.create).invoke(CreateOrg(b.slug, None, Actor.seed, at)): Unit
      context.componentClient.forEventSourcedEntity(EntityId(c)).call(Company.create).invoke(CreateOrg(b.slug, Some(h), Actor.seed, at)): Unit
      context.componentClient.forEventSourcedEntity(EntityId(s)).call(SubBrand.create)
        .invoke(CreateSubBrand(c, h, b.slug, SubBrandType.Casino, Some(b.slug), Actor.seed, at)): Unit
    }
    effects.done()

object Seed extends TimedAction.Companion[Seed](ComponentId("seed")):
  def create(context: TimedActionContext) = new Seed(context)
  val run = handler("run")(_.run)

  private def nameUUID(s: String) = UUID.nameUUIDFromBytes(s.getBytes(UTF_8)).toString

  /** The holding, company and sub-brand ids of a brand, as the original derives them. */
  def ids(slug: String): (String, String, String) =
    (nameUUID(s"seed.holding.$slug"), nameUUID(s"seed.company.$slug"), nameUUID(s"seed.sub-brand.$slug"))

  /** Scheduled at start, at once; scheduling again under the name replaces it. */
  def schedule(scheduler: TimerScheduler, brands: Vector[SeededBrand]): Unit =
    if brands.nonEmpty then scheduler.createSingleTimer("seed", 0.millis, run.deferred)

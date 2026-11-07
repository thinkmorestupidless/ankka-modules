package io.eitheror.casino.provider.edge.brands

import com.thinkmorestupidless.ankka.core.{CommandError, EntityId}
import com.thinkmorestupidless.ankka.sdk.ComponentClient
import io.eitheror.casino.provider.brands.Brand
import io.eitheror.casino.provider.core.Brands

import java.net.URI
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.*
import scala.util.Try

/**
 * The brand entries as the outbound path and the core read them (spec 026 R3): a process-local copy
 * of the entities, refreshed from them at start and on every write the routes make, so a change
 * applies without a redeploy. A read never calls the entities: `known`, `answerWait` and the rest
 * run on every callback.
 *
 * `caPem: env:<VAR>` names a setting of the service, resolved when the brand is read, as the
 * simulator's table resolves it; a variable that is not set leaves the brand with no CA of its own.
 */
final class BrandCache(env: Map[String, String] = sys.env) extends Brands:

  private val entries = AtomicReference(Map.empty[String, BrandEntry])

  /** Reads every entry the index names; an entry the index lists that is gone is skipped. */
  def refresh(client: ComponentClient): Unit =
    val slugs = client.forKeyValueEntity(EntityId(BrandIndex.Id)).call(BrandIndex.slugs).invoke()
    val read  = slugs.flatMap { s =>
      try Some(s -> client.forKeyValueEntity(EntityId(s)).call(BrandEntryEntity.get).invoke())
      catch case _: CommandError => None
    }
    entries.set(read.toMap)

  /** What a write through the routes changed, applied at once. */
  def put(entry: BrandEntry): Unit = entries.updateAndGet(_.updated(entry.slug, entry)): Unit
  def remove(slug: String): Unit   = entries.updateAndGet(_ - slug): Unit

  def entry(slug: String): Option[BrandEntry] = entries.get.get(slug)
  def bySite(site: String): Option[BrandEntry] = entries.get.values.find(_.site == site)
  def all: Vector[BrandEntry]                  = entries.get.values.toVector.sortBy(_.slug)

  /** The brand as the `Sender` posts to it; a slug with no entry is a fault of the caller's. */
  def apply(slug: String): Brand =
    val e = entry(slug).getOrElse(throw java.util.NoSuchElementException(s"no brand entry for '$slug'"))
    Brand(e.slug, URI(e.baseAddress), e.testBrand, e.providerWaitMillis, e.keyRefreshBoundSeconds, ca(e), None, e.site)

  def known(slug: String): Boolean             = entry(slug).isDefined
  def testBrand(slug: String): Boolean         = entry(slug).exists(_.testBrand)
  def answerWait(slug: String): FiniteDuration =
    entry(slug).fold(BrandEntry.DefaultWaitMillis)(_.providerWaitMillis).millis
  def keyRefreshBound(slug: String): FiniteDuration =
    entry(slug).fold(BrandEntry.DefaultRefreshSeconds)(_.keyRefreshBoundSeconds).seconds
  def slugs: Vector[String] = entries.get.keys.toVector.sorted

  private def ca(e: BrandEntry): Option[String] = e.caPem.flatMap { pem =>
    if pem.startsWith("env:") then env.get(pem.stripPrefix("env:")).map(_.trim).filter(_.nonEmpty) else Some(pem)
  }

object BrandCache:
  /** A cache whose `refresh` failed at start is empty; the next write fills it (logged by `Main`). */
  def started(client: ComponentClient, env: Map[String, String] = sys.env): (BrandCache, Option[Throwable]) =
    val cache = BrandCache(env)
    (cache, Try(cache.refresh(client)).failed.toOption)

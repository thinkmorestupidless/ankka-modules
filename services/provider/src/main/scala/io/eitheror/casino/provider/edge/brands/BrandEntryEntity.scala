package io.eitheror.casino.provider.edge.brands

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.sdk.*

/**
 * One brand the house provider serves (spec 026 R3, `contracts/brand-entry.md`), as an operator
 * wrote it. The private key is never part of it: the brand holds that, and the entry holds only the
 * public key the brand's operator calls are verified under.
 */
final case class BrandEntry(
    slug: String,
    baseAddress: String,
    site: String,
    operatorPublicKey: String,
    language: String,
    testBrand: Boolean,
    games: Vector[String],
    keyDocument: Boolean,
    providerWaitMillis: Long,
    keyRefreshBoundSeconds: Long,
    caPem: Option[String],
    realMoney: Boolean
):
  /** An entry that states real money and names the key document refuses every launch (FR-020). */
  def launchesRefused: Boolean = realMoney && keyDocument

  /** Whether the brand may run `code`: an empty list is every registered game (FR-017). */
  def runs(code: String): Boolean = games.isEmpty || games.contains(code)

object BrandEntry:
  val Languages             = Set("es", "pt")
  val DefaultWaitMillis     = 5000L
  val DefaultRefreshSeconds = 60L

  /** The rules an entry is held to beyond its shape, each refusal naming the field. */
  def problems(e: BrandEntry): Vector[String] = Vector(
    Option.when(!Languages.contains(e.language))(s"language '${e.language}' must be es or pt"),
    Option.when(e.keyDocument && !e.testBrand)("keyDocument may be true only for a test brand"),
    Option.when(e.keyDocument && e.realMoney)("keyDocument may not be named by a brand that takes real money: it holds the key itself"),
    Option.when(e.providerWaitMillis <= 0)("providerWaitMillis must be more than 0"),
    Option.when(e.keyRefreshBoundSeconds <= 0)("keyRefreshBoundSeconds must be more than 0"),
    Option.when(e.site.isEmpty)("site is required"),
    Option.when(e.operatorPublicKey.isEmpty)("operatorPublicKey is required")
  ).flatten

/** A brand's entry, id the slug. Writing replaces it; the routes check `site` is unique (US3). */
final class BrandEntryEntity(context: KeyValueEntityContext) extends KeyValueEntity[Option[BrandEntry]]:

  def emptyState: Option[BrandEntry] = None

  def put(entry: BrandEntry): Effect[BrandEntry] =
    if entry.slug != context.entityId then effects.error(s"slug '${entry.slug}' is not this entry's (${context.entityId})", ErrorCode.BadRequest)
    else
      BrandEntry.problems(entry).headOption match
        case Some(problem) => effects.error(problem, ErrorCode.BadRequest)
        case None          => effects.updateState(Some(entry)).thenReply(_ => entry)

  def remove: Effect[Done] =
    if currentState.isEmpty then effects.reply(Done) else effects.updateState(None).thenReply(_ => Done)

  def get: ReadOnlyEffect[BrandEntry] =
    currentState.fold(effects.error[BrandEntry]("brand not found", ErrorCode.NotFound))(effects.reply(_))

object BrandEntryEntity
    extends KeyValueEntity.Companion[BrandEntryEntity, Option[BrandEntry]](
      componentId = ComponentId("brand-entry"),
      stateSerializer = Codecs.serializer[Option[BrandEntry]]("brand-entry-state")
    ):
  given Serializer[BrandEntry] = Codecs.serializer[BrandEntry]("brand-entry")

  def create(context: KeyValueEntityContext) = new BrandEntryEntity(context)

  val put    = command("put")(_.put)
  val remove = command("remove")(_.remove)
  val get    = query("get")(_.get)

/** The slugs of every entry (id `index`), so `Brands.slugs` and the cache's refresh need no scan. */
final class BrandIndex extends KeyValueEntity[Vector[String]]:

  def emptyState: Vector[String] = Vector.empty

  def add(slug: String): Effect[Vector[String]] =
    if currentState.contains(slug) then effects.reply(currentState)
    else
      val next = (currentState :+ slug).sorted
      effects.updateState(next).thenReply(_ => next)

  def drop(slug: String): Effect[Vector[String]] =
    if !currentState.contains(slug) then effects.reply(currentState)
    else
      val next = currentState.filterNot(_ == slug)
      effects.updateState(next).thenReply(_ => next)

  def slugs: ReadOnlyEffect[Vector[String]] = effects.reply(currentState)

object BrandIndex
    extends KeyValueEntity.Companion[BrandIndex, Vector[String]](
      componentId = ComponentId("brand-index"),
      stateSerializer = Codecs.serializer[Vector[String]]("brand-index")
    ):
  val Id = "index"

  def create(context: KeyValueEntityContext) = new BrandIndex

  val add   = command("add")(_.add)
  val drop  = command("drop")(_.drop)
  val slugs = query("slugs")(_.slugs)

package io.eitheror.casino.attribution.tag

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.sdk.ComponentClient
import io.eitheror.casino.attribution.{Reasons, TagOutcome}
import io.eitheror.casino.attribution.networks.*
import io.eitheror.casino.attribution.settings.{BrandSettings, SettingsEntity}

import java.time.{Duration, Instant}
import scala.util.Try

/**
 * The verdict on a presented tag (research R6, R16) from what the brand holds now: the network, its
 * cached documents, and for a bare UUID the programme's legacy tag document and the transition. Nothing
 * here fetches; `TagCheck.live` reads the entities and, at the click only, fetches a key document whose
 * `kid` the cache lacks.
 */
object TagCheck:

  /** What a verdict reads, as functions so a suite can hold them still. */
  final case class Sources(
      network: String => Option[NetworkState],
      documents: String => DocumentsState,
      programme: () => Option[String],
      settings: () => BrandSettings,
      networks: () => Vector[NetworkState] = () => Vector.empty,
      now: () => Instant = () => Instant.now()
  )

  /** The tag's verdict, with the window counted back from `registeredAt` when it is a registration's. */
  def decide(tag: String, sources: Sources, registeredAt: Option[Instant]): TagOutcome =
    val value = tag.trim
    if Tag.isLegacy(value) then legacy(value, sources)
    else
      Tag.parse(value) match
        case Left(reason) => TagOutcome.Refused(reason)
        case Right(parsed) =>
          val net  = parsed.named.network
          val node = sources.network(net).filter(_.live)
          val docs = if node.isDefined then sources.documents(net) else DocumentsState()
          val revoked = docs.revocations
          Tag.verify(parsed, Tag.Context(
            networkKnown = node.isDefined,
            keys = Cache.keys(docs, sources.now()),
            revokedLinks = revoked.fold(Set.empty[String])(_.links),
            revokedAffiliates = revoked.fold(Set.empty[String])(_.affiliates),
            window = registeredAt.map(at => at -> Duration.ofDays(sources.settings().attributionWindowDays.toLong))
          ))

  private def legacy(uuid: String, sources: Sources): TagOutcome =
    sources.programme() match
      case None => TagOutcome.Refused(Reasons.LegacyUnknown)
      case Some(programme) =>
        val doc = sources.documents(programme).legacy.map(_.tags)
        Tag.resolveLegacy(uuid, doc, programme, sources.settings().transitionOver)

  /** The kid a token names, when it parses: what a click's on-demand fetch is for. */
  def kidOf(tag: String): Option[(String, String)] =
    Tag.parse(tag.trim).toOption.map(p => p.named.network -> p.kid)

  /** The entities read through `components`; `networks` lists every network (the `Networks` view). */
  def live(components: ComponentClient, networks: () => Vector[NetworkState],
      now: () => Instant = () => io.eitheror.casino.attribution.Wiring.clock.get().instant()): Sources =
    Sources(
      network = id => Try(components.forKeyValueEntity(EntityId(id)).call(Network.get).invoke()).toOption.filter(_.exists),
      documents = id => Try(components.forKeyValueEntity(EntityId(id)).call(NetworkDocuments.get).invoke()).getOrElse(DocumentsState()),
      programme = () => networks().filter(_.live).find(_.addresses.exists(_.legacyTagDocument.isDefined)).map(_.id),
      settings = () => components.forKeyValueEntity(EntityId(SettingsEntity.Id)).call(SettingsEntity.get).invoke(),
      networks = networks,
      now = now
    )

  /**
   * At the click only (FR-023 allows it there, never at registration): the key document fetched when the
   * cache cannot verify the token's kid (`Cache.fetchFor`). Answers whether a document was written.
   */
  def fetchKeysFor(tag: String, components: ComponentClient, fetch: DocumentFetch, now: Instant): Boolean =
    kidOf(tag).exists((network, kid) => Cache.fetchFor(network, kid, components, fetch, now))

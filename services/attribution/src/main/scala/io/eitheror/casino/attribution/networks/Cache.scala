package io.eitheror.casino.attribution.networks

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.sdk.ComponentClient

import java.time.Instant
import scala.util.Try

/**
 * What a verdict may trust of a network's cached documents (research R3, FR-023). A key document is used
 * until it expires and not after: a network whose document cannot be fetched keeps its tags verifying for
 * the time it said, then they are refused `key_unreachable`. The revocation list's last good copy is kept,
 * since a stale list can only refuse more. Only the click fetches on demand (`fetchFor`); registration
 * reads the cache and never fetches.
 */
object Cache:

  /** The signing keys held now, by kid: none when no key document is cached or it has expired. */
  def keys(docs: DocumentsState, now: Instant): Option[Map[String, String]] =
    if docs.fresh(Documents.Kind.Keys, now) then docs.keys.map(_.keys) else None

  /** Whether a network's key document is held and current, as the status route reports it. */
  def reachable(docs: DocumentsState, now: Instant): Boolean = docs.fresh(Documents.Kind.Keys, now)

  /**
   * At the click: when the network is live and the cache lacks the kid, or holds no current key document,
   * fetch the key document now and cache it for the time it names. Answers whether one was written; a
   * failed fetch is recorded on the network's documents and the cache is left as it was.
   */
  def fetchFor(network: String, kid: String, components: ComponentClient, fetch: DocumentFetch, now: Instant): Boolean =
    val node = Try(components.forKeyValueEntity(EntityId(network)).call(Network.get).invoke()).toOption.filter(_.live)
    node.flatMap(_.addresses).exists { addresses =>
      val documents = components.forKeyValueEntity(EntityId(network))
      val held      = documents.call(NetworkDocuments.get).invoke()
      if keys(held, now).exists(_.contains(kid)) then false
      else
        val outcome = fetch.fetch(addresses.keyDocument).flatMap {
          case (200, body) => Documents.cacheSeconds(Documents.Kind.Keys, body).map(body -> _)
          case (status, _) => Left(s"the document answered $status")
        }
        outcome match
          case Right((body, seconds)) =>
            Try(documents.call(NetworkDocuments.put).invoke(PutDocument(Documents.Kind.Keys, body, now, now.plusSeconds(seconds)))).isSuccess
          case Left(error) =>
            documents.call(NetworkDocuments.failed).invoke(DocumentFailed(Documents.Kind.Keys, error, now)): Unit
            false
    }

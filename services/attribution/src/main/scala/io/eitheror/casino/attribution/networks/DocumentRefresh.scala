package io.eitheror.casino.attribution.networks

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.attribution.Wiring

import java.time.{Clock, Instant}

/**
 * The documents' refresh (research R3): for every live network, each document whose cache has expired, or
 * expires before the run after next (`DocumentRefresh.Ahead`), is fetched and written to `NetworkDocuments`,
 * cached for the seconds it names; a failure is recorded and the last good body kept. Answers what it
 * fetched, as `(network, kind, ok)`. Fetching ahead matters: a registration reads the cache and never
 * fetches, so a key document left to expire before the next run refuses a valid tag `key_unreachable`.
 */
final class DocumentRefresher(components: ComponentClient, networks: () => Vector[NetworkState], fetch: DocumentFetch, now: () => Instant):
  def run(): Vector[(String, String, Boolean)] =
    networks().filter(_.live).flatMap { n =>
      val docs  = components.forKeyValueEntity(EntityId(n.id))
      val held  = docs.call(NetworkDocuments.get).invoke()
      val at    = now()
      Documents.Kind.all.flatMap(kind => n.addresses.flatMap(_.of(kind)).map(kind -> _)).collect {
        case (kind, url) if !held.fresh(kind, at.plus(DocumentRefresh.Ahead)) =>
          val outcome = fetch.fetch(url).flatMap {
            case (200, body) => Documents.cacheSeconds(kind, body).map(s => body -> s)
            case (status, _) => Left(s"the document answered $status")
          }
          outcome match
            case Right((body, seconds)) =>
              docs.call(NetworkDocuments.put).invoke(PutDocument(kind, body, at, at.plusSeconds(seconds))): Unit
              (n.id, kind, true)
            case Left(error) =>
              docs.call(NetworkDocuments.failed).invoke(DocumentFailed(kind, error, at)): Unit
              (n.id, kind, false)
      }
    }

/** The recurring timer `refresh-documents`, every minute: always done. */
final class DocumentRefresh(context: TimedActionContext) extends TimedAction:
  def run: Effect =
    DocumentRefresh.refresher(context.componentClient, context.clock).run(): Unit
    effects.done()

object DocumentRefresh extends TimedAction.Companion[DocumentRefresh](ComponentId("document-refresh")):
  val TimerName = "refresh-documents"

  /** Twice the timer's period: a document is fetched again while it is still current. */
  val Ahead: java.time.Duration = java.time.Duration.ofMinutes(2)

  def refresher(components: ComponentClient, clock: Clock): DocumentRefresher =
    DocumentRefresher(components, () => Wiring.networks.get()(), Wiring.fetch.get(), () => clock.instant())

  def create(context: TimedActionContext) = new DocumentRefresh(context)
  val refreshDocuments = handler("refresh-documents")(_.run)

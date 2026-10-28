package io.eitheror.casino.attribution.networks

import com.thinkmorestupidless.ankka.core.{ComponentId, ErrorCode, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.attribution.AttributionCodecs

import java.time.Instant

/**
 * One cached document (research R3). The body is kept as fetched and parsed on read, so a failed refresh
 * keeps the last good body and records only the error.
 */
final case class CachedDocument(
    fetchedAt: Option[Instant] = None,
    expiresAt: Option[Instant] = None,
    body: Option[String] = None,
    lastError: Option[String] = None,
    lastErrorAt: Option[Instant] = None
)

/** The cache of a network's four documents. Public documents, not secrets. */
final case class DocumentsState(documents: Map[String, CachedDocument] = Map.empty):
  def of(kind: String): Option[CachedDocument] = documents.get(kind)

  /** Whether a document may be used now: a body is held and it has not expired. */
  def fresh(kind: String, now: Instant): Boolean =
    of(kind).exists(d => d.body.isDefined && d.expiresAt.exists(now.isBefore))

  private def body(kind: String): Option[String] = of(kind).flatMap(_.body)

  def keys: Option[Documents.KeyDocument]               = body(Documents.Kind.Keys).flatMap(Documents.keyDocument(_).toOption)
  def revocations: Option[Documents.RevocationList]     = body(Documents.Kind.Revocations).flatMap(Documents.revocationList(_).toOption)
  def legacy: Option[Documents.LegacyTagDocument]       = body(Documents.Kind.Legacy).flatMap(Documents.legacyTagDocument(_).toOption)
  def codes: Option[Documents.CodeDocument]             = body(Documents.Kind.Codes).flatMap(Documents.codeDocument(_).toOption)

final case class PutDocument(kind: String, body: String, fetchedAt: Instant, expiresAt: Instant)
final case class DocumentFailed(kind: String, error: String, at: Instant)
final case class Freshness(kind: String, now: Instant)

object NetworkDocumentsCodecs:
  given Serializer[DocumentsState] = AttributionCodecs.serializer[DocumentsState]("network-documents")
  given Serializer[PutDocument]    = AttributionCodecs.serializer[PutDocument]("put-document")
  given Serializer[DocumentFailed] = AttributionCodecs.serializer[DocumentFailed]("document-failed")
  given Serializer[Freshness]      = AttributionCodecs.serializer[Freshness]("freshness")

import NetworkDocumentsCodecs.given

final class NetworkDocuments(@scala.annotation.unused context: KeyValueEntityContext) extends KeyValueEntity[DocumentsState]:
  def emptyState: DocumentsState = DocumentsState()

  private def parses(kind: String, body: String): Either[String, Unit] = kind match
    case Documents.Kind.Keys        => Documents.keyDocument(body).map(_ => ())
    case Documents.Kind.Revocations => Documents.revocationList(body).map(_ => ())
    case Documents.Kind.Legacy      => Documents.legacyTagDocument(body).map(_ => ())
    case Documents.Kind.Codes       => Documents.codeDocument(body).map(_ => ())
    case other                      => Left(s"no document of kind $other")

  /**
   * A fetched document, held only when it parses: a bad body is a failure that keeps the old one. A copy
   * fetched before the one held is ignored: the minute's refresh and a click's fetch can finish out of
   * order, and the older copy may still list a key the network has withdrawn.
   */
  def put(cmd: PutDocument): Effect[DocumentsState] =
    parses(cmd.kind, cmd.body) match
      case Left(e) => effects.error(e, ErrorCode.BadRequest)
      case Right(_) if currentState.of(cmd.kind).flatMap(_.fetchedAt).exists(_.isAfter(cmd.fetchedAt)) =>
        effects.reply(currentState)
      case Right(_) =>
        val doc = CachedDocument(Some(cmd.fetchedAt), Some(cmd.expiresAt), Some(cmd.body))
        effects.updateState(currentState.copy(documents = currentState.documents.updated(cmd.kind, doc))).thenReplyState

  /** A fetch that failed: the error recorded, the last good body and its expiry kept. */
  def failed(cmd: DocumentFailed): Effect[DocumentsState] =
    if !Documents.Kind.all.contains(cmd.kind) then effects.error(s"no document of kind ${cmd.kind}", ErrorCode.BadRequest)
    else
      val doc = currentState.of(cmd.kind).getOrElse(CachedDocument()).copy(lastError = Some(cmd.error), lastErrorAt = Some(cmd.at))
      effects.updateState(currentState.copy(documents = currentState.documents.updated(cmd.kind, doc))).thenReplyState

  def fresh(q: Freshness): ReadOnlyEffect[Boolean] = effects.reply(currentState.fresh(q.kind, q.now))

  def get: ReadOnlyEffect[DocumentsState] = effects.reply(currentState)

object NetworkDocuments extends KeyValueEntity.Companion[NetworkDocuments, DocumentsState](
      componentId = ComponentId("network-documents"),
      stateSerializer = NetworkDocumentsCodecs.given_Serializer_DocumentsState
    ):
  import com.thinkmorestupidless.ankka.core.Serializers.given

  def create(context: KeyValueEntityContext) = new NetworkDocuments(context)

  val put    = command("put")(_.put)
  val failed = command("failed")(_.failed)
  val fresh  = query("fresh")(_.fresh)
  val get    = query("get")(_.get)

package io.eitheror.casino.rewards.codes

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.thinkmorestupidless.ankka.core.{ComponentId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.rewards.RewardsCodecs
import io.eitheror.casino.rewards.clients.ProgrammeDocument
import io.eitheror.casino.rewards.offers.{Codes, ProgrammeEntry}

import java.time.{Duration, Instant}

/**
 * The last copy of the programme's code document for the brand (research R13; FR-021): each code, normalised as
 * `Codes.normalise`, to the offer it names and its tag; when it was fetched, and the document's version. No player is
 * named: the document is the programme's, public.
 */
final case class CodeDocumentState(entries: Map[String, ProgrammeEntry] = Map.empty, fetchedAt: Option[Instant] = None,
    version: Long = 0L):
  /** The codes, normalised, against which a brand code must be unique (FR-003). */
  def codes: Set[String] = entries.keySet

  /** Stale past `after` from its fetch, or never fetched (FR-021). */
  def stale(now: Instant, after: Duration): Boolean = fetchedAt.forall(at => now.isAfter(at.plus(after)))

object CodeDocumentState:
  given JsonValueCodec[CodeDocumentState] = RewardsCodecs.stored[CodeDocumentState]

  /** The document as this service keeps it: a code twice keeps its first entry. */
  def of(d: ProgrammeDocument, at: Instant): CodeDocumentState =
    val entries = d.codes.foldLeft(Map.empty[String, ProgrammeEntry]) { (acc, c) =>
      val n = Codes.normalise(c.code)
      if n.isEmpty || acc.contains(n) then acc else acc.updated(n, ProgrammeEntry(c.offerId, Option(c.tag).filter(_.nonEmpty)))
    }
    CodeDocumentState(entries, Some(at), d.version)

/** A document fetched at `at`, to keep as the last copy. */
final case class Fetched(document: ProgrammeDocument, at: Instant)

/**
 * The programme's code document for the brand (key value, id = slug; data-model.md `CodeDocument`): the copy
 * `codes-<slug>` fetched last. A fetch that fails writes nothing, so the last copy serves (FR-021).
 */
final class CodeDocument(@scala.annotation.unused context: KeyValueEntityContext) extends KeyValueEntity[CodeDocumentState]:
  def emptyState: CodeDocumentState = CodeDocumentState()

  def fetched(c: Fetched): Effect[CodeDocumentState] =
    val next = CodeDocumentState.of(c.document, c.at)
    effects.updateState(next).thenReply(identity)

  def get: ReadOnlyEffect[CodeDocumentState] = effects.reply(currentState)

object CodeDocument
    extends KeyValueEntity.Companion[CodeDocument, CodeDocumentState](
      componentId = ComponentId("code-document"),
      stateSerializer = RewardsCodecs.serializer[CodeDocumentState]("code-document-state")
    ):
  given Serializer[CodeDocumentState] = RewardsCodecs.serializer[CodeDocumentState]("code-document-reply")
  given Serializer[Fetched]           = RewardsCodecs.serializer[Fetched]("code-document-fetched")

  def create(context: KeyValueEntityContext) = new CodeDocument(context)

  val fetched = command("fetched")(_.fetched)
  val get     = query("get")(_.get)

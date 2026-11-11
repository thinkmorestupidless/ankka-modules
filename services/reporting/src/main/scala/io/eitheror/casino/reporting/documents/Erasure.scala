package io.eitheror.casino.reporting.documents

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.thinkmorestupidless.ankka.runtime.ViewClient
import com.thinkmorestupidless.ankka.sdk.{Bucket, ComponentClient, ErasureHandler, ErasureReport, ObjectRules}
import io.eitheror.casino.reporting.ReportingRuntime
import io.eitheror.casino.reporting.api.Wire
import io.eitheror.casino.reporting.intake.Personals

import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant

/** `POST /erasure/players/{playerId}`'s answer: the service, the subject, and the kept documents the erasure ended. */
final case class Erased(service: String, subject: String, documents: Int)

object Erased:
  given JsonValueCodec[Erased] = Wire.answer[Erased]

/**
 * A player's erasure (FR-021, research R10): every object under the subject's prefix deleted (a dispute report of theirs
 * among them), and every kept document that names them, by its own subject or in its `.players` beside it (an export
 * listing them), or in a page of a job still running, deleted and its record marked `expired` by `erasure`. The records
 * stay, naming the player by id only. Run again it ends nothing more: a record already expired is left as it is.
 *
 * The documents are found in the bucket, not in the `Documents` view, so one readied a moment ago is not missed: the
 * objects under the subject's prefix, and every `.players` (a document's or a job page's) naming the player, each
 * object's name giving its document's reference. The view's kept records are read too, for a job that has kept no page.
 */
final class Erasing(components: ComponentClient, bucket: Bucket, kept: () => Vector[DocumentState], now: () => Instant):
  def run(player: String): Erased = counted(player)._1

  /**
   * `run`'s answer, and how many objects it deleted: those under the subject's prefix (a dispute report of theirs, with
   * its sidecar), and of each document it ended outside the prefix, the object, its sidecar and any page still kept.
   * A document under the prefix is counted once, with the prefix.
   */
  def counted(player: String): (Erased, Long) =
    val subject = Personals.subject(player)
    val prefix  = ObjectRules.subjectPrefix(subject)
    val under   = bucket.list(prefix).size.toLong
    val own     = bucket.list(prefix).map(_.name).flatMap(Erasing.referenceOf).toVector
    bucket.erase(subject): Unit
    val listing = bucket.list(Document.Root).map(_.name).filter(n => n.endsWith(".players") && listed(player, n))
      .flatMap(Erasing.referenceOf).toVector
    val viewed  = kept().filter(_.subject.contains(player)).map(_.reference)
    var outside = 0L
    val ended = (own ++ listing ++ viewed).distinct.map(r => Keeping.document(components, r).call(Document.get).invoke())
      .filter(d => d.recorded && d.kept).map { d =>
        outside += d.objectName.filterNot(_.startsWith(prefix)).fold(0L)(objectsOf)
        Keeping.expire(components, bucket, d, Document.Erasure, now())
      }
    (Erased("reporting", subject.value, ended.count(_.endedBy.contains(Document.Erasure))), under + outside)

  /** The objects a document's drop deletes that are there: the document, its sidecar, its pages. */
  private def objectsOf(objectName: String): Long =
    Vector(objectName, Document.sidecar(objectName)).count(bucket.stat(_).isDefined).toLong +
      bucket.list(s"$objectName.parts/").size.toLong

  private def listed(player: String, object_ : String): Boolean =
    bucket.getBytes(object_).exists((_, b) => String(b, UTF_8).split('\n').contains(player))

object Erasing:
  private val Named = """(?:^|/)reports/[^/]+/([^/.]+)\.""".r

  /** The reference of the document an object belongs to (`[subjects/<s>/]reports/<kind>/<reference>.<…>`), if it is one. */
  def referenceOf(objectName: String): Option[String] = Named.findFirstMatchIn(objectName).map(_.group(1))

object Erasure:
  /** The route and the installation's erasure do the same; a subject that is not a player's is not this service's. */
  def erase(components: ComponentClient, bucket: Bucket, views: ViewClient, player: String): Erased =
    Erasing(components, bucket, () => Documents.keptNow(views), () => ReportingRuntime.now()).run(player)

  /**
   * The service's part of an erasure filed at the installation (feature 042), for `player/<id>`. It reports the objects
   * it deleted (`Erasing.counted`): the subject's own, and each kept document it ended outside them, with its sidecar.
   */
  val handler: ErasureHandler = ErasureHandler { ctx => subject =>
    val name = subject.value
    if !name.startsWith("player/") || name.length <= "player/".length then ErasureReport(0)
    else
      val bucket = ctx.storage.default
      val (_, objects) = Erasing(ctx.client, bucket, () => Documents.keptNow(ReportingRuntime.views), () => ReportingRuntime.now())
        .counted(name.stripPrefix("player/"))
      ErasureReport(objects)
  }

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
 */
final class Erasing(components: ComponentClient, bucket: Bucket, kept: () => Vector[DocumentState], now: () => Instant):
  def run(player: String): Erased =
    val subject = Personals.subject(player)
    bucket.erase(subject): Unit
    val ended = kept().filter(names(player)).map(d => Keeping.expire(components, bucket, d, Document.Erasure, now()))
    Erased("reporting", subject.value, ended.count(_.endedBy.contains(Document.Erasure)))

  /** Whether the document names the player: as its subject, in its `.players`, or in a running job's pages. */
  private def names(player: String)(d: DocumentState): Boolean =
    d.subject.contains(player) || d.objectName.exists { name =>
      def listed(object_ : String) = bucket.getBytes(object_).exists((_, b) => String(b, UTF_8).split('\n').contains(player))
      listed(Document.sidecar(name)) ||
        bucket.list(name + ".parts/").map(_.name).filter(_.endsWith(".players")).exists(listed)
    }

object Erasure:
  /** The route and the installation's erasure do the same; a subject that is not a player's is not this service's. */
  def erase(components: ComponentClient, bucket: Bucket, views: ViewClient, player: String): Erased =
    Erasing(components, bucket, () => Documents.keptNow(views), () => ReportingRuntime.now()).run(player)

  /**
   * The service's part of an erasure filed at the installation (feature 042), for `player/<id>`. It reports the objects
   * it deleted: the subject's own and each kept document it ended, with its sidecar.
   */
  val handler: ErasureHandler = ErasureHandler { ctx => subject =>
    val name = subject.value
    if !name.startsWith("player/") || name.length <= "player/".length then ErasureReport(0)
    else
      val bucket = ctx.storage.default
      val own    = bucket.list(ObjectRules.subjectPrefix(subject)).size.toLong
      val ended  = erase(ctx.client, bucket, ReportingRuntime.views, name.stripPrefix("player/"))
      ErasureReport(own + ended.documents)
  }

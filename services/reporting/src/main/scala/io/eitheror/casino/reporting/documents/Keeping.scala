package io.eitheror.casino.reporting.documents

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.sdk.{Bucket, ComponentClient}

import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant

/**
 * How a document's object is kept and let go (research R9), for the request that renders one, its job, the sweep and an
 * erasure alike: the object under its name, the player ids it names in its sidecar, the record told.
 */
object Keeping:
  /**
   * The parts of `objectName`, in order, streamed into it: each part's stream opened only when the one before it has been
   * read, its length declared from what the bucket says the parts hold, so the document is never held whole.
   */
  def joinParts(bucket: Bucket, objectName: String, pages: Int, contentType: String): Unit =
    val names  = (0 until pages).map(Document.part(objectName, _))
    val length = names.flatMap(bucket.stat).map(_.length).sum
    val parts  = names.iterator.map(n => bucket.get(n).fold[java.io.InputStream](java.io.ByteArrayInputStream(Array.emptyByteArray))(_.body))
    val body   = java.io.SequenceInputStream(scala.jdk.CollectionConverters.IteratorHasAsJava(parts).asJavaEnumeration)
    try bucket.put(objectName, body, length, contentType)
    finally body.close()

  val PlayersType = "text/plain; charset=UTF-8"

  def document(components: ComponentClient, reference: String) = components.forEventSourcedEntity(EntityId(reference))

  /** The ids, one a line; none written for none. */
  def putNamed(bucket: Bucket, objectName: String, named: Vector[String]): Unit =
    if named.nonEmpty then bucket.putBytes(Document.sidecar(objectName), named.distinct.mkString("\n").getBytes(UTF_8), PlayersType)

  def namedIn(bucket: Bucket, objectName: String): Vector[String] =
    bucket.getBytes(Document.sidecar(objectName)).toVector.flatMap((_, b) => String(b, UTF_8).split('\n').toVector.filter(_.nonEmpty))

  /** A document rendered in the request: kept, its sidecar beside it, recorded ready for `lifetimeHours`. */
  def keep(components: ComponentClient, bucket: Bucket, record: DocumentState, body: Array[Byte], named: Vector[String],
      lifetimeHours: Int): DocumentState =
    val name = record.objectName.getOrElse(throw IllegalArgumentException("a kept document has an object name"))
    bucket.putBytes(name, body, record.contentType.getOrElse("application/octet-stream"))
    putNamed(bucket, name, named)
    val at = record.readyAt.orElse(record.generatedAt).getOrElse(Instant.now())
    document(components, record.reference).call(Document.ready)
      .invoke(Kept(record.copy(readyAt = Some(at)), at.plusSeconds(lifetimeHours.toLong * 3600)))

  /** A serving, recorded for the audit (FR-026). */
  def served(components: ComponentClient, reference: String, by: String, at: Instant): Unit =
    document(components, reference).call(Document.served).invoke(Serve(by, at)): Unit

  /** The object and its sidecar deleted, and a job's pages if any were left. Deleting nothing is not an error. */
  def drop(bucket: Bucket, objectName: String): Unit =
    bucket.delete(objectName)
    bucket.delete(Document.sidecar(objectName))
    dropParts(bucket, objectName)

  def dropParts(bucket: Bucket, objectName: String): Unit =
    bucket.list(s"$objectName.parts/").map(_.name).toVector.foreach(bucket.delete)

  /** A kept document let go: its object deleted, its record marked expired by `endedBy`; the record stays (FR-021). */
  def expire(components: ComponentClient, bucket: Bucket, d: DocumentState, endedBy: String, at: Instant): DocumentState =
    d.objectName.foreach(drop(bucket, _))
    document(components, d.reference).call(Document.expire).invoke(Expire(at, endedBy))

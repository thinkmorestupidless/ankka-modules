package io.eitheror.casino.reporting.intake

import com.thinkmorestupidless.ankka.core.Serializer
import com.thinkmorestupidless.ankka.sdk.{ChangeSource, StartFrom, TopicOptions}
import io.eitheror.casino.contract.facts.{Fact, Topic}
import org.slf4j.LoggerFactory

import scala.util.Try

/**
 * A topic's serializer answering none for what it cannot read, as attribution's: a family added to a topic later
 * must not stop a consumer here. The consumer logs the subject, never the payload, and skips it. Every topic is read
 * from the earliest offset (research R3), so a new generation's consumers replay it whole (R8).
 */
object Lenient:
  private val log = LoggerFactory.getLogger("io.eitheror.casino.reporting.intake")

  def serializer[F](facts: Serializer[F]): Serializer[Option[F]] = new Serializer[Option[F]]:
    def manifest: String                         = facts.manifest
    def toBytes(value: Option[F]): Array[Byte]   = value.fold(Array.emptyByteArray)(facts.toBytes)
    def fromBytes(bytes: Array[Byte]): Option[F] = Try(facts.fromBytes(bytes)).toOption

  def from[F <: Fact](topic: Topic[F]): ChangeSource[Option[F]] =
    ChangeSource.fromTopic(topic.name, serializer(topic.serializer), StartFrom.Earliest,
      TopicOptions(Some(topic.contract), None, false))

  def unreadable(topic: String, subject: String): Unit =
    log.warn("a {} fact this service cannot read was skipped (subject {})", topic, subject)

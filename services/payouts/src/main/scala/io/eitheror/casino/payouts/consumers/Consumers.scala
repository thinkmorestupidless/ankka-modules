package io.eitheror.casino.payouts.consumers

import com.thinkmorestupidless.ankka.core.Serializer
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.contract.facts.*
import org.slf4j.LoggerFactory

import scala.util.Try

/**
 * What this service reads of other services' facts, as kyc reads them: each topic from `StartFrom.Latest`
 * through a lenient serializer, so a fact it cannot read is logged by its subject, never its payload, and skipped.
 */
object Read:
  private val log = LoggerFactory.getLogger("io.eitheror.casino.payouts.consumers")

  def lenient[F <: Fact](topic: Topic[F]): Serializer[Option[F]] = new Serializer[Option[F]]:
    private val facts = topic.serializer
    def manifest: String                         = facts.manifest
    def toBytes(value: Option[F]): Array[Byte]   = value.fold(Array.emptyByteArray)(facts.toBytes)
    def fromBytes(bytes: Array[Byte]): Option[F] = Try(facts.fromBytes(bytes)).toOption

  def from[F <: Fact](topic: Topic[F]): ChangeSource[Option[F]] =
    ChangeSource.fromTopic(topic.name, lenient(topic), StartFrom.Latest, TopicOptions(Some(topic.contract), None, false))

  def unreadable(topic: String, subject: String): Unit =
    log.warn("a {} fact this service cannot read was skipped (subject {})", topic, subject)

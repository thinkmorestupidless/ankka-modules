package io.eitheror.casino.publicapi.records

import com.thinkmorestupidless.ankka.core.Serializer
import com.thinkmorestupidless.ankka.sdk.{ChangeSource, StartFrom, TopicOptions}
import io.eitheror.casino.contract.facts.{Fact, Topic}

import scala.util.Try

/** A topic read from the earliest offset, a fact this service cannot read answered as none and skipped (reporting's). */
object Lenient:
  def serializer[F](facts: Serializer[F]): Serializer[Option[F]] = new Serializer[Option[F]]:
    def manifest: String                         = facts.manifest
    def toBytes(value: Option[F]): Array[Byte]   = value.fold(Array.emptyByteArray)(facts.toBytes)
    def fromBytes(bytes: Array[Byte]): Option[F] = Try(facts.fromBytes(bytes)).toOption

  def from[F <: Fact](topic: Topic[F]): ChangeSource[Option[F]] =
    ChangeSource.fromTopic(topic.name, serializer(topic.serializer), StartFrom.Earliest, TopicOptions(Some(topic.contract), None, false))

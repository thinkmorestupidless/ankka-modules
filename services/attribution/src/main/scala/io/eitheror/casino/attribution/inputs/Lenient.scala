package io.eitheror.casino.attribution.inputs

import com.thinkmorestupidless.ankka.core.Serializer

import scala.util.Try

/**
 * A topic's serializer answering none for what it cannot read, as `ingest`'s `LenientFacts`: a family
 * added to a topic later must not stop a consumer here. The consumer logs and skips it.
 */
object Lenient:
  def serializer[F](facts: Serializer[F]): Serializer[Option[F]] = new Serializer[Option[F]]:
    def manifest: String = facts.manifest
    def toBytes(value: Option[F]): Array[Byte] = value.fold(Array.emptyByteArray)(facts.toBytes)
    def fromBytes(bytes: Array[Byte]): Option[F] = Try(facts.fromBytes(bytes)).toOption

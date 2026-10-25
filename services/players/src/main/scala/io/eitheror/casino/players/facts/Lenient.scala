package io.eitheror.casino.players.facts

import com.thinkmorestupidless.ankka.core.Serializer
import io.eitheror.casino.contract.facts.{Fact, Topic}

import scala.util.Try

/**
 * A topic's serializer answering none for a fact it cannot read (a family newer than this service's
 * contract), so a reader logs and skips it rather than stopping on it, as ingest's `LenientFacts`.
 */
object Lenient:
  def of[F <: Fact](topic: Topic[F]): Serializer[Option[F]] = new Serializer[Option[F]]:
    private val facts = topic.serializer
    def manifest: String                          = facts.manifest
    def toBytes(value: Option[F]): Array[Byte]    = value.fold(Array.emptyByteArray)(facts.toBytes)
    def fromBytes(bytes: Array[Byte]): Option[F]  = Try(facts.fromBytes(bytes)).toOption

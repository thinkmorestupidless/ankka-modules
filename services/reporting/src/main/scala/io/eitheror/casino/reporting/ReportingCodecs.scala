package io.eitheror.casino.reporting

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonReader, JsonValueCodec, JsonWriter}
import com.github.plokhotnyuk.jsoniter_scala.macros.{CodecMakerConfig, JsonCodecMaker}
import com.thinkmorestupidless.ankka.core.Serializer

import java.time.Instant

/**
 * The codec settings for this service's stored types, in one place, as engagement's: absent, empty and default
 * fields left out, `Instant`s as ISO-8601, a sum type's case named under `type`. Every stored type has a name,
 * which is its manifest, `reporting.<name>`.
 */
object ReportingCodecs:

  inline def stored[A]: JsonValueCodec[A] =
    JsonCodecMaker.make[A](
      CodecMakerConfig
        .withDiscriminatorFieldName(Some("type"))
        .withRequireDiscriminatorFirst(false)
        .withTransientEmpty(true)
        .withTransientNone(true)
        .withTransientDefault(true)
        .withMapMaxInsertNumber(Int.MaxValue)
        .withSetMaxInsertNumber(Int.MaxValue)
    )

  inline def serializer[A](name: String): Serializer[A] = Serializer.json[A](s"reporting.$name")(using stored[A])

  /** A view's row: every field written, defaults and empties too, so a query can match a column at its default. */
  inline def row[A](name: String): Serializer[A] =
    Serializer.json[A](s"reporting.$name")(using JsonCodecMaker.make[A](
      CodecMakerConfig.withTransientEmpty(false).withTransientNone(true).withTransientDefault(false)
        .withMapMaxInsertNumber(Int.MaxValue).withSetMaxInsertNumber(Int.MaxValue)))

  /**
   * An enum written as its wire name, never its Scala name, so a renamed case does not break the
   * journal. An unknown name fails the read.
   */
  def wire[E](values: Array[E], name: E => String): JsonValueCodec[E] =
    val byName = values.map(e => name(e) -> e).toMap
    new JsonValueCodec[E]:
      def decodeValue(in: JsonReader, default: E): E =
        val s = in.readString(null)
        byName.getOrElse(s, in.decodeError(s"unknown value '$s'"))
      def encodeValue(x: E, out: JsonWriter): Unit = out.writeVal(name(x))
      def nullValue: E                             = null.asInstanceOf[E]

  given Serializer[Instant] = serializer[Instant]("instant")
  given Serializer[Boolean] = serializer[Boolean]("boolean")
  given Serializer[String]  = serializer[String]("string")

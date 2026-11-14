package io.eitheror.casino.publicapi

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonReader, JsonValueCodec, JsonWriter}
import com.github.plokhotnyuk.jsoniter_scala.macros.{CodecMakerConfig, JsonCodecMaker}
import com.thinkmorestupidless.ankka.core.Serializer

/**
 * The codec settings for this service's stored types, in one place, as reporting's: absent, empty and default fields
 * left out, `Instant`s as ISO-8601, a sum type's case named under `type`. Every stored type has a name, which is its
 * manifest, `public-api.<name>`; each type's serializer is declared beside it with `serializer` or `row`.
 */
object PublicApiCodecs:

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

  inline def serializer[A](name: String): Serializer[A] = Serializer.json[A](s"public-api.$name")(using stored[A])

  /** A view's row: every field written, defaults and empties too, so a query can match a column at its default. */
  given Serializer[Boolean] = serializer[Boolean]("boolean")

  inline def row[A](name: String): Serializer[A] =
    Serializer.json[A](s"public-api.$name")(using JsonCodecMaker.make[A](
      CodecMakerConfig.withTransientEmpty(false).withTransientNone(true).withTransientDefault(false)
        .withMapMaxInsertNumber(Int.MaxValue).withSetMaxInsertNumber(Int.MaxValue)))

  /** An enum written as its wire name, never its Scala name, so a renamed case does not break the journal. */
  def wire[E](values: Array[E], name: E => String): JsonValueCodec[E] =
    val byName = values.map(e => name(e) -> e).toMap
    new JsonValueCodec[E]:
      def decodeValue(in: JsonReader, default: E): E =
        val s = in.readString(null)
        byName.getOrElse(s, in.decodeError(s"unknown value '$s'"))
      def encodeValue(x: E, out: JsonWriter): Unit = out.writeVal(name(x))
      def nullValue: E                             = null.asInstanceOf[E]

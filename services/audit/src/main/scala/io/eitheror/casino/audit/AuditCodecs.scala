package io.eitheror.casino.audit

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.{CodecMakerConfig, JsonCodecMaker}
import com.thinkmorestupidless.ankka.core.Serializer
import io.eitheror.casino.audit.stored.{Stored, StoredChange}

/**
 * The codec settings for this service's stored types, in one place, as engagement's: absent, empty and
 * default fields left out, `Instant`s as ISO-8601, a sum type's case named under `type`. Every stored
 * type has a name, which is its manifest, `audit.<name>`; each component's companion names its own.
 */
object AuditCodecs:

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

  inline def serializer[A](name: String): Serializer[A] = Serializer.json[A](s"audit.$name")(using stored[A])

  /** A view's row: every field written, defaults and empties too, so a query can match a column at its default. */
  inline def row[A](name: String): Serializer[A] =
    Serializer.json[A](s"audit.$name")(using JsonCodecMaker.make[A](
      CodecMakerConfig.withTransientEmpty(false).withTransientNone(true).withTransientDefault(false)))

  /** The stored record as a row and as the routes and exports write it: every field, so a filter matches at a default. */
  given storedCodec: JsonValueCodec[Stored] =
    JsonCodecMaker.make[Stored](CodecMakerConfig.withTransientEmpty(false).withTransientNone(true).withTransientDefault(false))

  given storedChangeCodec: JsonValueCodec[StoredChange] = JsonCodecMaker.make[StoredChange](CodecMakerConfig.withTransientNone(true))

  given Serializer[Stored] = Serializer.json[Stored]("audit.stored")(using storedCodec)

package io.eitheror.casino.backoffice

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.{CodecMakerConfig, JsonCodecMaker}
import com.thinkmorestupidless.ankka.core.Serializer

/**
 * The codec settings for this service's stored types (states, events, commands, replies, workflow states,
 * view rows), as players': absent, empty and default fields left out, `Instant`s as ISO-8601, a sum
 * type's case named under `type`. Every stored type has a name, its wire manifest, `backoffice-state.<name>`.
 */
object StateCodecs:

  inline def stored[A]: JsonValueCodec[A] =
    JsonCodecMaker.make[A](
      CodecMakerConfig
        .withDiscriminatorFieldName(Some("type"))
        .withRequireDiscriminatorFirst(false)
        .withTransientEmpty(true)
        .withTransientNone(true)
        .withTransientDefault(true)
    )

  inline def serializer[A](name: String): Serializer[A] = Serializer.json[A](s"backoffice-state.$name")(using stored[A])

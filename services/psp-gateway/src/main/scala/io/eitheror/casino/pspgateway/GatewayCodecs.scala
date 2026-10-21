package io.eitheror.casino.pspgateway

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.{CodecMakerConfig, JsonCodecMaker}
import com.thinkmorestupidless.ankka.core.Serializer

/**
 * The codec settings for this service's stored types (states, events, commands), in one place, as
 * `merchant`'s `MerchantCodecs`: absent, empty and default fields left out, no cap on a map's entries,
 * `Long` minor units as JSON numbers and `Instant`s as ISO-8601. Every stored type has a name, which is
 * its wire manifest.
 */
object GatewayCodecs:

  inline def stored[A]: JsonValueCodec[A] =
    JsonCodecMaker.make[A](
      CodecMakerConfig
        .withDiscriminatorFieldName(Some("type"))
        .withAllowRecursiveTypes(true)
        .withRequireDiscriminatorFirst(false)
        .withTransientEmpty(true)
        .withTransientNone(true)
        .withTransientDefault(true)
        .withMapMaxInsertNumber(Int.MaxValue)
        .withSetMaxInsertNumber(Int.MaxValue)
    )

  inline def serializer[A](name: String): Serializer[A] = Serializer.json[A](s"psp-gateway.$name")(using stored[A])

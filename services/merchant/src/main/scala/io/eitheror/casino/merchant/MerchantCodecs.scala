package io.eitheror.casino.merchant

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.{CodecMakerConfig, JsonCodecMaker}
import com.thinkmorestupidless.ankka.core.Serializer

/**
 * The codec settings for this service's stored types (states, events, commands), in one place, as
 * spec 002's `WalletCodecs`: ankka's own settings with absent, empty and default fields left out, and
 * no cap on a map's or a set's entries (a sub-brand's keys or a tree's nodes must never write a
 * snapshot it cannot read back). Every stored type has a name, which is its wire manifest.
 */
object MerchantCodecs:

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

  inline def serializer[A](name: String): Serializer[A] = Serializer.json[A](s"merchant.$name")(using stored[A])

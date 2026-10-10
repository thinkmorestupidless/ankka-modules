package io.eitheror.casino.wallet

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.{CodecMakerConfig, JsonCodecMaker}

/**
 * The wallet's codec settings, in one place.
 *
 * `stored`: ankka's own settings for stored types (`Codecs.make`) with two changes. No cap on the
 * entries of a map or a set, and absent, empty and default fields left out: the key store holds an
 * outcome per key, mostly absent fields, and writing each as `null` tripled the state (461 bytes a
 * key at 10,000 transactions; research R4). Both are safe to decide now, before any journal exists;
 * a missing field reads back as its default. jsoniter caps them at 1,024 by default: a wallet that
 * had answered more than 1,024 keys would write a snapshot it could not read back, and could not
 * recover (found by `ScaleItSuite`). The key store is every key for the wallet's life (FR-002).
 *
 * `wire`: an HTTP reply leaves out what it does not have, rather than writing `null` or `[]`.
 */
object WalletCodecs:

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

  inline def wire[A]: JsonValueCodec[A] =
    JsonCodecMaker.make[A](
      CodecMakerConfig
        .withDiscriminatorFieldName(Some("type"))
        .withTransientEmpty(true)
        .withTransientNone(true)
    )

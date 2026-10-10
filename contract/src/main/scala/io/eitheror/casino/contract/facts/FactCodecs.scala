package io.eitheror.casino.contract.facts

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.{CodecMakerConfig, JsonCodecMaker}

/**
 * The facts' codec configuration: the family in the body's `type` (each case class is annotated
 * `@named` with its family's name), fields in snake_case as the original's exports name them
 * (FR-014), and absent options written as absent.
 */
object FactCodecs:
  inline def make[A]: JsonValueCodec[A] =
    JsonCodecMaker.make[A](
      CodecMakerConfig
        .withDiscriminatorFieldName(Some("type"))
        .withRequireDiscriminatorFirst(false)
        .withFieldNameMapper(JsonCodecMaker.enforce_snake_case)
        .withTransientNone(true)
        .withTransientEmpty(false)
    )

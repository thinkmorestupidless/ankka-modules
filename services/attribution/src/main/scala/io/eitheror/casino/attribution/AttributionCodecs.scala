package io.eitheror.casino.attribution

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.{CodecMakerConfig, JsonCodecMaker}
import com.thinkmorestupidless.ankka.core.Serializer

/**
 * The codec settings for this service's stored types (states, events, commands, replies, view rows), in
 * one place, as `ingest`'s `IngestCodecs`: absent, empty and default fields left out, `Long` minor units
 * as JSON numbers and `Instant`s as ISO-8601. Every stored type has a name, which is its wire manifest.
 */
object AttributionCodecs:

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

  inline def serializer[A](name: String): Serializer[A] = Serializer.json[A](s"attribution.$name")(using stored[A])

  given Serializer[Binding]    = serializer[Binding]("Binding")
  given Serializer[TagOutcome] = serializer[TagOutcome]("TagOutcome")
  given Serializer[Touch]      = serializer[Touch]("Touch")
  given Serializer[MoneyEvent] = serializer[MoneyEvent]("MoneyEvent")
  given Serializer[java.time.Instant] = serializer[java.time.Instant]("Instant")

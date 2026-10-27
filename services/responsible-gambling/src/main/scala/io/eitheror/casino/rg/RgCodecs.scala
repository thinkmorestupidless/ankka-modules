package io.eitheror.casino.rg

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.{CodecMakerConfig, JsonCodecMaker}
import com.thinkmorestupidless.ankka.core.Serializer

import java.time.Instant

/**
 * The codec settings for this service's stored types, in one place, as players': absent, empty and default
 * fields left out, `Instant`s as ISO-8601, a sum type's case named under `type`. A `Personal[String]` field
 * is written through the contract's codec. Every stored type has a name, which is its manifest, `rg.<name>`.
 */
object RgCodecs:

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

  inline def serializer[A](name: String): Serializer[A] = Serializer.json[A](s"rg.$name")(using stored[A])

/**
 * What the components and the endpoints share that is the service's: the settings, the clock, and the
 * timers for the components the runtime offers none. A suite wires its own, with a clock it moves.
 */
object RgRuntime:
  @volatile var settings: Settings = Settings()
  @volatile var now: () => Instant = () => Instant.now()

  @volatile var timers: () => com.thinkmorestupidless.ankka.sdk.TimerScheduler =
    () => throw IllegalStateException("the timers are not wired: RgService sets RgRuntime.timers at start")

  def wire(s: Settings, clock: () => Instant = () => Instant.now()): Unit =
    settings = s
    now = clock

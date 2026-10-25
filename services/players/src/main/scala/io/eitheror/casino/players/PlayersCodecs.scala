package io.eitheror.casino.players

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.{CodecMakerConfig, JsonCodecMaker}
import com.thinkmorestupidless.ankka.core.Serializer

import java.time.Instant

/**
 * The codec settings for this service's stored types (states, events, commands, replies, workflow
 * states, view rows), in one place, as ingest's: absent, empty and default fields left out, `Instant`s as
 * ISO-8601, a sum type's case named under `type`. A `Personal[String]` field is written through the
 * contract's codec (the marker's), never as a type of its own. Every stored type has a name, which is its
 * wire manifest, `players.<name>`.
 */
object PlayersCodecs:

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

  inline def serializer[A](name: String): Serializer[A] = Serializer.json[A](s"players.$name")(using stored[A])

/**
 * What the components and the endpoints share that is the service's: the settings and the clock. A
 * suite wires its own, with short bounds and a clock it moves.
 */
object PlayersRuntime:
  @volatile var settings: Settings = Settings()
  @volatile var now: () => Instant = () => Instant.now()

  /** The timers, for the components the runtime offers none (a consumer); the service sets it at start. */
  @volatile var timers: () => com.thinkmorestupidless.ankka.sdk.TimerScheduler =
    () => throw IllegalStateException("the timers are not wired: PlayersService sets PlayersRuntime.timers at start")

  /** The view client, for the components the runtime offers none (a timed action); the service sets it at start. */
  @volatile var views: () => com.thinkmorestupidless.ankka.runtime.ViewClient =
    () => throw IllegalStateException("the views are not wired: PlayersService sets PlayersRuntime.views at start")

  def wire(s: Settings, clock: () => Instant = () => Instant.now()): Unit =
    settings = s
    now = clock

/** Where a late answer is heard after its caller was answered: a virtual thread each, as a handler runs on. */
object Background:
  given ec: scala.concurrent.ExecutionContext =
    scala.concurrent.ExecutionContext.fromExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor())

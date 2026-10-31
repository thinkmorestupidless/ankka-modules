package io.eitheror.casino.payouts

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonReader, JsonValueCodec, JsonWriter}
import com.github.plokhotnyuk.jsoniter_scala.macros.{CodecMakerConfig, JsonCodecMaker}
import com.thinkmorestupidless.ankka.core.Serializer

import java.time.Instant

/**
 * The codec settings for this service's stored types, in one place, as kyc's: absent, empty and default
 * fields left out, `Instant`s as ISO-8601, a sum type's case named under `type`. A `Personal` field is
 * written through the contract's codec. Every stored type has a name, which is its manifest,
 * `payouts.<name>`.
 */
object PayoutsCodecs:

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

  inline def serializer[A](name: String): Serializer[A] = Serializer.json[A](s"payouts.$name")(using stored[A])

  /** A view's row: every field written, defaults and empties too, so a query can match a column at its default. */
  inline def row[A](name: String): Serializer[A] =
    Serializer.json[A](s"payouts.$name")(using JsonCodecMaker.make[A](
      CodecMakerConfig.withTransientEmpty(false).withTransientNone(true).withTransientDefault(false)))

  /**
   * An enum written as its wire name, never its Scala name, so a renamed case does not break the
   * journal. An unknown name fails the read.
   */
  def wire[E](values: Array[E], name: E => String): JsonValueCodec[E] =
    val byName = values.map(e => name(e) -> e).toMap
    new JsonValueCodec[E]:
      def decodeValue(in: JsonReader, default: E): E =
        val s = in.readString(null)
        byName.getOrElse(s, in.decodeError(s"unknown value '$s'"))
      def encodeValue(x: E, out: JsonWriter): Unit = out.writeVal(name(x))
      def nullValue: E                             = null.asInstanceOf[E]

/**
 * What the components and the endpoints share that is the service's: the settings, the clock, the timers.
 * A suite wires its own, with a clock it moves and fakes for the neighbours.
 */
object PayoutsRuntime:
  @volatile var settings: Settings = Settings()
  @volatile var now: () => Instant = () => Instant.now()

  /** The neighbours, made from the component's service clients; a suite sets fakes. */
  @volatile var wallet: com.thinkmorestupidless.ankka.sdk.ServiceClients => clients.Wallet =
    services => clients.Wallet.http(services, settings)
  @volatile var gateway: com.thinkmorestupidless.ankka.sdk.ServiceClients => clients.Gateway =
    services => clients.Gateway.http(services, settings)

  @volatile var timers: () => com.thinkmorestupidless.ankka.sdk.TimerScheduler =
    () => throw IllegalStateException("the timers are not wired: PayoutsService sets PayoutsRuntime.timers at start")

  def wire(s: Settings, clock: () => Instant = () => Instant.now()): Unit =
    settings = s
    now = clock

package io.eitheror.casino.kyc

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonReader, JsonValueCodec, JsonWriter}
import com.github.plokhotnyuk.jsoniter_scala.macros.{CodecMakerConfig, JsonCodecMaker}
import com.thinkmorestupidless.ankka.core.Serializer
import io.eitheror.casino.contract.personal.Personal

import java.time.{Instant, LocalDate}

/**
 * The codec settings for this service's stored types, in one place, as responsible-gambling's: absent,
 * empty and default fields left out, `Instant`s as ISO-8601, a sum type's case named under `type`. A
 * `Personal` field is written through the contract's codec. Every stored type has a name, which is its
 * manifest, `kyc.<name>`.
 */
object KycCodecs:

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

  inline def serializer[A](name: String): Serializer[A] = Serializer.json[A](s"kyc.$name")(using stored[A])

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

  /** The day a player turns eighteen (R11): personal, written as an ISO date. */
  given adultDate: JsonValueCodec[Personal[LocalDate]] with
    def decodeValue(in: JsonReader, default: Personal[LocalDate]): Personal[LocalDate] = Personal(LocalDate.parse(in.readString(null)))
    def encodeValue(x: Personal[LocalDate], out: JsonWriter): Unit                    = out.writeVal(x.reveal.toString)
    def nullValue: Personal[LocalDate]                                                = null.asInstanceOf[Personal[LocalDate]]

/**
 * What the components and the endpoints share that is the service's: the settings, the clock, the store
 * and the timers. A suite wires its own, with a clock it moves and a store of its choosing.
 */
object KycRuntime:
  @volatile var settings: Settings = Settings()
  @volatile var now: () => Instant = () => Instant.now()

  @volatile var store: () => io.eitheror.casino.kyc.documents.Store = () => io.eitheror.casino.kyc.documents.Store.Unavailable

  @volatile var timers: () => com.thinkmorestupidless.ankka.sdk.TimerScheduler =
    () => throw IllegalStateException("the timers are not wired: KycService sets KycRuntime.timers at start")

  def wire(s: Settings, clock: () => Instant = () => Instant.now()): Unit =
    settings = s
    now = clock

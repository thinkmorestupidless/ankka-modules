package io.eitheror.casino.programme

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonReader, JsonValueCodec, JsonWriter}
import com.github.plokhotnyuk.jsoniter_scala.macros.{CodecMakerConfig, JsonCodecMaker}
import com.thinkmorestupidless.ankka.core.Serializer
import io.eitheror.casino.programme.commission.{FactType, LedgerFact, Window}
import io.eitheror.casino.programme.sealing.Sealed

/**
 * The codec settings for this service's stored types, in one place, as `wallet`'s `WalletCodecs`: no
 * cap on a map's or a set's entries (an account's last 10,000 fact ids), and absent, empty and default
 * fields left out. Every stored type has a name, which is its manifest, `programme.<name>`. `wire` is
 * an HTTP answer's: it leaves out what it does not have.
 */
object ProgrammeCodecs:

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
    JsonCodecMaker.make[A](CodecMakerConfig.withDiscriminatorFieldName(Some("type")).withTransientEmpty(true).withTransientNone(true))

  inline def serializer[A](name: String): Serializer[A] = Serializer.json[A](s"programme.$name")(using stored[A])

  /** A fact type as the domain spells it (`bonus-cost`), not as Scala names the case. */
  given JsonValueCodec[FactType] = new JsonValueCodec[FactType]:
    def decodeValue(in: JsonReader, default: FactType): FactType =
      val s = in.readString(null)
      FactType.parse(s).getOrElse(in.decodeError(s"no fact type $s"))
    def encodeValue(x: FactType, out: JsonWriter): Unit = out.writeVal(x.wire)
    def nullValue: FactType = null

  given JsonValueCodec[Window] = new JsonValueCodec[Window]:
    def decodeValue(in: JsonReader, default: Window): Window =
      in.readString(null) match
        case "lifetime" => Window.Lifetime
        case "period"   => Window.Period
        case s          => in.decodeError(s"no window $s")
    def encodeValue(x: Window, out: JsonWriter): Unit = out.writeVal(x.toString.toLowerCase)
    def nullValue: Window = null

  given Serializer[LedgerFact] = serializer[LedgerFact]("LedgerFact")
  given Serializer[Sealed]     = serializer[Sealed]("Sealed")

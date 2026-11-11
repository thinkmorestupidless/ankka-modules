package io.eitheror.casino.support

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonReader, JsonValueCodec, JsonWriter}
import com.github.plokhotnyuk.jsoniter_scala.macros.{CodecMakerConfig, JsonCodecMaker}
import com.thinkmorestupidless.ankka.core.Serializer

/**
 * The codec settings for this service's stored types, in one place, as engagement's: absent, empty and default
 * fields left out, `Instant`s as ISO-8601, a sum type's case named under `type`. Every stored type has a name,
 * which is its manifest, `support.<name>` (data-model.md).
 */
object SupportCodecs:

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

  inline def serializer[A](name: String): Serializer[A] = Serializer.json[A](s"support.$name")(using stored[A])

  /** A view's row: every field written, defaults and empties too, so a query can match a column at its default. */
  inline def row[A](name: String): Serializer[A] =
    Serializer.json[A](s"support.$name")(using JsonCodecMaker.make[A](
      CodecMakerConfig.withTransientEmpty(false).withTransientNone(true).withTransientDefault(false)))

  /** What a route shows: every field written, an absent one as `null`, so an operator sees the whole body. */
  inline def shown[A]: JsonValueCodec[A] =
    JsonCodecMaker.make[A](CodecMakerConfig.withTransientEmpty(false).withTransientNone(false).withTransientDefault(false))

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

/** Every stored type's serializer, under the manifests data-model.md names. */
object Stored:
  import io.eitheror.casino.support.config.*
  import io.eitheror.casino.support.conversation.*
  import io.eitheror.casino.support.limits.*

  /** A conversation's events, each case under its data-model.md name, so a renamed case keeps its journal. */
  val eventCodec: JsonValueCodec[ConversationEvent] =
    JsonCodecMaker.make[ConversationEvent](
      CodecMakerConfig
        .withDiscriminatorFieldName(Some("type"))
        .withRequireDiscriminatorFirst(false)
        .withTransientEmpty(true)
        .withTransientNone(true)
        .withTransientDefault(true)
        .withAdtLeafClassNameMapper(n =>
          JsonCodecMaker.simpleClassName(n) match
            case "Started"           => "started"
            case "TurnTaken"         => "turn"
            case "ToolCalled"        => "tool"
            case "ApprovalRequested" => "requested"
            case "Decided"           => "decided"
            case "GuardrailRejected" => "rejected"
            case "HandedOff"         => "handed-off"
            case "Ended"             => "ended"
            case "Forgotten"         => "forgotten"
            case other               => other
        )
    )

  val conversationEvent: Serializer[ConversationEvent] = Serializer.json("support.conversation.event")(using eventCodec)
  val conversation: Serializer[ConversationState]      = SupportCodecs.serializer[ConversationState]("conversation")
  val configuration: Serializer[AgentConfigState]      = SupportCodecs.serializer[AgentConfigState]("configuration")
  val configVersion: Serializer[VersionState]          = SupportCodecs.serializer[VersionState]("config-version")
  val turnWindow: Serializer[TurnWindowState]          = SupportCodecs.serializer[TurnWindowState]("turn-window")
  val budget: Serializer[BudgetState]                  = SupportCodecs.serializer[BudgetState]("budget")

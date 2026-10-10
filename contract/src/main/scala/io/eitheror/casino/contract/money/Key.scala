package io.eitheror.casino.contract.money

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonReader, JsonValueCodec, JsonWriter}

/** Why a money command was refused: it carried no key (FR-027). */
case object MissingKey:
  val message: String = "a money command must carry the key its caller chose"

/**
 * The name a caller gives one money command (FR-027): a game provider's transaction id, a payment
 * reference or id, a withdrawal request id. Sending the command again under the same key returns the
 * first outcome and moves nothing. Never minted by the callee: the original minted a random UUID per
 * dispatch, which is why a retry could move money twice (spec 001, known defects).
 */
opaque type Key = String

object Key:
  val MaxLength: Int = 255

  def from(text: String): Either[MissingKey.type, Key] =
    val trimmed = Option(text).map(_.trim).getOrElse("")
    if trimmed.isEmpty || trimmed.length > MaxLength then Left(MissingKey) else Right(trimmed)

  def from(text: Option[String]): Either[MissingKey.type, Key] = text.fold(Left(MissingKey))(from)

  def unsafe(text: String): Key = from(text).fold(_ => throw IllegalArgumentException(MissingKey.message), identity)

  extension (key: Key) def value: String = key

  given JsonValueCodec[Key] with
    def decodeValue(in: JsonReader, default: Key): Key =
      from(in.readString(null)).fold(_ => in.decodeError(MissingKey.message), identity)
    def encodeValue(x: Key, out: JsonWriter): Unit = out.writeVal(x)
    def nullValue: Key                             = null.asInstanceOf[Key]

/** Any command that moves money in a wallet (glossary: money command). */
trait MoneyCommand:
  def key: Key

/**
 * The keys a money-moving entity has seen and what it answered, so a repeat is answered with the
 * first outcome and moves nothing (FR-027). Immutable, to be held in an entity's state.
 *
 * Bounded by nothing here on purpose: a wallet must remember every key it ever answered, or a late
 * retry moves money; the owning spec decides retention.
 */
final case class KeyedOutcomes[A](seen: Map[String, A] = Map.empty[String, A]):

  def outcomeOf(key: Key): Option[A] = seen.get(key.value)

  /**
   * Answers `command`: the first outcome when its key was seen, else `decide`'s, recorded. The
   * boolean says whether this was the first time, so the caller emits events only then.
   */
  def answer(key: Key)(decide: => A): (KeyedOutcomes[A], A, Boolean) =
    seen.get(key.value) match
      case Some(first) => (this, first, false)
      case None =>
        val outcome = decide
        (copy(seen = seen.updated(key.value, outcome)), outcome, true)

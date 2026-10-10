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
 * What a key remembers: the digest of the command first sent under it and the outcome it was given.
 * The digest tells a repeat (same command) from a reuse (another command under the same key).
 */
final case class Seen[A](digest: String, outcome: A)

/** How a money command under a key is answered (spec 002 FR-002). */
enum Answer[+A]:
  /** The key was new: `outcome` was decided now and recorded. */
  case First(outcome: A)
  /** The same command again: the first outcome, and nothing moves. */
  case Repeated(first: A)
  /** Another command under a seen key: refused naming the first (`KEY_REUSED`). */
  case Reused(first: Seen[A @scala.annotation.unchecked.uncheckedVariance])

/**
 * The digest of a command: SHA-256 over its fields in name order, its key left out, so two commands
 * that differ in any field (type, amount, round, …) differ here and an identical repeat does not.
 */
object Digest:
  def of(fields: Iterable[(String, String)]): String =
    sha256(fields.toVector.sortBy(_._1).map((k, v) => s"${k.length}:$k=${v.length}:$v").mkString(";"))

  def sha256(text: String): String =
    java.security.MessageDigest.getInstance("SHA-256")
      .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8))
      .map(b => f"${b & 0xff}%02x").mkString

/**
 * The keys a money-moving entity has seen, the digest of each key's command and what it answered,
 * so a repeat is answered with the first outcome and moves nothing, and another command under a
 * seen key is told apart (FR-027; spec 002 FR-002). Immutable, to be held in an entity's state.
 *
 * Bounded by nothing here on purpose: a wallet must remember every key it ever answered, or a late
 * retry moves money; the owning spec decides retention.
 */
final case class KeyedOutcomes[A](seen: Map[String, Seen[A]] = Map.empty[String, Seen[A]]):

  def outcomeOf(key: Key): Option[A] = seen.get(key.value).map(_.outcome)

  def seenOf(key: Key): Option[Seen[A]] = seen.get(key.value)

  /** A seen key's answer for a command with this digest, or `None` when the key is new. */
  def check(key: Key, digest: String): Option[Answer[A]] =
    seen.get(key.value).map(first => if first.digest == digest then Answer.Repeated(first.outcome) else Answer.Reused(first))

  /** Records a new key's outcome; a seen key is never overwritten. */
  def record(key: Key, digest: String, outcome: A): KeyedOutcomes[A] =
    if seen.contains(key.value) then this else copy(seen = seen.updated(key.value, Seen(digest, outcome)))

  /** Answers a command: the stored answer for a seen key, else `decide`'s outcome, recorded. */
  def answer(key: Key, digest: String)(decide: => A): (KeyedOutcomes[A], Answer[A]) =
    check(key, digest) match
      case Some(stored) => (this, stored)
      case None =>
        val outcome = decide
        (record(key, digest, outcome), Answer.First(outcome))

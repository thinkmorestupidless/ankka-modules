package io.eitheror.casino.rg.profile

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.rg.RgCodecs

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.HexFormat

/** The answer a keyed command gave, kept so its repeat answers the same: the status and the body. */
final case class KeptReply(status: Int, body: String)

final case class KeyEntry(key: String, hash: String, reply: KeptReply)

/** What a key comes to before its command runs (spec 017 amendments R2–R4). */
enum KeyCheck:
  case Fresh
  case Repeat(reply: KeptReply)
  /** The key with another body. */
  case Reused
  /** A key older than the last [[RgKeysState.Recent]]: its outcome is no longer kept. */
  case Expired

final case class KeyAsk(key: String, hash: String)

/**
 * `support-agent`'s keys for one player (spec 017 amendments R2–R4): the last [[RgKeysState.Recent]] with each
 * command's answer and its body's hash, as the wallet keeps its recent keys. A key evicted from those is
 * remembered by a short digest only, so its return is `key_expired` rather than a second change. Keys are
 * approval ids, a handful per player, so the evicted digests stay small (research, *Found during implementation*).
 */
final case class RgKeysState(recent: Vector[KeyEntry] = Vector.empty, evicted: Set[String] = Set.empty):

  def check(key: String, hash: String): KeyCheck =
    recent.find(_.key == key) match
      case Some(e) if e.hash == hash => KeyCheck.Repeat(e.reply)
      case Some(_)                   => KeyCheck.Reused
      case None if evicted(RgKeysState.digest(key)) => KeyCheck.Expired
      case None                      => KeyCheck.Fresh

  /** The first answer under a key is kept; a second (two duplicates that ran together) keeps the first. */
  def remember(key: String, hash: String, reply: KeptReply): RgKeysState =
    if recent.exists(_.key == key) then this
    else
      val all = recent :+ KeyEntry(key, hash, reply)
      val out = all.dropRight(RgKeysState.Recent)
      RgKeysState(all.drop(out.size), evicted ++ out.map(e => RgKeysState.digest(e.key)))

object RgKeysState:
  val Recent: Int = 64

  /** 64 bits of the key's SHA-256: enough to tell one player's keys apart, small enough to keep them all. */
  def digest(key: String): String = HexFormat.of.formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(UTF_8)).take(8))

  /** The hash of a command under a key: the route's command and its body's fields, named. */
  def hash(parts: String*): String =
    HexFormat.of.formatHex(MessageDigest.getInstance("SHA-256").digest(parts.mkString("\u001f").getBytes(UTF_8)))

final class RgKeys(@scala.annotation.unused context: KeyValueEntityContext) extends KeyValueEntity[RgKeysState]:
  def emptyState: RgKeysState = RgKeysState()

  def check(ask: KeyAsk): Effect[KeyCheck] = effects.reply(currentState.check(ask.key, ask.hash))

  /** Keeps the answer and replies the one kept: the first, should two duplicates have run together. */
  def record(entry: KeyEntry): Effect[KeptReply] =
    val next = currentState.remember(entry.key, entry.hash, entry.reply)
    val kept = next.recent.find(_.key == entry.key).fold(entry.reply)(_.reply)
    if next == currentState then effects.reply(kept) else effects.updateState(next).thenReply(_ => kept)

object RgKeys
    extends KeyValueEntity.Companion[RgKeys, RgKeysState](
      componentId = ComponentId("rg-keys"),
      stateSerializer = RgCodecs.serializer[RgKeysState]("keys")
    ):

  given com.thinkmorestupidless.ankka.core.Serializer[KeyAsk]    = RgCodecs.serializer[KeyAsk]("key-ask")
  given com.thinkmorestupidless.ankka.core.Serializer[KeyCheck]  = RgCodecs.serializer[KeyCheck]("key-check")
  given com.thinkmorestupidless.ankka.core.Serializer[KeyEntry]  = RgCodecs.serializer[KeyEntry]("key-entry")
  given com.thinkmorestupidless.ankka.core.Serializer[KeptReply] = RgCodecs.serializer[KeptReply]("kept-reply")

  def id(playerId: String): EntityId = EntityId(playerId)

  def create(context: KeyValueEntityContext) = new RgKeys(context)

  val check  = command("check")(_.check)
  val record = command("record")(_.record)

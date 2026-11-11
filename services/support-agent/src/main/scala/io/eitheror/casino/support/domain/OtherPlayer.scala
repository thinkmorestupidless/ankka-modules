package io.eitheror.casino.support.domain

import java.util.concurrent.ConcurrentHashMap
import scala.util.matching.Regex

/**
 * The `other-player` pattern (FR-021, FR-022; research R8): what a turn or a reply must not carry about anyone but the
 * player. A turn is refused for a player id (a UUID) other than their own, an email address, or an internal hostname
 * (`*.svc`, `*.cluster.local`, `*.internal`); a reply for the id or the hostname, since it may repeat the player's own
 * email back to them. Each answers what it found, as the refusal names it, never the value itself. A UUID is another
 * player's only when it is none of the player's own ids ([[OwnIds]]): their game sessions, transactions and rewards
 * carry UUIDs too.
 */
object OtherPlayer:
  private val Uuid: Regex  = "(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b".r
  private val Email: Regex = "[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}".r
  private val Host: Regex  = "(?i)\\b[a-z0-9-]+(\\.[a-z0-9-]+)*\\.(svc|cluster\\.local|internal)\\b".r

  val AnotherPlayer   = "another player's id"
  val AnEmail         = "an email address"
  val AnInternalHost  = "an internal address"

  /** The UUIDs in `text`, in lower case. */
  def ids(text: String): Set[String] = Uuid.findAllIn(text).map(_.toLowerCase).toSet

  private def anotherId(text: String, own: OwnIds): Boolean = ids(text).exists(!own(_))

  /** What a player's turn names that it must not, if anything. */
  def inInput(text: String, own: OwnIds): Option[String] =
    if anotherId(text, own) then Some(AnotherPlayer)
    else if Email.findFirstIn(text).isDefined then Some(AnEmail)
    else Option.when(Host.findFirstIn(text).isDefined)(AnInternalHost)

  /** What a reply names that it must not, if anything. */
  def inOutput(text: String, own: OwnIds): Option[String] =
    if anotherId(text, own) then Some(AnotherPlayer)
    else Option.when(Host.findFirstIn(text).isDefined)(AnInternalHost)

/**
 * The ids a turn may name as the player's own: their player id, every id their tools answered this turn, and, read
 * once when a turn names an id that is neither, the ids their own records carry (`records`: the answers of their
 * game sessions, transactions and rewards), since a reply may repeat an id an earlier turn's tool answered.
 */
final class OwnIds(own: String, records: () => Iterable[String]):
  private val seen = ConcurrentHashMap.newKeySet[String]()
  private lazy val recorded: Set[String] = records().iterator.flatMap(OtherPlayer.ids).toSet

  /** Every id in `text`, which a tool answered about the player. */
  def add(text: String): Unit = OtherPlayer.ids(text).foreach(seen.add)

  def apply(id: String): Boolean =
    val i = id.toLowerCase
    i == own.toLowerCase || seen.contains(i) || recorded(i)

object OwnIds:
  /** The player's id alone. */
  def only(own: String): OwnIds = OwnIds(own, () => Nil)

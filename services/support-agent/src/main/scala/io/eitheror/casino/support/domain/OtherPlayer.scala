package io.eitheror.casino.support.domain

import scala.util.matching.Regex

/**
 * The `other-player` pattern (FR-021, FR-022; research R8): what a turn or a reply must not carry about anyone but the
 * player. A turn is refused for a player id (a UUID) other than their own, an email address, or an internal hostname
 * (`*.svc`, `*.cluster.local`, `*.internal`); a reply for the id or the hostname, since it may repeat the player's own
 * email back to them. Each answers what it found, as the refusal names it, never the value itself.
 */
object OtherPlayer:
  private val Uuid: Regex  = "(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b".r
  private val Email: Regex = "[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}".r
  private val Host: Regex  = "(?i)\\b[a-z0-9-]+(\\.[a-z0-9-]+)*\\.(svc|cluster\\.local|internal)\\b".r

  val AnotherPlayer   = "another player's id"
  val AnEmail         = "an email address"
  val AnInternalHost  = "an internal address"

  private def anotherId(text: String, own: String): Boolean =
    Uuid.findAllIn(text).exists(!_.equalsIgnoreCase(own))

  /** What a player's turn names that it must not, if anything. */
  def inInput(text: String, own: String): Option[String] =
    if anotherId(text, own) then Some(AnotherPlayer)
    else if Email.findFirstIn(text).isDefined then Some(AnEmail)
    else Option.when(Host.findFirstIn(text).isDefined)(AnInternalHost)

  /** What a reply names that it must not, if anything. */
  def inOutput(text: String, own: String): Option[String] =
    if anotherId(text, own) then Some(AnotherPlayer)
    else Option.when(Host.findFirstIn(text).isDefined)(AnInternalHost)

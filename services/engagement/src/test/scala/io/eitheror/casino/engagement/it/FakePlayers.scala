package io.eitheror.casino.engagement.it

import scala.collection.concurrent.TrieMap

/**
 * `players`' inbox route the webhook's `send_message` relays to (`POST …/players/{player}/messages`):
 * 200 `{"ok": true}` unless an answer is scripted for the player; any other route is 404.
 */
final class FakePlayers(brand: String) extends FakeServer():
  private val scripted = TrieMap.empty[String, Answer]
  private val Prefix   = s"/v1/casino/brands/$brand/players/"

  def answer(player: String, answer: Answer): Unit = scripted.update(player, answer)

  def reset(): Unit =
    scripted.clear()
    resetServer()

  /** Every relayed message, oldest first; for one player with `messages(player)`. */
  def messages: Vector[Received]                 = received.filter(r => playerOf(r).isDefined)
  def messages(player: String): Vector[Received] = received.filter(r => playerOf(r).contains(player))

  private def playerOf(r: Received): Option[String] =
    r.path.stripPrefix(Prefix).split('/').toVector match
      case Vector(player, "messages") if r.method == "POST" && r.path.startsWith(Prefix) => Some(player)
      case _                                                                            => None

  protected def route(r: Received): Answer = playerOf(r) match
    case Some(player) => scripted.getOrElse(player, Answer(200, """{"ok":true}"""))
    case None         => Answer(404, """{"error":"not_found"}""")

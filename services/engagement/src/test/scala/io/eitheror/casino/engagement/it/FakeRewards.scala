package io.eitheror.casino.engagement.it

import scala.collection.concurrent.TrieMap

/**
 * `rewards`' two routes the webhook relays to (`POST …/reward-campaigns/{player}/target` and
 * `…/award`): 200 `{"ok": true}` unless an answer is scripted for the route (`target`, `award`), or for
 * the route and a player; any other route is 404.
 */
final class FakeRewards(brand: String) extends FakeServer():
  private val byRoute  = TrieMap.empty[String, Answer]
  private val byPlayer = TrieMap.empty[(String, String), Answer]
  private val Prefix   = s"/v1/casino/brands/$brand/reward-campaigns/"

  def answer(route: String, answer: Answer): Unit                 = byRoute.update(route, answer)
  def answer(player: String, route: String, answer: Answer): Unit = byPlayer.update((player, route), answer)

  def reset(): Unit =
    byRoute.clear()
    byPlayer.clear()
    resetServer()

  /** Every call to `target` or `award`, oldest first; for one player with `calls(route, player)`. */
  def calls(route: String): Vector[Received]                 = received.filter(r => routeOf(r).exists(_._2 == route))
  def calls(route: String, player: String): Vector[Received] = received.filter(r => routeOf(r).contains((player, route)))

  private def routeOf(r: Received): Option[(String, String)] =
    r.path.stripPrefix(Prefix).split('/').toVector match
      case Vector(player, route @ ("target" | "award")) if r.method == "POST" && r.path.startsWith(Prefix) => Some((player, route))
      case _                                                                                              => None

  protected def route(r: Received): Answer = routeOf(r) match
    case Some(key @ (_, route)) => byPlayer.get(key).orElse(byRoute.get(route)).getOrElse(Answer(200, """{"ok":true}"""))
    case None                   => Answer(404, """{"error":"not_found"}""")

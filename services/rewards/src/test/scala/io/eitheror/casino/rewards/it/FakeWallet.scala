package io.eitheror.casino.rewards.it

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.eitheror.casino.rewards.Json
import io.eitheror.casino.rewards.Json.*

import scala.collection.concurrent.TrieMap

/**
 * The brand's wallet's routes rewards calls, as spec 002 built them and research R6 amends them: the six award
 * routes and the two reads. A small model stands behind the defaults: an award opens a record named by its key, a
 * forfeit, a cancel or a close ends the named record, and the reads list what is open. A key answered once answers
 * the same again while it is among the 1,000 most recent, as the wallet's idempotency does. Any answer is scriptable
 * per key (`answerFor`) or per route (`routeAnswer`), and a record can be put at pending cap or given a baseline-less
 * origin.
 */
final class FakeWallet(brand: String) extends FakeServer():
  /** One open record: `grantRef` is the award's key. */
  final case class Record(grantRef: String, bonusRef: String, amount: Long, requirement: Long, priority: Long, pendingCap: Boolean = false,
      baseline: Boolean = true, winnings: Long = 0L)

  private val records  = TrieMap.empty[(String, String), Vector[Record]]
  private val real     = TrieMap.empty[(String, String), Long]
  private val scripted = TrieMap.empty[String, Answer]
  private val byRoute  = TrieMap.empty[String, Answer]
  private val answered = TrieMap.empty[(String, String), Answer]
  private val recent   = java.util.concurrent.ConcurrentLinkedQueue[(String, String)]()

  /** The answer for the call keyed `key`, whatever the route. */
  def answerFor(key: String, answer: Answer): Unit = scripted.update(key, answer)
  /** The answer for every call to a route: `awards`, `awards/forfeit`, `awards/cancel`, `awards/close-pending-cap`,
   * `awards/switch`, `provider-credits`, `restricted-funds`, `balance`. */
  def routeAnswer(route: String, answer: Answer): Unit = byRoute.update(route, answer)
  def clearRoute(route: String): Unit                  = byRoute.remove(route): Unit
  def realFor(player: String, currency: String, amount: Long): Unit = real.update((player, currency), amount)
  def realOf(player: String, currency: String): Long               = real.getOrElse((player, currency), 0L)
  def open(player: String, currency: String): Vector[Record]       = records.getOrElse((player, currency), Vector.empty)
  /** The record the award keyed `grantRef` opened now waits at its pending cap (W2). */
  def pendingCap(player: String, currency: String, grantRef: String): Unit = alter(player, currency, grantRef)(_.copy(pendingCap = true))
  /** The record has no baseline, as spec 019's migrated ones may (W4). */
  def noBaseline(player: String, currency: String, grantRef: String): Unit = alter(player, currency, grantRef)(_.copy(baseline = false))
  /**
   * The real winnings since the record's award less the deposits since, as the wallet computes them from its baseline
   * (spec 002 FR-017): what a cancel of the record claws back, at most the real balance, nothing without a baseline.
   */
  def winningsSince(player: String, currency: String, grantRef: String, amount: Long): Unit =
    alter(player, currency, grantRef)(_.copy(winnings = amount))

  def reset(): Unit =
    records.clear(); real.clear(); scripted.clear(); byRoute.clear(); answered.clear(); recent.clear()
    resetServer()

  /** Every call to the route, in order (`awards`, `awards/cancel`, …). */
  def calls(route: String): Vector[Received] = received.filter(r => routeOf(r).contains(route))
  def keys(route: String): Vector[String]    = calls(route).flatMap(_.json.flatMap(_.text("key")))

  private val Prefix = s"/v1/casino/brands/$brand/wallets/"

  private def routeOf(r: Received): Option[String] =
    Option.when(r.path.startsWith(Prefix))(r.path.stripPrefix(Prefix).split('/').toVector.drop(2).mkString("/")).filter(_.nonEmpty)

  private def alter(player: String, currency: String, grantRef: String)(f: Record => Record): Unit =
    records.updateWith((player, currency))(_.map(_.map(r => if r.grantRef == grantRef then f(r) else r))): Unit

  protected def route(r: Received): Answer =
    r.path.stripPrefix(Prefix).split('/').toVector match
      case player +: currency +: rest if r.path.startsWith(Prefix) && rest.nonEmpty =>
        val route = rest.mkString("/")
        byRoute.get(route).getOrElse {
          (r.method, route) match
            case ("GET", "restricted-funds") => Answer.ok(restricted(player, currency))
            case ("GET", "balance")          => Answer.ok(balance(player, currency))
            case ("POST", _) =>
              val body = r.json.collect { case o: ObjectNode => o }.getOrElse(Json.obj())
              body.text("key") match
                case None => FakeWallet.refusal("MISSING_KEY", 400)
                case Some(key) => keyed(route, key)(scripted.getOrElse(key, command(player, currency, route, key, body)))
            case _ => FakeWallet.notFound
        }
      case _ => FakeWallet.notFound

  /**
   * A key answered once answers the same again while it is among the wallet's 1,000 most recent (spec Dependencies);
   * past them the command is carried out afresh, as the wallet's would be.
   */
  private def keyed(route: String, key: String)(fresh: => Answer): Answer = synchronized {
    answered.getOrElse((route, key), {
      val a = fresh
      answered.update((route, key), a)
      recent.add((route, key))
      while recent.size > FakeWallet.RecentKeys do Option(recent.poll()).foreach(answered.remove)
      a
    })
  }

  private def command(player: String, currency: String, route: String, key: String, body: JsonNode): Answer =
    val wallet = (player, currency)
    def named = body.text("grantRef").flatMap(g => open(player, currency).find(_.grantRef == g))
    def end(g: String): Unit = records.updateWith(wallet)(_.map(_.filterNot(_.grantRef == g))): Unit
    route match
      case "awards" =>
        val amount = body.long("amount").getOrElse(0L)
        val active = open(player, currency).count(!_.pendingCap)
        if body.long("maxConcurrentGrants").exists(active >= _) then FakeWallet.refusal("BONUS_ALREADY_ACTIVE")
        else
          val priority = body.long("priority").getOrElse(0L)
          val record   = Record(key, body.textOr("bonusRef", ""), amount, amount * body.long("wageringMultiplier").getOrElse(0L), priority)
          records.updateWith(wallet)(rs => Some(rs.getOrElse(Vector.empty) :+ record)): Unit
          Answer.ok(money(player, currency, "txnId" -> s"txn-$key", "recordId" -> key, "wageringRequirement" -> record.requirement))
      case "awards/forfeit" =>
        named.orElse(open(player, currency).headOption) match
          case None    => FakeWallet.refusal("NO_ACTIVE_BONUS")
          case Some(g) => end(g.grantRef); Answer.ok(money(player, currency, "forfeited" -> Vector(g.grantRef)))
      case "awards/cancel" =>
        named match
          case None => FakeWallet.refusal("NO_ACTIVE_BONUS")
          case Some(g) if body.bool("requireBaseline").contains(true) && !g.baseline => FakeWallet.refusal("MISSING_BASELINE")
          case Some(g) =>
            val clawback = if g.baseline then math.max(0L, math.min(g.winnings, realOf(player, currency))) else 0L
            real.update(wallet, realOf(player, currency) - clawback)
            end(g.grantRef)
            Answer.ok(money(player, currency, "clawbackAmount" -> clawback, "forfeited" -> Vector(g.grantRef)))
      case "awards/close-pending-cap" =>
        named.filter(_.pendingCap) match
          case None    => FakeWallet.refusal("NOT_PENDING_CAP")
          case Some(g) => end(g.grantRef); Answer.ok(Json.obj("closedRecordId" -> g.grantRef))
      case "awards/switch" =>
        named match
          case None => FakeWallet.refusal("GRANT_NOT_FOUND", 404)
          case Some(g) =>
            val top = open(player, currency).map(_.priority).maxOption.getOrElse(0L)
            if g.priority == top then Answer.ok(Json.obj("priority" -> g.priority, "alreadyPrimary" -> true))
            else
              alter(player, currency, g.grantRef)(_.copy(priority = top + 1))
              Answer.ok(Json.obj("priority" -> (top + 1), "alreadyPrimary" -> false))
      case "provider-credits" =>
        val amount = body.long("amount").getOrElse(0L)
        val primary = open(player, currency).filterNot(_.pendingCap).sortBy(-_.priority).headOption
        primary match
          case Some(p) => alter(player, currency, p.grantRef)(r => r.copy(amount = r.amount + amount))
          case None    => real.updateWith(wallet)(b => Some(b.getOrElse(0L) + amount)): Unit
        Answer.ok(money(player, currency, "txnId" -> s"txn-$key", "landedOn" -> primary.fold("real")(_.grantRef)))
      case _ => FakeWallet.notFound

  private def money(player: String, currency: String, fields: (String, Any)*): ObjectNode =
    val o = Json.obj("realBalance" -> real.getOrElse((player, currency), 0L),
      "bonusBalance" -> open(player, currency).map(_.amount).sum, "currency" -> currency)
    fields.foreach((k, v) => Json.set(o, k, v))
    o

  private def restricted(player: String, currency: String): ObjectNode =
    val rs = open(player, currency).sortBy(-_.priority).map { r =>
      Json.obj("recordId" -> r.grantRef, "grantRef" -> r.grantRef, "bonusRef" -> r.bonusRef,
        "status" -> (if r.pendingCap then "converted_pending_cap" else "active"), "priority" -> r.priority, "balance" -> r.amount,
        "wageringRequirement" -> r.requirement, "releaseProgress" -> 0L, "maxConvertible" -> r.amount)
    }
    Json.obj("records" -> Json.arr(rs*), "headline" -> rs.headOption, "restrictedBalance" -> open(player, currency).map(_.amount).sum)

  private def balance(player: String, currency: String): ObjectNode =
    val primary = open(player, currency).sortBy(-_.priority).headOption
    Json.obj("realBalance" -> real.getOrElse((player, currency), 0L), "restrictedBalance" -> open(player, currency).map(_.amount).sum,
      "currency" -> currency, "primary" -> primary.map(p => Json.obj("recordId" -> p.grantRef, "grantRef" -> p.grantRef)))

object FakeWallet:
  /** The keys the wallet remembers (spec Dependencies). */
  val RecentKeys = 1000

  def refusal(code: String, status: Int = 409): Answer =
    Answer.json(status, Json.obj("error" -> code, "message" -> code, "details" -> Json.obj()))

  val notFound: Answer = Answer(404, """{"error":"NOT_FOUND"}""")

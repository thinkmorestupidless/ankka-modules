package io.eitheror.casino.reporting.port

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.TextNode
import io.eitheror.casino.reporting.it.{FactSet, Kit, Reply, SharedKit}
import io.eitheror.casino.reporting.port.Parity.Difference
import io.eitheror.casino.reporting.reads.Rounds

import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import java.time.LocalDate
import java.util.UUID
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * The world `reporting-seed` made on the original, published through the facts the service consumes, on a today of its
 * own (`Kit.freshToday`): one player registered in GB in EUR, who logs in, deposits 100.00 through a payment provider,
 * plays two game sessions of five rounds (stakes of 1.00; wins of 2.50, 0.40 and 1.20), is given an operator's bonus
 * of 10.00 with no offer (`manual:<uuid>`, `MANUAL`, as rewards' operator award) and excludes themselves. The
 * recordings' period is the thirty days to today, as the driver's.
 */
final class World(val kit: Kit):
  /**
   * A today of its own, a hundred and twenty days past any other suite's, so the longest window a recording reads (the
   * rankings' 90 days) holds this world's facts alone: the kit's brand is every suite's.
   */
  val today: LocalDate =
    val day = kit.freshToday().plusDays(120)
    kit.moveClock(java.time.Duration.between(kit.now, day.atTime(10, 0).atZone(kit.zone).toInstant).toMillis.millis)
    day
  val from: LocalDate  = today.minusDays(29)
  val tag              = UUID.randomUUID().toString.take(8)
  val player           = UUID.randomUUID().toString
  val email            = s"reporting-email-$tag@example.test"
  val login            = UUID.randomUUID().toString
  val game             = UUID.randomUUID().toString
  val otherGame        = UUID.randomUUID().toString
  val provider         = "reporting-driver-psp"

  /** The rounds, by game session: the game, its name, the round, the stake and the win. */
  val rounds: Vector[(String, String, String, String, Long, Long)] = Vector(
    (game, "book-of-ra", "Book of Ra", "A", 1_00L, 2_50L), (game, "book-of-ra", "Book of Ra", "B", 1_00L, 0L),
    (game, "book-of-ra", "Book of Ra", "C", 1_00L, 40L), (otherGame, "fake-slot-002", "Fake Slots II", "D", 1_00L, 0L),
    (otherGame, "fake-slot-002", "Fake Slots II", "E", 1_00L, 1_20L))

  private val facts = FactSet(kit.zone)

  locally {
    val s = facts
    s.register(player, s.at(today, "07:00"), "EUR", country = "GB", email = Some(email))
    s.loggedIn(player, s.at(today, "07:01"))
    s.login(player, login, s.at(today, "07:01"), None, agent = "Go-http-client/1.1")
    s.payment(player, "EUR", provider, 100_00, Some(s.at(today, "07:02")), "captured", Some(s.at(today, "07:03")))
    s.deposit(player, "EUR", 100_00, s.at(today, "07:03"))
    s.gameSession(player, game, "book-of-ra", login, s.at(today, "07:05"))
    s.gameSession(player, otherGame, "fake-slot-002", login, s.at(today, "07:06"))
    rounds.zipWithIndex.foreach { case ((session, gameId, name, r, stake, win), i) =>
      val round = s"$tag-round-$r"
      val at    = s.at(today, f"07:${10 + i}%02d")
      s.bet(player, "EUR", round, stake, stake, at)
      if win > 0 then s.win(player, "EUR", round, win, win, at.plusSeconds(2)) else s.loss(player, "EUR", round, at.plusSeconds(2))
      s.settled(player, "EUR", round, "st8", gameId, name, session, stake, win, at.plusSeconds(2))
    }
    s.grant(player, "EUR", 10_00, s.at(today, "07:30"), offer = s"manual:${UUID.randomUUID()}", name = "Free Bonus", offerType = "MANUAL")
    s.award(player, "EUR", s"$tag-award", 10_00, 300_00, s.at(today, "07:30"))
    s.status(player, "player_self_excluded", "self_excluded", s.at(today, "07:40"))
    s.publish(kit)
  }

  private def json(r: Reply): Option[JsonNode] = Parity.parse(r.text)

  /**
   * Waits until every read the recordings make holds the world: today's figures and provider, the player's movements,
   * registration and feed, the bonus and the rounds. Today is the world's own, so today's figures are its alone; the
   * period may hold an earlier world's too.
   */
  locally {
    val g     = kit.settings.generation
    val range = s"from=$from&to=$today"
    def ask(rest: String) = kit.get(rest, kit.asFinance)
    kit.eventually("the world's figures today", 60.seconds)(json(ask("/dashboard/kpis")).filter(b =>
      b.path("ggrToday").path("value").asText == "0.90" && b.path("depositsToday").path("count").asInt == 1))
    kit.eventually("the provider today", 60.seconds)(json(ask("/dashboard/payment-health")).filter(
      _.path("pspBreakdown").elements.asScala.exists(_.path("pspName").asText == provider)))
    kit.eventually("the player's movements in the ledger", 60.seconds)(json(ask(
      s"/reports/transactions?$range&playerEmail=${URLEncoder.encode(email, UTF_8)}")).filter(_.path("totalCount").asInt == 12))
    kit.eventually("the player in the player report", 60.seconds)(json(ask(s"/reports/players?$range&country=GB&status=all")).filter(
      _.path("players").elements.asScala.exists(p => p.path("playerId").asText == player && p.path("status").asText == "self_excluded")))
    kit.eventually("the player's feed", 60.seconds)(json(ask("/dashboard/activity")).filter(b =>
      Set("registration", "bonus_grant", "self_exclusion").subsetOf(b.path("events").elements.asScala
        .filter(_.path("playerId").asText == player).map(_.path("eventType").asText).toSet)))
    kit.eventually("the bonus in the bonus report", 60.seconds)(json(ask(s"/reports/bonus?$range&offerId=all")).filter(
      _.path("offers").elements.asScala.exists(_.path("offerName").asText == "Free Bonus")))
    kit.eventually("the award in the bonus summary", 60.seconds)(json(ask("/dashboard/bonus-summary")).filter(
      _.path("activeBonusCount").asInt >= 1))
    for (session, n) <- Seq(game -> 3, otherGame -> 2) do
      kit.eventually(s"$session's rounds matched", 60.seconds)(Option.when(
        Rounds.ofGameSession(kit.views, g, session).count(_.status == "matched") == n)(()))
  }

  /** A recorded placeholder's value in this world. */
  def value(name: String): Option[String] = name match
    case "playerId"           => Some(player)
    case "playerSessionId"    => Some(login)
    case "gameSessionId"      => Some(game)
    case "otherGameSessionId" => Some(otherGame)
    case "from"               => Some(from.toString)
    case "to"                 => Some(today.toString)
    case "flagOffBrand"       => Some(kit.brand)
    case "unknownPlayerId"    => Some(UUID.randomUUID().toString)
    case "unknownSessionId"   => Some(UUID.randomUUID().toString)
    case _                    => None

object World:
  private var made: Option[World] = None

  /**
   * The world the recordings read, none writing to it: made once, and again on a new today when another suite of the JVM
   * has moved the kit's today since (`Kit.freshToday`), as the dashboard reads today.
   */
  def current: World = synchronized {
    val kit = SharedKit.kit
    made.filter(_.today == kit.today).getOrElse {
      val w = World(kit)
      made = Some(w)
      w
    }
  }

/** What the service answered a step. */
final case class Observed(status: Int, headers: Map[String, String], body: Option[JsonNode], text: String)

/**
 * Replays one recording on the shared world: each step's placeholders filled from it, its path moved from the original's
 * `/v1/casino/backoffice/<brand>` to the endpoint's `/v1/casino/brands/<slug>/reporting` (contracts/routes.md), sent as
 * a brand admin (`{operatorToken}`) or an operator of the brand with no other role (`{supportToken}`), and compared.
 * `reports-flag-off` hides the Reports tab for its steps, the brand's setting standing for the original's second brand.
 */
object Replayed:
  private val Placeholder = """\{([A-Za-z0-9]+)\}""".r
  private val Backoffice  = """^/v1/casino/backoffice/[^/]+(/.*)$""".r

  def replay(name: String): Vector[Difference] =
    val world    = World.current
    val kit      = world.kit
    val recorded = Parity.load(name)
    val hidden   = name == "reports-flag-off"
    val before   = kit.reporting
    if hidden then kit.setReporting(_.copy(reportsTab = false))
    try
      recorded.recording.steps.zipWithIndex.flatMap { (step, i) =>
        val observed = send(world, step.request)
        compare(world, name, i, step, recorded.headers.lift(i).getOrElse(Map.empty), observed)
      }
    finally if hidden then kit.setReporting(_ => before)

  private def fill(world: World, text: String): String =
    Placeholder.replaceAllIn(text, m => java.util.regex.Matcher.quoteReplacement(world.value(m.group(1)).map(encodeIfQuery).getOrElse(m.matched)))

  private def encodeIfQuery(v: String): String = URLEncoder.encode(v, UTF_8)

  def send(world: World, r: io.eitheror.casino.contract.port.Recording.Request): Observed =
    val kit  = world.kit
    val path = fill(world, r.path) match
      case Backoffice(rest) => kit.path(rest)
      case other            => throw AssertionError(s"not one of the original's backoffice routes: $other")
    val as = if r.headers.get("Authorization").exists(_.contains("{supportToken}")) then kit.asOperator else kit.asFinance
    observed(kit.request(r.method, path, headers = as))

  private def observed(reply: Reply): Observed =
    val headers = Seq("Content-Type", "Content-Disposition").flatMap(h => reply.header(h).map(h -> _)).toMap
    val json    = headers.get("Content-Type").exists(_.startsWith("application/json"))
    Observed(reply.status, headers, if json then Parity.parse(reply.text) else Some(TextNode(reply.text)), reply.text)

  private def mediaType(v: String): String = v.takeWhile(_ != ';').trim.toLowerCase

  private def compare(world: World, name: String, i: Int, step: io.eitheror.casino.contract.port.Recording.Step,
      headers: Map[String, String], first: Observed): Vector[Difference] =
    def at(what: String) = s"steps[$i].$what"
    val expected = step.response
    // A dispute report over the synchronous limit is a job (FR-022): its document, once served, is the answer compared.
    val (queued, o) =
      if first.status == 202 && expected.status == 200 then
        val reference = first.body.map(_.path("reference").asText).getOrElse("")
        val served = world.kit.eventually(s"document $reference served", 120.seconds)(
          Some(world.kit.get(s"/documents/$reference", world.kit.asFinance)).filter(_.status != 202))
        (Vector(Difference(name, at("status"), "200", "202 (a job, then its document)")), observed(served))
      else (Vector.empty, first)
    val status =
      if o.status != expected.status then Vector(Difference(name, at("status"), expected.status.toString, s"${o.status} ${o.text.take(300)}"))
      else Vector.empty
    val recordedType = headers.getOrElse("Content-Type", "")
    val observedType = o.headers.getOrElse("Content-Type", "")
    val dispute      = step.request.path.contains("/dispute-report") && expected.status == 200
    val contentType =
      if dispute then
        if recordedType.replace(" ", "").equalsIgnoreCase(observedType.replace(" ", "")) then Vector.empty
        else Vector(Difference(name, at("headers.Content-Type"), recordedType, observedType))
      else if mediaType(recordedType) != mediaType(observedType) then
        Vector(Difference(name, at("headers.Content-Type"), recordedType, observedType))
      else Vector.empty
    val disposition =
      val (e, x) = (headers.get("Content-Disposition").map(Parity.disposition), o.headers.get("Content-Disposition").map(Parity.disposition))
      if e != x then Vector(Difference(name, at("headers.Content-Disposition"), e.getOrElse("absent"), x.getOrElse("absent")))
      else Vector.empty
    val body = (expected.body, o.body) match
      case (Some(e), Some(x)) if e.isTextual && mediaType(recordedType) == "text/html" && x.isTextual =>
        Parity.htmlDifferences(name, at("html"), e.asText, x.asText)
      case (Some(e), Some(x)) if e.isTextual && mediaType(recordedType) == "text/csv" && x.isTextual =>
        Parity.csvDifferences(name, at("csv"), e.asText, x.asText)
      case (Some(e), Some(x)) => Parity.shape(name, at("body"), e, x)
      case (None, None)       => Vector.empty
      case (e, x)             => Vector(Difference(name, at("body"), e.fold("absent")(Parity.typeOf), x.fold("absent")(Parity.typeOf)))
    queued ++ status ++ contentType ++ disposition ++ body ++ Values.compared(world, name, i, expected.body, o.body)

/**
 * The values a shape does not hold that the world decides: what the original answered whatever its figures (a refusal's
 * text, the currency, the range, the hard-coded rate) and what the world's own player must show (its feed's events).
 */
object Values:
  def compared(world: World, name: String, i: Int, expected: Option[JsonNode], observed: Option[JsonNode]): Vector[Difference] =
    def at(what: String) = s"steps[$i].values.$what"
    def text(n: Option[JsonNode], field: String) = n.filter(_.isObject).flatMap(b => Option(b.get(field))).map(_.toString)
    def same(field: String): Vector[Difference] =
      (text(expected, field), text(observed, field)) match
        case (Some(e), o) if !o.contains(e) => Vector(Difference(name, at(field), e, o.getOrElse("absent")))
        case _                              => Vector.empty
    val common = Vector("error", "currency", "range").flatMap(same)
    val own = name match
      case "dashboard-payment-health" =>
        val rates = (n: Option[JsonNode]) => n.toVector.flatMap(_.path("pspBreakdown").elements.asScala.map(_.path("successRatePct").toString))
        same("depositSuccessRatePct") ++
          (if rates(expected) != rates(observed) then Vector(Difference(name, at("pspBreakdown[].successRatePct"), rates(expected).mkString(", "),
            rates(observed).mkString(", "))) else Vector.empty)
      case "dashboard-activity" =>
        // The recording's player is the seed's: the newest entry.
        def kinds(n: Option[JsonNode], player: Option[String]) =
          val events = n.toVector.flatMap(_.path("events").elements.asScala)
          val who    = player.orElse(events.headOption.map(_.path("playerId").asText))
          events.filter(e => who.contains(e.path("playerId").asText)).map(_.path("eventType").asText).distinct.sorted
        val (e, o) = (kinds(expected, None), kinds(observed, Some(world.player)))
        if e != o then Vector(Difference(name, at("events[].eventType"), e.mkString(", "), o.mkString(", "))) else Vector.empty
      case _ => Vector.empty
    common ++ own

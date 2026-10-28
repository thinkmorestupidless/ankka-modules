package io.eitheror.casino.attribution.port

import com.fasterxml.jackson.databind.JsonNode
import io.eitheror.casino.attribution.Json
import io.eitheror.casino.attribution.carriers.FeedFact
import io.eitheror.casino.attribution.it.{Kit, SharedKit}
import io.eitheror.casino.attribution.money.{DayCloser, OpenDays}
import io.eitheror.casino.attribution.port.Translate.*
import io.eitheror.casino.attribution.tag.Pseudonyms
import io.eitheror.casino.contract.facts.*
import io.eitheror.casino.contract.port.Recording

import java.nio.file.{Files, Path, Paths}
import java.time.{Instant, LocalDate, LocalTime}
import java.util.UUID
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * Replays one recording of tests/port/attribution on the shared kit through `Translate`
 * (contracts/recording.md): a click is sent and its answer compared field by field, a notification is
 * published as the fact that now carries it, a twin read is awaited as the facts the port emits instead,
 * and a link's seed is put in `FakeNetwork`'s legacy tag document. Every recorded `{placeholder}` is given
 * a value of this replay's own, since the database outlives replays.
 */
object Parity:
  val root: Path = Paths.get(sys.props.getOrElse("casino.root", "../..")).toAbsolutePath.normalize
  val directory: Path = root.resolve("tests/port/attribution")

  def recordings(directory: Path): Vector[Path] =
    if !Files.isDirectory(directory) then Vector.empty
    else Files.list(directory).iterator.asScala.filter(p => p.toString.endsWith(".json") && !p.getFileName.toString.startsWith("suite-result")).toVector.sorted

  final case class Difference(recording: Path, step: String, at: String, expected: String, observed: String):
    def message: String = s"${recording.getFileName} $step $at: expected $expected, observed $observed"

  /**
   * Where the port answers differently from the original on purpose: the row of plan.md's *Divergences*
   * (its first words), the steps it holds at, the fields it covers (`*` for every one at the step), and
   * why. `observable` says the recording must still show the difference; it is set once T035's recordings
   * show where the original's answer and the port's differ, and not before.
   */
  final case class Divergence(row: String, why: String, holds: (Recording, Recording.Step) => Boolean, fields: Seq[String],
      observable: Boolean = false):
    def reached(recording: Recording): Boolean = recording.steps.exists(holds(recording, _))
    def covers(field: String): Boolean =
      fields.contains("*") || fields.exists(f => field == f || field.startsWith(f + ".") || field.startsWith(f + "["))

  private def notification(kind: String)(s: Recording.Step): Boolean =
    s.request.path.matches(s"/internal/v1/affiliates/[^/]+/notify/$kind")
  private def click(s: Recording.Step): Boolean = s.request.path == "/v1/track/click"
  private def read(twin: String)(s: Recording.Step): Boolean = s.request.path.startsWith(s"/twins/$twin-")
  /** An affiliate fact the original wrote with GGR it left out: money converted from restricted funds. */
  private def excluded(s: Recording.Step): Boolean =
    s.response.body.flatMap(b => Option(b.get("twins"))).exists(_.elements().asScala.exists(t =>
      Option(t.get("properties")).flatMap(p => Option(p.get("excludedGgrCents"))).exists(c => c.asText.toLongOption.exists(_ != 0))))

  val divergences: Vector[Divergence] = Vector(
    Divergence("The click answers the `clickId` the link carried", "the port answers the click id sent; the original minted its own",
      (_, s) => click(s), Seq("$.clickId")),
    Divergence("A click's tag is verified", "a refused tag answers ok false with the reason; the original answered unknown",
      (_, s) => click(s), Seq("$.ok", "$.reason")),
    Divergence("`first-deposit` once in a player's life", "the original counted a first deposit per CPA period",
      (r, s) => read("affiliate-revenue")(s) && r.steps.count(notification("deposit")) > 2, Seq("*")),
    Divergence("`withdrawal-requested` at the hold", "the original was told once, at the operator's approval",
      (_, s) => notification("withdrawal")(s) || notification("withdrawal-reversal")(s), Seq("*")),
    Divergence("`bonus-cost` at the wallet's award", "the original's call always failed 400 and recorded nothing",
      (_, s) => notification("bonus-cost")(s), Seq("*")),
    Divergence("`chargeback` from a dispute opened or a refund", "nothing in the original called its route",
      (_, s) => notification("chargeback")(s), Seq("*")),
    Divergence("GGR per player per reporting day", "money converted from restricted funds counts in the port",
      (r, s) => (r.scenario.contains("ConvertedMoney") && notification("ggr")(s)) || (read("affiliate-facts")(s) && excluded(s)), Seq("*"),
      observable = true),
    Divergence("The seven `notify/*` routes are not served", "every input is a fact on a brand topic now",
      (_, s) => s.request.path.contains("/notify/"), Seq("$.ok", "$.affiliateId", "$.duplicate", "$.error"))
  )

  def diverging(recording: Recording): Vector[Divergence] = divergences.filter(_.reached(recording))

  /** The divergence that marks a difference at a step, if one does. */
  def divergence(recording: Recording)(d: Difference): Option[Divergence] =
    recording.steps.find(_.name == d.step).flatMap(step => divergences.find(v => v.holds(recording, step) && v.covers(d.at)))

  // ── the replay ──────────────────────────────────────────────────────────────────────────────────

  def replay(recording: Recording, kit: Kit = SharedKit.kit): Vector[Difference] =
    kit.reset()
    // A placeholder is a fresh UUID, the shape the original's ids and tags had (a bare-UUID tag is
    // `malformed` in any other); a step's captures take the port's own answer, for the steps after it.
    val values = scala.collection.mutable.Map.empty[String, String]
    val Placeholder = """\{(\w+)\}""".r
    def fill(text: String): String = Placeholder.replaceAllIn(text, m =>
      java.util.regex.Matcher.quoteReplacement(values.getOrElseUpdate(m.group(1), UUID.randomUUID().toString)))
    // The recording's instants move onto a reporting day of this replay's own (10:00 the next day, their
    // spacing kept), since a day another replay closed would make its rounds adjustments; and the clock
    // goes past the latest of them, so money stamped with the kit's `now` is not before the binding.
    val instants = Translate.instants(recording)
    val shift: Instant => Instant = instants.minOption match
      case None => identity
      case Some(first) =>
        val to = kit.now.atZone(kit.zone).toLocalDate.plusDays(1).atTime(LocalTime.of(10, 0)).atZone(kit.zone).toInstant
        _.plus(java.time.Duration.between(first, to))
    instants.maxOption.map(shift).foreach { last =>
      kit.moveClock(last.plusSeconds(60))
      kit.refreshDocuments(): Unit
    }
    val translate = Translate.stepwise(kit.zone, fill, kit.now, shift)
    val network = kit.network.id
    recording.steps.flatMap(translate).flatMap {
      case Replay(step) =>
        val reply = kit.request(step.request.method, fill(step.request.path),
          step.request.body.map(b => fill(Json.render(b))), step.request.headers.toSeq.map((k, v) => k -> fill(v)))
        Json.parse(reply.text).foreach(body =>
          step.capture.foreach((name, path) => lookup(body, path).foreach(v => values(name) = v)))
        compare(recording, step, reply)
      case Publish(topic, fact) =>
        kit.publish(topic.asInstanceOf[Topic[Fact]], fact)
        Vector.empty
      case Seed(tag, affiliate, link) =>
        kit.network.legacy = kit.network.legacy.updated(tag, affiliate -> link)
        kit.moveClock(kit.now.plusSeconds(kit.network.cacheSeconds + 1))
        kit.refreshDocuments(): Unit
        Vector.empty
      case e: Expect => expect(recording, kit, network, e)
      case e: ExpectNone => expectNone(recording, kit, network, e)
      case Skip(_, _) => Vector.empty
    }

  private def pseudonymOf(kit: Kit, network: String, player: String): String =
    val key = kit.testKit.secrets.get(Pseudonyms.secretName(network)).getOrElse(throw AssertionError(s"no pseudonym key for $network"))
    Pseudonyms.derive(Pseudonyms.keyBytes(key), player)

  private def amountOf(fact: AttributionFact): Option[Long] = fact match
    case f: FirstDeposit => Some(f.amount.value)
    case f: Deposit      => Some(f.amount.value)
    case f: GgrDaily     => Some(f.ggr.value)
    case f: GgrAdjustment => Some(f.ggr.value)
    case f: GgrRound     => Some(f.ggr.value)
    case _               => None

  private def referenceOf(fact: AttributionFact): Option[String] = fact match
    case f: FirstDeposit => Some(f.reference)
    case f: Deposit      => Some(f.reference)
    case f: GgrDaily     => Some(f.reportingDay)
    case _               => None

  /**
   * The reporting day closed as its timer would: once the player's day is open in the view (their legs
   * read), when `waitForPlay`, the clock past `CLOSE_GRACE` after midnight and the closer run.
   */
  private def close(kit: Kit, player: String, day: String, waitForPlay: Boolean): Unit =
    val next = LocalDate.parse(day).plusDays(1)
    if waitForPlay then
      kit.eventually(s"$player's day $day open")(kit.views.forView(OpenDays).ask(OpenDays.openBefore, "day" -> next.toString)
        .find(r => r.player == player && r.day == day)): Unit
    val at = next.atTime(LocalTime.of(0, 21)).atZone(kit.zone).toInstant
    if at.isAfter(kit.now) then kit.moveClock(at)
    kit.refreshDocuments(): Unit
    DayCloser.closing(kit.components, kit.clock).run(): Unit

  private def expectNone(recording: Recording, kit: Kit, network: String, e: ExpectNone): Vector[Difference] =
    close(kit, e.playerId, e.day, waitForPlay = false)
    Thread.sleep(2000)
    val pseudonym = pseudonymOf(kit, network, e.playerId)
    val found = kit.factsOn(network).collect { case f: PlayerAttributionFact if f.pseudonym == pseudonym => f }
      .filter(f => FeedFact.of(f).kind == e.kind)
    found.map(f => Difference(recording.path, e.from, "fact", s"no ${e.kind} for ${e.playerId} on ${e.day}",
      s"${e.kind} ${amountOf(f).getOrElse("")} ${referenceOf(f).getOrElse("")}".trim))

  private def expect(recording: Recording, kit: Kit, network: String, e: Expect): Vector[Difference] =
    if e.kind == "ggr-daily" then e.reference.foreach(close(kit, e.playerId, _, waitForPlay = true))
    val pseudonym = pseudonymOf(kit, network, e.playerId)
    def matching = kit.factsOn(network).collect { case f: PlayerAttributionFact if f.pseudonym == pseudonym => f }
      .filter(f => FeedFact.of(f).kind == e.kind)
      .filter(f => e.amount.forall(a => amountOf(f).contains(a)) && e.reference.forall(r => referenceOf(f).contains(r)))
    val found = scala.util.Try(kit.eventually(s"a ${e.kind} fact", 15.seconds)(matching.headOption)).toOption
    val step  = if e.from.nonEmpty then e.from else s"expect ${e.kind}"
    // What the player's record says, so a missing fact names why the port bound nothing.
    def record = scala.util.Try(kit.record(e.playerId)).toOption
      .map(r => s"; the record: binding ${r.binding.map(_.network)}, refused ${r.refused.map(_.reason)}").getOrElse("")
    if found.isDefined then Vector.empty
    else Vector(Difference(recording.path, step, "fact", s"${e.kind} for ${e.playerId} ${e.amount.getOrElse("")} ${e.reference.getOrElse("")}".trim,
      kit.factsOn(network).collect { case f: PlayerAttributionFact if f.pseudonym == pseudonym => f }
        .map(f => s"${FeedFact.of(f).kind} ${amountOf(f).getOrElse("")} ${referenceOf(f).getOrElse("")}".trim).mkString("[", ", ", "]") + record))

  /** The status and every recorded field of the answer, the fields that varied between runs for presence only. */
  private def compare(recording: Recording, step: Recording.Step, reply: io.eitheror.casino.attribution.it.Reply): Vector[Difference] =
    def diff(at: String, expected: String, observed: String) = Difference(recording.path, step.name, at, expected, observed)
    val status = Option.when(reply.status != step.response.status)(diff(".status", step.response.status.toString, reply.status.toString))
    val observed = Json.parse(reply.text)
    val fields = step.response.body.toVector.flatMap(flatten("$", _)).flatMap { (path, expected) =>
      val got = observed.flatMap(o => lookup(o, path))
      (got, step.vary.contains(path) || recording.vary.contains(path)) match
        case (None, _)                              => Some(diff(path, expected, "absent"))
        case (Some(_), true)                        => None
        case (Some(value), false) if value != expected => Some(diff(path, expected, value))
        case _                                      => None
    }
    status.toVector ++ fields

  private def flatten(prefix: String, node: JsonNode): Vector[(String, String)] =
    if node.isObject then node.properties().asScala.toVector.flatMap(e => flatten(s"$prefix.${e.getKey}", e.getValue))
    else if node.isArray then node.elements().asScala.toVector.zipWithIndex.flatMap((n, i) => flatten(s"$prefix[$i]", n))
    else Vector(prefix -> Json.scalar(node))

  private def lookup(node: JsonNode, path: String): Option[String] =
    flatten("$", node).find(_._1 == path).map(_._2)

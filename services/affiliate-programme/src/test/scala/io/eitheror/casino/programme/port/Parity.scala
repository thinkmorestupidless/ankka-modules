package io.eitheror.casino.programme.port

import com.fasterxml.jackson.databind.JsonNode
import io.eitheror.casino.contract.port.{JsonPath, Recording}
import io.eitheror.casino.programme.it.{FakeServer, Kit, Reply, SharedKit}
import io.eitheror.casino.programme.ledger.{Ledger, LedgerRow}
import io.eitheror.casino.programme.port.Translate.*

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import java.security.MessageDigest
import java.util.UUID
import scala.jdk.CollectionConverters.*

/**
 * Replays one recording on the shared kit through `Translate` (contracts/recording.md): a route is sent as
 * the manager or the affiliate the recording names and its answer compared field by field; a fact is
 * published on the replay's brand's topic; a seeded twin becomes kit setup; a facts read is the ledger's
 * GGR. Every recorded `{placeholder}` takes a value of this replay's own, since the database outlives replays.
 */
object Parity:
  val root: Path = Paths.get(sys.props.getOrElse("casino.root", "../..")).toAbsolutePath.normalize
  val programme: Path   = root.resolve("tests/port/programme")
  val attribution: Path = root.resolve("tests/port/attribution")

  def recordings(directory: Path): Vector[Path] =
    if !Files.isDirectory(directory) then Vector.empty
    else Files.list(directory).iterator.asScala
      .filter(p => p.toString.endsWith(".json") && !p.getFileName.toString.startsWith("suite-result")).toVector.sorted

  /** attribution's recordings that read the original's ledger: its revenue or its GGR facts. */
  def ledgerReads(recording: Recording): Boolean =
    recording.steps.exists(s => s.request.method == "GET" &&
      (s.request.path.startsWith("/twins/affiliate-revenue-") || s.request.path.startsWith("/twins/affiliate-facts-")))

  final case class Difference(recording: Path, step: String, at: String, expected: String, observed: String):
    def message: String = s"${recording.getFileName} $step $at: expected $expected, observed $observed"

  /**
   * Where the programme answers differently from the original on purpose: the change of spec.md's
   * *Changes from the original* it demonstrates, the steps it holds at, the fields it covers (`*` for every
   * one at the step) and why (tests/port/programme/README.md *Marked divergences*).
   */
  final case class Divergence(change: Int, why: String, holds: Recording.Step => Boolean, fields: Seq[String]):
    def covers(field: String): Boolean =
      fields.contains("*") || fields.exists(f => field == f || field.startsWith(f + ".") || field.startsWith(f + "["))

  private def path(p: String)(s: Recording.Step) = s.request.path.matches(p)

  val divergences: Vector[Divergence] = Vector(
    Divergence(6, "a payout is per currency; the original named one by affiliate and period",
      path(""".*/payouts.*"""), Seq("$.id", "$.payoutId", "$.payouts", "$.currency")),
    Divergence(11, "CPA is in the plan's currency; the original took the deposit's",
      path(""".*revenue.*"""), Seq("$.cpaCommission", "$.currency")),
    Divergence(14, "an override figure is an adjustment fact; the original rewrote the payout's override and total",
      s => s.request.method == "PATCH" && s.request.path.contains("/payouts/"), Seq("$.overrideCommission", "$.total", "$.adjustments")),
    Divergence(15, "a period ends at a UTC boundary by one clock; the original's was its server's month",
      path(""".*"""), Seq("$.period")),
    Divergence(38, "no player id leaves the brand: players are pseudonyms (FR-038)",
      _ => true, Seq("$.playerId", "$.players"))
  )

  def divergence(recording: Recording)(d: Difference): Option[Divergence] =
    recording.steps.find(_.name == d.step).flatMap(step => divergences.find(v => v.holds(step) && v.covers(d.at)))

  /** A player's pseudonym in a replay: stable for the player, as a brand's key makes it. */
  def pseudonym(player: String): String =
    "ps-" + MessageDigest.getInstance("SHA-256").digest(player.getBytes(UTF_8)).take(10).map(b => f"${b & 0xff}%02x").mkString

  // ── the replay ──────────────────────────────────────────────────────────────────────────────────

  def replay(recording: Recording, kit: Kit = SharedKit.kit, brand: String = "goldvibe"): Vector[Difference] =
    kit.rewire()
    kit.reset()
    val values = scala.collection.mutable.Map("brand" -> brand)
    val Placeholder = """\{(\w+)\}""".r
    def fill(text: String): String = Placeholder.replaceAllIn(text, m =>
      java.util.regex.Matcher.quoteReplacement(values.getOrElseUpdate(m.group(1), UUID.randomUUID().toString)))
    var plans = Map.empty[String, String] // the recording's plan id → the programme's
    // The clock past the latest instant the recording carries, so no fact is in the kit's future.
    Translate.instants(recording).maxOption.filter(_.isAfter(kit.now)).foreach(last => kit.moveClock(last.plusSeconds(60)))
    val translate = Translate.stepwise(brand, fill, pseudonym, () => kit.now)
    recording.steps.flatMap(translate).flatMap {
      case Replay(r) =>
        val headers = r.auth match
          case Auth.Nobody        => kit.fromInternet
          case Auth.Manager       => kit.asManager(Seq(brand), role = "brand_admin")
          case Auth.Affiliate(who) => kit.asAffiliate(fill(s"{$who}"))
          case Auth.AffiliateId(id) => kit.asAffiliate(id)
        def send() = kit.request(r.method, fill(r.path), r.body.map(fill), headers)
        // A read is of views, which follow the facts: it is asked again until it agrees, or 20 s pass.
        val (reply, differences) =
          if r.method != "GET" || !r.compare then
            val once = send()
            (once, if r.compare then compare(recording, r, once, fill) else Vector.empty)
          else
            val deadline = System.nanoTime() + 20_000_000_000L
            Iterator.continually { val x = send(); (x, compare(recording, r, x, fill)) }
              .tapEach(x => if x._2.nonEmpty && System.nanoTime() < deadline then Thread.sleep(500))
              .find(x => x._2.isEmpty || System.nanoTime() >= deadline).get
        parse(reply).foreach(body => r.capture.foreach((name, at) => JsonPath.get(body, at).map(Translate.scalar).foreach(v => values(name) = v)))
        differences
      case Publish(fact) =>
        kit.publish(fact)
        Vector.empty
      case p: SetupPlan =>
        val kind = p.planType.toLowerCase match
          case "revshare" => "revshare"
          case "hybrid"   => "hybrid"
          case _          => "cpa"
        plans += p.plan -> kit.plan(p.brand, s"${p.name}-${UUID.randomUUID().toString.take(6)}", kind, p.cpaAmount, p.currency, p.revShareBp, p.minimumDeposit)
        Vector.empty
      case a: SetupAffiliate =>
        kit.affiliate(a.affiliate, a.brand, plans.getOrElse(a.plan, a.plan)): Unit
        Vector.empty
      case l: SetupLink =>
        kit.link(l.link, l.affiliate, l.brand): Unit
        Vector.empty
      case e: ExpectGgr => expectGgr(recording, kit, e)
      case Skip(_, _)   => Vector.empty
    }

  private def parse(reply: Reply): Option[JsonNode] =
    scala.util.Try(FakeServer.mapper.readTree(reply.text)).toOption.filter(n => n != null && !n.isMissingNode)

  /** The ledger's GGR facts for the round's player, summed, as the original's facts twin answered. */
  private def expectGgr(recording: Recording, kit: Kit, e: ExpectGgr): Vector[Difference] =
    val ids = e.factIds.map(id => s"ggr/$id").toSet
    def rows: Vector[LedgerRow] = kit.views.forView(Ledger).ask(Ledger.ofAffiliate, "affiliate" -> e.affiliate, "brand" -> e.brand,
      "from" -> "0000-00", "to" -> "9999-99").filter(r => r.currency == e.currency && ids(r.factId))
    def sum = rows.map(_.amount).sum
    val observed =
      if ids.isEmpty then 0L
      else scala.util.Try(kit.eventually(s"the ledger's GGR for ${e.affiliate}")(Option(sum).filter(_ == e.amount))).getOrElse(sum)
    Option.when(observed != e.amount)(Difference(recording.path, e.from, "ggr", e.amount.toString,
      s"$observed of ${kit.views.forView(Ledger).ask(Ledger.ofAffiliate, "affiliate" -> e.affiliate, "brand" -> e.brand, "from" -> "0000-00",
        "to" -> "9999-99").map(r => s"${r.currency} ${r.factId} ${r.amount}").mkString("[", ", ", "]")} for ${ids.mkString(",")}")).toVector

  /** The status and every recorded field of the answer; a field that varied between the original's runs, for presence only. */
  private def compare(recording: Recording, r: Request, reply: Reply, fill: String => String): Vector[Difference] =
    def diff(at: String, expected: String, observed: String) = Difference(recording.path, r.name, at, expected, observed)
    val status   = Option.when(reply.status != r.status)(diff(".status", r.status.toString, s"${reply.status} ${reply.text.take(200)}"))
    val observed = parse(reply)
    val fields = r.expected.toVector.flatMap(flatten("$", _)).flatMap { (at, expected) =>
      val got = observed.flatMap(o => lookup(o, at))
      (got, r.vary.contains(at) || recording.vary.contains(at)) match
        case (None, _)                                       => Some(diff(at, fill(expected), "absent"))
        case (Some(_), true)                                 => None
        case (Some(value), false) if value != fill(expected) => Some(diff(at, fill(expected), value))
        case _                                               => None
    }
    status.toVector ++ fields

  private def flatten(prefix: String, node: JsonNode): Vector[(String, String)] =
    if node.isObject then node.properties().asScala.toVector.flatMap(e => flatten(s"$prefix.${e.getKey}", e.getValue))
    else if node.isArray then node.elements().asScala.toVector.zipWithIndex.flatMap((n, i) => flatten(s"$prefix[$i]", n))
    else Vector(prefix -> Translate.scalar(node))

  private def lookup(node: JsonNode, at: String): Option[String] = flatten("$", node).find(_._1 == at).map(_._2)

package io.eitheror.casino.programme.port

import com.fasterxml.jackson.databind.JsonNode
import io.eitheror.casino.contract.port.{JsonPath, Recording}
import io.eitheror.casino.programme.it.{FakeServer, Kit, Reply, SharedKit}
import io.eitheror.casino.programme.activity.FraudFlag
import io.eitheror.casino.programme.ledger.{Ledger, LedgerRow}
import io.eitheror.casino.programme.settlement.Payouts
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
   * *Changes from the original* it demonstrates (0 for a decision research.md records, named in `why`), the steps it holds at, the fields it covers (`*` for every
   * one at the step) and why (tests/port/programme/README.md *Marked divergences*).
   */
  final case class Divergence(change: Int, why: String, holds: Recording.Step => Boolean, fields: Seq[String]):
    def covers(field: String): Boolean =
      fields.contains("*") || fields.exists(f => field == f || field.startsWith(f + ".") || field.startsWith(f + "["))

  private def path(p: String)(s: Recording.Step) = s.request.path.matches(p)

  val divergences: Vector[Divergence] = Vector(
    Divergence(6, "a payout is per currency; the original named one by affiliate and period",
      path(""".*/payouts.*"""), Seq("$.id", "$.payoutId", "$.payouts", "$.currency")),
    Divergence(6, "a view page's figures are per brand and currency, and a period with no facts has none; the original answered its counts flat, zero",
      path(""".*/view/(dashboard|stats).*"""), Seq("$.fraudFlagCount", "$.registrationCount")),
    Divergence(11, "CPA is in the plan's currency; the original took the deposit's",
      path(""".*revenue.*"""), Seq("$.cpaCommission", "$.currency")),
    Divergence(0, "the programme also tells an affiliate in the portal of an invitation, a reset request and a payment (research.md T076), so marking all read marks those too",
      s => s.request.path.contains("/notifications"), Seq("$.marked")),
    Divergence(0, "a bonus cost lowers NGR (research.md, found during implementation); the original never stored the bonus-cost fact (its id is not a UUID), so the chargeback's recompute left it out",
      s => s.name.startsWith("read revenue for the period: "),
      Seq("bonusCosts", "ngr", "revShareCommission", "totalCommission").map(f => s"$$.figures[0].$f")),
    Divergence(13, "a payout moves only through its transitions and is refused as one, naming the status (research.md T057); the original answered 400 for pending and spelt its codes two ways",
      s => s.request.method == "PATCH" && s.request.path.endsWith("/status") && s.response.status >= 400, Seq(".status", "$.error", "$.message")),
    Divergence(14, "an override figure is an adjustment fact; the original rewrote the payout's override and total",
      s => s.request.method == "PATCH" && s.request.path.contains("/payouts/"), Seq("$.overrideCommission", "$.total", "$.adjustments")),
    Divergence(15, "a period ends at a UTC boundary by one clock; the original's was its server's month",
      path(""".*"""), Seq("$.period")),
    Divergence(17, "an audit row names the event, the actor and when, never the affiliate's identity or a token; the original's rows were its event twins",
      path(""".*/audit.*"""), Seq("$")),
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
      java.util.regex.Matcher.quoteReplacement(values.getOrElseUpdate(m.group(1), derived(m.group(1)).getOrElse(UUID.randomUUID().toString))))
    // The names the original mints from others, minted as the programme mints them.
    def derived(name: String): Option[String] = name match
      case "codeLower"      => Some(fill("{code}").toLowerCase)
      case "payout"         => Some(Payouts.wireId(fill("{affiliate}"), Translate.DefaultCurrency, fill("{period}")))
      case "rejectedPayout" => Some(Payouts.wireId(fill("{rejected}"), Translate.DefaultCurrency, fill("{period}")))
      case "flag"           => Some(FraudFlag.flagId(brand, pseudonym(fill("{player}")), FraudFlag.BonusCost))
      case _                => None
    var plans = Map.empty[String, String] // the recording's plan id → the programme's
    // The clock past the latest instant the recording carries, so no fact is in the kit's future.
    Translate.instants(recording).maxOption.filter(_.isAfter(kit.now)).foreach(last => kit.moveClock(last.plusSeconds(60)))
    // The driver's periods are the months around the day it ran; the replay's, around the kit's clock.
    val month = java.time.YearMonth.from(kit.now.atZone(java.time.ZoneOffset.UTC))
    values ++= Seq("period" -> month.toString, "previousPeriod" -> month.minusMonths(1).toString, "nextPeriod" -> month.plusMonths(1).toString)
    val translate = Translate.stepwise(brand, fill, pseudonym, () => kit.now)
    // Step by step: a step is translated once the ones before it have answered, so it names what they captured.
    withPriorState(recording.steps).iterator.flatMap(translate).flatMap {
      case Replay(r) =>
        val headers = r.auth match
          case Auth.Nobody        => kit.fromInternet
          case Auth.Manager       => kit.asManager(Seq(brand), role = "brand_admin")
          case Auth.Affiliate(who) => kit.asAffiliate(fill(s"{$who}"))
          case Auth.AffiliateId(id) => kit.asAffiliate(id)
        def send() = kit.request(r.method, fill(r.path), r.body.map(fill), headers)
        // A close reads the accounts' unclaimed facts from a view; the original read them in the request.
        if r.path.contains("/payouts/close") then
          values.values.filter(_.startsWith("aff-")).toSet.foreach(aff =>
            scala.util.Try(kit.owed(aff, brand, Translate.DefaultCurrency)): Unit)
        // A read is of views, which follow the facts: it is asked again until it agrees, or 20 s pass.
        val (reply, differences) =
          if r.method != "GET" || !r.compare then
            // A close opens its payouts and a fact raises its flag a moment later: a command on one the original
            // found is retried while it is not yet there.
            val deadline = System.nanoTime() + 20_000_000_000L
            val once = Iterator.continually(send())
              .tapEach(x => if x.status == 404 && r.status != 404 && System.nanoTime() < deadline then Thread.sleep(500))
              .find(x => x.status != 404 || r.status == 404 || System.nanoTime() >= deadline).get
            (once, if r.compare then compare(recording, r, once, fill) else Vector.empty)
          else
            val deadline = System.nanoTime() + 20_000_000_000L
            Iterator.continually { val x = send(); (x, compare(recording, r, x, fill)) }
              .tapEach(x => if x._2.nonEmpty && System.nanoTime() < deadline then Thread.sleep(500))
              .find(x => x._2.isEmpty || System.nanoTime() >= deadline).get
        if sys.env.contains("PARITY_TRACE") then println(s"TRACE ${r.name} ${r.method} ${fill(r.path)} -> ${reply.status} ${reply.text.take(600)}")
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
    }.toVector

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
    // A text answer (the original's ACL refusal) is compared as the one string it is.
    val observed = parse(reply).orElse(Option(reply.text).filter(_.nonEmpty).map(t => FakeServer.mapper.getNodeFactory.textNode(t)))
    // A varied path covers what is under it: `$` varied is a brand-wide list whose rows earlier runs fill.
    // A captured field is bound from this answer, so it is present, not compared.
    val varied = (r.vary ++ recording.vary.keySet ++ r.capture.values).toSet
    def under(at: String) = varied.exists(v => at == v || at.startsWith(v + ".") || at.startsWith(v + "["))
    // Lists are compared as sets of rows: the original and the programme order them differently.
    val expectedFilled = r.expected.map(e => ordered(FakeServer.mapper.readTree(fill(FakeServer.mapper.writeValueAsString(e)))))
    val orderedObserved = observed.map(o => ordered(withoutNewKinds(r, expectedFilled, o)))
    val fields = expectedFilled.toVector.flatMap(flatten("$", _)).flatMap { (at, expected) =>
      val got = orderedObserved.flatMap(o => lookup(o, at))
      (got, under(at)) match
        case (_, true) if !varied(at)                        => None
        case (None, _)                                       => Some(diff(at, expected, "absent"))
        case (Some(_), true)                                 => None
        case (Some(value), false) if !Translate.same(at, expected, value) => Some(diff(at, expected, value))
        case _                                               => None
    }
    status.toVector ++ fields

  /** A brand's settings and programme page, the singletons every run of the original shares. */
  private val Singleton = """/v1/affiliates/\{brand\}/(settings|programme)""".r

  /**
   * The steps, with a brand singleton read before the recording changes it first set to what the original
   * answered: that answer is what earlier runs left, the state the run started from.
   */
  def withPriorState(steps: Vector[Recording.Step]): Vector[Recording.Step] =
    val seen = scala.collection.mutable.Set.empty[String]
    steps.flatMap { s =>
      s.request.path match
        case Singleton(which) if !seen(which) =>
          seen += which
          val prior = Option.when(s.request.method == "GET" && s.response.status == 200)(s.response.body).flatten
            .filter(b => b.isObject && steps.exists(t => t.request.method == "PUT" && t.request.path == s.request.path))
            .map { b =>
              val body = b.deepCopy[com.fasterxml.jackson.databind.node.ObjectNode]()
              body.remove(java.util.List.of("brandId", "origin", "previousGgrSource", "ggrSourceFrom"))
              Recording.Step(s"setup: the $which the original's earlier runs left", Recording.Request("PUT", s.request.path,
                Map("Authorization" -> "Bearer {staffToken}"), Some(body)), Recording.Response(200, None), Map.empty)
            }
          prior.toVector :+ s
        case _ => Vector(s)
    }

  /** The notification kinds the programme writes and the original did not (research.md T076). */
  val NewNotificationKinds = Set("INVITED", "PASSWORD_RESET_REQUESTED", "PAYOUT_PAID")

  /** A notification list without the kinds the original never wrote, when its own list holds none of them. */
  private def withoutNewKinds(r: Request, expected: Option[JsonNode], observed: JsonNode): JsonNode =
    def kind(n: JsonNode) = Option(n.get("type")).map(_.asText).getOrElse("")
    val recorded = expected.filter(_.isArray).map(_.elements().asScala.map(kind).toSet).getOrElse(Set.empty)
    if !r.path.contains("/notifications") || !observed.isArray || recorded.exists(NewNotificationKinds) then observed
    else
      val out = FakeServer.mapper.createArrayNode()
      observed.elements().asScala.filterNot(n => NewNotificationKinds(kind(n))).foreach(out.add)
      out

  /** The fields a list's rows are told apart by, in the order they are tried. */
  private val RowKeys = Vector("code", "btag", "linkId", "flagId", "payoutId", "affiliateId", "action", "type", "period", "planName")

  /** The node with every array of objects sorted by the first row key its rows carry, case aside. */
  private def ordered(node: JsonNode): JsonNode =
    import com.fasterxml.jackson.databind.node.{ArrayNode, ObjectNode}
    node match
      case o: ObjectNode =>
        val out = o.deepCopy()
        o.properties().asScala.foreach(e => out.set(e.getKey, ordered(e.getValue)): Unit)
        out
      case a: ArrayNode =>
        val rows = a.elements().asScala.toVector.map(ordered)
        val key  = RowKeys.find(k => rows.nonEmpty && rows.forall(r => r.isObject && r.has(k)))
        val out  = FakeServer.mapper.createArrayNode()
        key.fold(rows)(k => rows.sortBy(r => Translate.scalar(r.get(k)).toLowerCase)).foreach(out.add)
        out
      case other => other

  private def flatten(prefix: String, node: JsonNode): Vector[(String, String)] =
    if node.isObject then node.properties().asScala.toVector.flatMap(e => flatten(s"$prefix.${e.getKey}", e.getValue))
    else if node.isArray then node.elements().asScala.toVector.zipWithIndex.flatMap((n, i) => flatten(s"$prefix[$i]", n))
    else Vector(prefix -> Translate.scalar(node))

  private def lookup(node: JsonNode, at: String): Option[String] = flatten("$", node).find(_._1 == at).map(_._2)

package io.eitheror.casino.ingest.port

import com.fasterxml.jackson.databind.JsonNode
import io.eitheror.casino.contract.port.{Recording, Replay}
import io.eitheror.casino.ingest.Json
import io.eitheror.casino.ingest.Json.*
import io.eitheror.casino.ingest.it.{Answer, FakeMerchant, Kit, RawKey, Received, Reply, SharedKit}

import java.nio.file.{Files, Path, Paths}
import java.util.UUID
import scala.jdk.CollectionConverters.*

/**
 * Replays one recording of tests/port/ingest against the shared kit (contracts/recording.md, *Replay*).
 * The original's `merchant-service` was the edge and the cashier in one process, so a public step's
 * recorded answer is both what `ingest` must return and what `FakeMerchant` answers the forwarded command
 * with. What is not this service's is stood in for:
 *
 * - every step that is not one of the four public routes (the realm, the operator, the hierarchy, the
 *   integration, substrate, the tree, the gateway's captures and pages, the players, the twins) is
 *   answered as recorded;
 * - the key-generation step (`POST /v1/merchants/{subBrand}/keys`, `201`) is answered as recorded and its
 *   `rawKey` issued to that sub-brand as `merchant_key_issued` would, awaited in the index; each public
 *   step then carries it as `merchantKey` (the Go suite's recorder keeps no such header). The driver's
 *   recordings (tests/original/ingest-driver) seed instead: the seed's `merchantKey` is issued to its
 *   `merchantId` under the name the step captures it as, and a step's recorded `merchantKey` header says
 *   which key it sent (`{name}`), that it sent none (empty), or the made-up key it sent, spelt as it was;
 *   the driver's write of the keys twin inactive deactivates the key;
 * - `FakeMerchant` answers a forwarded command with the step's recorded answer only when the command is
 *   what `ingest` must send: the create under the key's sub-brand with `amountCents` as `amount`, the
 *   submit and the read under the ticket and payment the recording names. Anything else it answers
 *   `599` naming what it received, so a wrong command is a difference;
 * - `GET /health` is the runtime's readiness, not a route.
 */
object Parity:
  val root: Path = Paths.get(sys.props.getOrElse("casino.root", "../..")).toAbsolutePath.normalize

  def recordings(directory: Path): Vector[Path] =
    if !Files.isDirectory(directory) then Vector.empty
    else Files.list(directory).iterator.asScala.filter(p => p.toString.endsWith(".json") && !p.getFileName.toString.startsWith("suite-result")).toVector.sorted

  /**
   * Where the service answers differently from the original on purpose (plan.md *Divergences*, named by the
   * row's first words in `row`): the fields, the steps it holds at, and why. Where the difference is that
   * `merchant` is not asked (`unasked`), the answer may match the original's, so the replay reports the
   * step's `.merchant` as not asked, which this divergence marks.
   */
  final case class Divergence(row: String, fields: Seq[String], why: String, holds: (Recording, Recording.Step) => Boolean, unasked: Boolean = false):
    def reached(recording: Recording): Boolean = recording.steps.exists(holds(recording, _))
    def covers(field: String): Boolean         = fields.exists(f => field == f || field.startsWith(f + ".") || field.startsWith(f + "["))

  private val CreatePath = "/v1/cashier/sessions"
  private val SubmitPath = """/v1/cashier/sessions/([^/]+)/submit""".r
  private val AbandonPath = """/v1/cashier/sessions/([^/]+)/abandon""".r
  private val ReadPath   = """/v1/payments/([^/?]+)""".r
  private val KeysPath   = """/v1/merchants/([^/]+)/keys""".r
  private val SeedPath   = """/internal/v1/seed/merchants/.+""".r
  private val KeysTwin   = """/twins/eitheror-merchant-keys/.+""".r
  private val Placeholder = """\{(\w+)\}""".r

  /** The step's `merchantKey` header as recorded, whatever its case: its name and value. */
  private def keyHeader(s: Recording.Step): Option[(String, String)] = s.request.headers.find(_._1.equalsIgnoreCase("merchantKey"))

  private def creates(s: Recording.Step) = s.request.method == "POST" && s.request.path == CreatePath
  private def body(s: Recording.Step)    = s.request.body.getOrElse(Json.obj())
  private def recordedError(s: Recording.Step) = s.response.body.flatMap(_.text("error"))
  private def wholeAmount(s: Recording.Step)   = Option(body(s).get("amountCents")).forall(n => n.isIntegralNumber && n.asLong > 0)

  /** The FR-007 fields `ingest` checks before `merchant` is asked. */
  private val Checked = Vector("amountCents", "currency", "customerId", "country", "redirectUrl")

  val divergences: Vector[Divergence] = Vector(
    Divergence("A missing `merchantKey`", Seq(".status", ".body"), "a missing merchantKey is 401 UNAUTHENTICATED (FR-005, correction 4)",
      (_, s) => creates(s) && keyHeader(s).exists(_._2.isEmpty) && s.response.status != 401),
    Divergence("A missing or malformed", Seq(".status", ".body"), "a missing or malformed field is 400 INVALID_REQUEST naming it, and merchant is not asked (FR-007)",
      (_, s) => creates(s) && s.response.status == 200 && (Checked.exists(f => !body(s).has(f)) || !wholeAmount(s))),
    Divergence("A missing or malformed", Seq(".status", ".body"), "a field the original refused as no routing context is 400 INVALID_REQUEST naming it (FR-007)",
      (_, s) => creates(s) && recordedError(s).contains("MISSING_ROUTING_CONTEXT")),
    Divergence("A `customerId` without its brand", Seq(".status", ".body"), "a player id without its brand is refused (FR-007)",
      (_, s) => creates(s) && body(s).text("customerId").exists(!_.contains(':')) && s.response.status == 200),
    Divergence("A repeat under one `transactionId`", Seq(".status", ".body", ".merchant"), "a repeat under one request id is the first session, or 409 with another body (FR-010)",
      (r, s) => creates(s) && body(s).text("transactionId").exists(t => r.steps.takeWhile(_ ne s).exists(p =>
        creates(p) && body(p).text("transactionId").contains(t) && keyHeader(p).map(_._2) == keyHeader(s).map(_._2))), unasked = true),
    Divergence("A payment is read only", Seq(".status", ".body"), "a payment is read only by its sub-brand's key or its session's ticket (FR-009)",
      (_, s) => s.request.method == "GET" && ReadPath.matches(s.request.path) && keyHeader(s).exists(_._2.startsWith("{other"))),
    Divergence("A submit or abandon under a ticket", Seq(".status", ".body", ".merchant"), "a ticket ingest never saw is 404 without asking merchant (R7)",
      (r, s) => SubmitPath.matches(s.request.path) && !r.steps.exists(p => p.capture.values.exists(_ == "$.session_token")), unasked = true)
  )

  def diverging(recording: Recording): Vector[Divergence] = divergences.filter(_.reached(recording))

  private val StepField = """\$\.steps\[(\d+)\]\.response(.*)""".r

  def divergence(recording: Recording)(d: Replay.Difference): Option[Divergence] = d.at match
    case StepField(i, field) =>
      val step = recording.steps.lift(i.toInt)
      divergences.find(v => v.covers(field) && step.exists(v.holds(recording, _)))
    case _ => None

  /** Whether a step is one of this service's four public routes. */
  def ours(r: Recording.Request): Boolean =
    (r.method == "POST" && (r.path == CreatePath || SubmitPath.matches(r.path) || AbandonPath.matches(r.path))) ||
      (r.method == "GET" && ReadPath.matches(r.path.takeWhile(_ != '?')))

  private final class Replaying(kit: Kit, recording: Recording):
    var step  = -1 // Replay.run sends the steps in order
    var key   = Option.empty[RawKey]
    val named = scala.collection.mutable.Map.empty[String, RawKey]
    var unasked = Vector.empty[Replay.Difference]

    /** The key header a public step sends: as recorded, or the recording's key when none was recorded. */
    private def keyOf(s: Recording.Step): (Seq[(String, String)], Option[RawKey]) = keyHeader(s) match
      case None                         => (key.toSeq.map(_.header), key)
      case Some((_, ""))                => (Nil, None)
      case Some((name, Placeholder(n))) => named.get(n).fold((Seq(name -> s"{$n}"), Option.empty[RawKey]))(k => (Seq(name -> k.raw), Some(k)))
      case Some((name, raw))            => (Seq(name -> raw), None)

    private def answered(r: Reply): Recording.Response =
      Recording.Response(r.status, Option.when(r.text.trim.nonEmpty)(Json.parse(r.text).getOrElse(Json.mapper.getNodeFactory.textNode(r.text))))

    private def asRecorded(s: Recording.Step): Answer =
      Answer(s.response.status, s.response.body.map(Json.render).getOrElse(""))

    /** `FakeMerchant`'s answer to the command a public step forwards: the recorded one, for the command it must be. */
    private def script(s: Recording.Step, expected: Received => Option[String]): Unit =
      kit.merchant.recorded = { case got if FakeMerchant.routeOf(got).isDefined =>
        expected(got).fold(asRecorded(s))(why => Answer(599, Json.render(Json.obj("unexpected" -> why, "received" -> s"${got.method} ${got.path} ${got.body}"))))
      }

    def send(r: Recording.Request): Recording.Response =
      step += 1
      val s = recording.steps(step)
      r.path match
        case KeysPath(subBrand) if r.method == "POST" && s.response.status == 201 =>
          s.response.body.flatMap(_.text("rawKey")).foreach { raw =>
            val k = RawKey(raw, s"k-${UUID.randomUUID().toString.take(8)}", subBrand)
            kit.issue(k)
            key = Some(k)
          }
          s.response
        case SeedPath() if r.method == "POST" && s.response.status == 200 =>
          for
            answer   <- s.response.body
            raw      <- answer.text("merchantKey")
            subBrand <- answer.text("merchantId")
          do
            val k = RawKey(raw, s"k-${UUID.randomUUID().toString.take(8)}", subBrand)
            kit.issue(k)
            s.capture.collect { case (name, "$.merchantKey") => name }.foreach(named(_) = k)
            if key.isEmpty then key = Some(k)
          s.response
        case KeysTwin() if r.method == "POST" =>
          key.foreach(kit.deactivateKey(_))
          s.response
        case _ if !ours(r) => s.response
        case _ if r.path.contains("{") => Recording.Response(0, Some(Json.obj("unfilled" -> r.path)))
        case _ =>
          val (headers, used) = keyOf(s)
          val sent = r.body.map(Json.render)
          r.path match
            case CreatePath =>
              script(s, got =>
                val c = got.json.getOrElse(Json.obj())
                val amount = r.body.flatMap(_.long("amountCents"))
                if FakeMerchant.routeOf(got) != Some(FakeMerchant.Create) then Some("not a create")
                else if c.text("subBrandId") != used.map(_.subBrand) then Some(s"subBrandId ${c.text("subBrandId")}, not the key's")
                else if c.long("amount") != amount then Some(s"amount ${c.long("amount")}, not amountCents $amount")
                else None)
            case SubmitPath(ticket) =>
              script(s, got => Option.when(got.path != s"/v1/cashier/sessions/$ticket/submit")(s"not the submit of $ticket"))
            case AbandonPath(ticket) =>
              script(s, got => Option.when(got.path != s"/v1/cashier/sessions/$ticket/abandon")(s"not the abandon of $ticket"))
            case ReadPath(id) =>
              script(s, got => Option.when(got.path != s"/v1/cashier/payments/$id")(s"not the read of $id"))
          val before = kit.merchant.received.size
          val reply  = answered(kit.request(r.method, r.path, sent, kit.asSubBrand ++ headers))
          if kit.merchant.received.size == before && divergences.exists(d => d.unasked && d.holds(recording, s)) then
            unasked :+= Replay.Difference(recording.path, s"$$.steps[$step].response.merchant", "asked", "not asked")
          reply

  def replay(recording: Recording): Vector[Replay.Difference] =
    val kit = SharedKit.kit
    kit.reset()
    val replaying = Replaying(kit, recording)
    try Replay.run(recording, replaying.send) ++ replaying.unasked
    finally kit.reset()

package io.eitheror.casino.pspgateway.port

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.contract.port.{JsonPath, Recording, Replay}
import io.eitheror.casino.pspgateway.Json
import io.eitheror.casino.pspgateway.Json.*
import io.eitheror.casino.pspgateway.it.{Kit, PspStub, Reply, ScriptStep, SharedKit}
import io.eitheror.casino.pspgateway.providers.Digests
import io.eitheror.casino.pspgateway.providers.dreamzpay.DreamzPay
import io.eitheror.casino.pspgateway.providers.t365.T365
import io.eitheror.casino.pspgateway.providers.unityfinance.UnityFinance
import io.eitheror.casino.pspgateway.sessions.*

import java.net.{URLDecoder, URLEncoder}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import java.util.UUID
import scala.collection.mutable
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.Try

/**
 * Replays one recording of tests/port/psp-gateway against the shared kit, psp-stub its providers
 * (tests/port/README.md, *psp-gateway*). What is not this service's is stood in for:
 *
 * - psp-stub's steps go to the kit's psp-stub for the recording's provider (its scenario's suffix, else
 *   the provider its first initiate names): its controls and webhooks as recorded, the Morphosis token the
 *   original minted replaced by this replay's session's, and the player's browser posting DreamzPay's relay
 *   form as the kit's `relay`;
 * - the receiver (the original's merchant-service and casino-service stand-in) is the kit's fakes: a terminal
 *   is the sub-brand's credential, its redacted values psp-stub's and the rest as recorded (none for a terminal
 *   with no properties; a fresh signing key when BounceMoney's terminal is put again), and a payout provider's
 *   terminal is also the brand's withdrawal terminal; a script answers the payment's outcomes in turn;
 * - an initiate is `merchant`'s, the payment id the one its `callbackUrl` names (`/v1/payments/{id}/capture`);
 * - a payout is a brand's `payouts` calling `POST /v1/payments/withdrawals` (contracts/payouts.md), the amount
 *   in minor units, and its answer and status read put in the original's words for the provider: the status in
 *   upper case, the request id as `paymentRef` (`paymentNo` for Unity Finance), the provider's reference as
 *   `cozeniTxnId`, `wpbId` or `ufWithdrawalId`, a status read as `resolved`, `outcome` and `neverCreated`;
 * - the original's re-drive route is the `Redrive` timer's command to each payment of the replay, or an
 *   operator's re-drive when the step says an operator re-drives it, once each payment's delivery has ended;
 * - a forged webhook is signed again, as the driver signed it, over what this replay sends, unless it is the
 *   one signed under another key; a Morphosis forgery names this replay's session's token and Morphosis id.
 *
 * The steps the replayer does not compare are compared here where they are the service's behaviour: an
 * observe of the provider calls by their number, method and path (`…response.calls`), and an observe of the
 * receiver by each outcome told, its payment, verb and the answer it had (`…response.delivered`).
 */
object Parity:
  val root: Path = Paths.get(sys.props.getOrElse("casino.root", "../..")).toAbsolutePath.normalize

  /** Every recording under `directory`, one directory per scenario with a file per provider, or a file. */
  def recordings(directory: Path): Vector[Path] =
    if !Files.isDirectory(directory) then Vector.empty
    else Files.walk(directory).iterator.asScala.filter(p => p.toString.endsWith(".json") && !p.getFileName.toString.startsWith("suite-result")).toVector.sorted

  /** The provider a recording is of: its scenario's suffix, else the provider its first initiate names. */
  def provider(recording: Recording): Provider =
    val suffix = recording.scenario.split('/').last
    Provider.values.find(_.path == suffix)
      .orElse(recording.steps.flatMap(_.request.body).flatMap(_.text("psp")).flatMap(Provider.from).headOption)
      .getOrElse(Provider.Digitarium)

  /**
   * Where the service answers differently from the original on purpose (plan.md Divergences, the findings of
   * tests/port/README.md and the decisions of spec.md Clarifications): the fields of the answer, the steps it
   * holds at, and why. A difference there is named in the test, not failed, and must still be there.
   */
  final case class Divergence(fields: Seq[String], why: String, holds: (Recording, Recording.Step) => Boolean):
    def reached(recording: Recording): Boolean = recording.steps.exists(holds(recording, _))
    def covers(field: String): Boolean         = fields.exists(f => field == f || field.startsWith(f + ".") || field.startsWith(f + "["))

  private def initiates(s: Recording.Step) = s.request.method == "POST" && s.request.path == "/v1/psp/hpp/initiate"
  private def pays(s: Recording.Step)      = s.request.method == "POST" && s.request.path.startsWith("/v1/psp/payout/")
  private def forged(s: Recording.Step)    = s.name.startsWith("webhook (forged)")
  private def text(s: Recording.Step)      = s.response.body.filter(_.isTextual).map(_.asText).getOrElse("")
  private def of(r: Recording, ps: Provider*) = ps.contains(provider(r))
  private def stubObserve(s: Recording.Step)  = s.name.startsWith("observe:") && s.request.service.contains("psp-stub")
  private def told(s: Recording.Step)         = s.name.startsWith("observe:") && s.request.service.contains("receiver")
  private def before(r: Recording, s: Recording.Step): Vector[Recording.Step] = r.steps.take(r.steps.indexWhere(_ eq s))
  private def nonEmpty(s: Recording.Step)     = s.response.body.exists(_.size > 0)
  private def statusRead(s: Recording.Step)   = s.request.method == "GET" && s.request.path.contains("/status")
  private def reference(s: Recording.Step)    = s.request.body.flatMap(_.text("reference"))

  val divergences: Vector[Divergence] = Vector(
    // ── US1: deposits (T036) ──
    Divergence(Seq(".status", ".body"), "a deposit for a sub-brand with no credential for the provider is refused 404 naming both (FR-018), not 502",
      (_, s) => initiates(s) && s.response.status == 502 && text(s).contains("credentials configured")),
    Divergence(Seq(".calls"), "a repeat initiate under one payment id returns the first answer and calls no provider (FR-008); the original called it again",
      (r, s) => stubObserve(s) && before(r, s).lastOption.exists(_.name.startsWith("initiate again under the same payment id"))),
    Divergence(Seq(".status", ".body"), "a webhook whose signature does not verify is refused 400 by every signing provider (FR-011, the decision of 2026-10-19), not acknowledged",
      (r, s) => forged(s) && s.name.contains("another key") && s.response.status == 200 &&
        of(r, Provider.Cozeni, Provider.EuPaymentz, Provider.T365, Provider.UnityFinance)),
    Divergence(Seq(".status", ".body"), "a signed webhook whose amount does not match its session does not verify (FR-013) and is refused 400 as a bad signature is (the decision of 2026-10-20); the original captured it",
      (r, s) => forged(s) && s.name.contains("amount tampered") && s.response.status == 200 &&
        of(r, Provider.DreamzPay, Provider.Digitarium, Provider.BounceMoney, Provider.T365, Provider.EuPaymentz, Provider.UnityFinance)),
    Divergence(Seq(".delivered"), "a webhook whose amount does not match its session settles nothing (FR-013, correction 7); the original captured it (a crypto overpayment still captures, FR-005)",
      (r, s) => told(s) && nonEmpty(s) && !of(r, Provider.Cozeni, Provider.Morphosis) && before(r, s).exists(p => forged(p) && p.name.contains("amount tampered"))),
    Divergence(Seq(".delivered"), "a webhook arriving again for a settled session tells the cashier nothing again (FR-013); the original sent the capture twice",
      (r, s) => told(s) && { val hooks = before(r, s).filter(_.name.startsWith("webhook:")).flatMap(reference); hooks.distinct.size < hooks.size }),
    Divergence(Seq(".status", ".body"), "a webhook for a reference the gateway does not know is acknowledged (FR-012), not answered 502",
      (_, s) => forged(s) && s.name.contains("unknown-ref") && s.response.status == 502),
    // ── US2: crypto (T044) ──
    Divergence(Seq(".delivered"), "a Morphosis confirmation whose received amount is unreadable is treated as short and fails underpaid (FR-005); the original settled nothing on it",
      (r, s) => told(s) && of(r, Provider.Morphosis) && before(r, s).exists(p => forged(p) && p.name.contains("unreadable received amount"))),
    // ── US3: payouts (T048) ──
    Divergence(Seq(".calls"), "a payout repeated under its request id calls no provider (FR-008); the original called it again",
      (r, s) => stubObserve(s) && before(r, s).lastOption.exists(_.name.startsWith("initiate it again under the same request id"))),
    Divergence(Seq(".status", ".body"), "a payout repeated under its request id answers the first answer, and a duplicate reference already_initiated, both 200 (FR-008, FR-004), not 409",
      (_, s) => pays(s) && s.response.status == 409),
    Divergence(Seq(".status", ".body"), "a payout refused for its credential is answered 200, rejected missing_credentials, in contracts/payouts.md's one shape, not 400",
      (_, s) => pays(s) && s.response.status == 400 && s.response.body.flatMap(_.text("error")).contains("missing_credentials")),
    Divergence(Seq(".body.reason"), "a 401 or 403 to a payout is rejected unauthorised for every provider (the decision of 2026-10-19), not the provider's own text",
      (_, s) => pays(s) && s.response.body.flatMap(_.text("reason")).exists(_.startsWith("Auth:"))),
    Divergence(Seq(".body.error"), "a status read the provider cannot answer is 502 status_unavailable whatever the cause (contracts/payouts.md)",
      (_, s) => statusRead(s) && s.response.status == 502),
    Divergence(Seq(".calls"), "a status read for a payout its webhook settled answers from the session and asks no provider (FR-009)",
      (r, s) => stubObserve(s) && { val b = before(r, s); b.exists(statusRead) && b.exists(_.name.startsWith("webhook:")) }),
    // ── US4: outcome delivery (T054) ──
    Divergence(Seq(".status", ".body"), "a provider's webhook is answered once the outcome is recorded and the outcome delivered after it (FR-014, from a consumer), so a receiver that does not answer does not hold the webhook; the original answered 502 when its own delivery timed out",
      (_, s) => s.name.startsWith("webhook:") && s.response.status == 502 && s.response.body.flatMap(_.text("error")).exists(_.contains("deadline exceeded"))),
    Divergence(Seq(".delivered"), "a refused outcome is left by the timer's re-drive and sent again only by an operator's (FR-016); the original's re-drive sent it again, to be refused again",
      (r, s) => told(s) && before(r, s).exists(p => p.name.startsWith("time:") && !p.name.contains("operator")) &&
        r.steps.exists(p => p.request.path == "/__script" && p.request.body.exists(_.toString.contains("400")))),
    // ── US6: the player's browser (T063) ──
    Divergence(Seq(".delivered"), "DreamzPay's return POST settles nothing (correction 8); the original captured on it",
      (r, s) => told(s) && nonEmpty(s) && before(r, s).exists(_.name.startsWith("the player's browser posts the return form")))
  )

  def diverging(recording: Recording): Vector[Divergence] = divergences.filter(_.reached(recording))

  private val StepField = """\$\.steps\[(\d+)\]\.response(.*)""".r

  /** The divergence a difference is, if it is one: the step it was found at must be one the divergence holds at. */
  def divergence(recording: Recording)(d: Replay.Difference): Option[Divergence] = d.at match
    case StepField(i, field) =>
      val step = recording.steps.lift(i.toInt)
      divergences.find(v => v.covers(field) && step.exists(v.holds(recording, _)))
    case _ => None

  private val Placeholder = """\{([A-Za-z][A-Za-z0-9]*)\}""".r

  /** A fresh value for every placeholder the scenario minted; captures are left to the replayer. */
  def minting(recording: Recording): Map[String, String] =
    val captures = recording.steps.flatMap(_.capture.keys).toSet
    val text     = recording.steps.map(s => s.request.path + " " + s.request.body.map(Json.render).getOrElse("") + " " + s.request.headers.values.mkString(" ")).mkString("\n")
    (Placeholder.findAllMatchIn(text).map(_.group(1)).toSet -- captures).map(n => n -> s"$n-${UUID.randomUUID().toString.take(8)}").toMap

  def minted(recording: Recording, fresh: Map[String, String]): Recording =
    def fill(s: String) = fresh.foldLeft(s)((t, kv) => t.replace(s"{${kv._1}}", kv._2))
    recording.copy(steps = recording.steps.map(st => st.copy(request = st.request.copy(path = fill(st.request.path),
      body = st.request.body.map(b => Json.mapper.readTree(fill(Json.mapper.writeValueAsString(b))))))))

  private final class Replaying(kit: Kit, recording: Recording, fresh: Map[String, String]):
    val provider: Provider = Parity.provider(recording)
    val found = mutable.ArrayBuffer.empty[Replay.Difference]
    private var step     = -1 // the recording's step being sent: Replay.run sends them in order
    private val captured = mutable.Map.empty[String, String]
    private var seen     = kit.psp.received(provider).size // psp-stub's log is the JVM's: the calls before this replay are not its
    private var payments = Vector.empty[String]
    private var requests = Vector.empty[String]
    private var puts     = 0
    private var secrets  = PspStub.credentials(provider) // the credential this replay last set, which a forgery is signed under

    private val subBrand = fresh.getOrElse("subBrand", "sb-replay")
    private val brand    = fresh.getOrElse("brand", "brand-replay")

    private def fill(s: String): String = (fresh ++ captured).foldLeft(s)((t, kv) => t.replace(s"{${kv._1}}", kv._2))
    private def at(field: String) = s"$$.steps[$step].response.$field"
    private def differ(field: String, expected: String, observed: String): Unit =
      found += Replay.Difference(recording.path, at(field), expected, observed)

    def send(r: Recording.Request): Recording.Response =
      step += 1
      val s = recording.steps(step)
      val answer =
        if Placeholder.findFirstIn(r.path).isDefined then Recording.Response(0, Some(Json.obj("unfilled" -> r.path)))
        else r.service match
          case Some("psp-stub") => stub(r, s)
          case Some("receiver") => receiver(r, s)
          case _                => gateway(r, s)
      s.capture.foreach((name, path) => answer.body.flatMap(JsonPath.get(_, path)).foreach(v => captured(name) = v.asText))
      answer

    // ── psp-stub ──────────────────────────────────────────────────────────────────────────────────

    private def stub(r: Recording.Request, s: Recording.Step): Recording.Response = r.path match
      case "/__received" =>
        val want = s.response.body.map(_.size).getOrElse(0)
        def since = kit.psp.received(provider).drop(seen)
        val got = if want == 0 then since else Try(kit.eventually("the provider calls", 10.seconds)(Option(since).filter(_.size >= want))).getOrElse(since)
        seen += got.size
        calls(s, got)
        Recording.Response(200, Some(Json.arr(got*)))
      case "/__webhook" =>
        val body = r.body.map(_.deepCopy[JsonNode]()).collect { case o: ObjectNode => o }.getOrElse(Json.obj())
        if provider == Provider.Morphosis then body.put("reference", morphosisToken): Unit
        stubAnswer(kit.psp.call(provider, "POST", "/__webhook", Some(Json.render(body))))
      case "/transaction/Checkout" =>
        Recording.Response(payments.lastOption.flatMap(kit.relay).getOrElse(0), Some(Json.obj()))
      case path => stubAnswer(kit.psp.call(provider, r.method, path, r.body.map(Json.render)))

    private def stubAnswer(a: (Int, String)): Recording.Response =
      Recording.Response(a._1, Option.when(a._2.trim.nonEmpty)(Json.parse(a._2).getOrElse(Json.mapper.getNodeFactory.textNode(a._2))))

    /** The token Morphosis knows this replay's latest session by: the original's was minted on its own run. */
    private def morphosisToken: String =
      if requests.nonEmpty then kit.payout(requests.last).claim.flatMap(_.generated.get("token")).getOrElse(requests.last)
      else payments.lastOption.map(kit.stubReference(_, Provider.Morphosis)).getOrElse("")

    private def call(n: JsonNode): String =
      s"${n.textOr("method", "?")} ${fill(n.textOr("path", "?")).takeWhile(_ != '?')}"

    /** The provider calls as the original made them: as many, each by its method and path where it did not vary. */
    private def calls(s: Recording.Step, got: Vector[JsonNode]): Unit =
      val expected = s.response.body.toVector.flatMap(_.elements.asScala)
      if expected.size != got.size then
        differ("calls", s"${expected.size} provider calls ${expected.map(call).mkString("[", ", ", "]")}", s"${got.size} ${got.map(call).mkString("[", ", ", "]")}")
      else
        expected.zip(got).zipWithIndex.foreach { case ((e, o), i) =>
          if !s.vary.contains(s"$$[$i].path") && call(e) != call(o) then differ(s"calls[$i]", call(e), call(o))
        }

    // ── the receiver ──────────────────────────────────────────────────────────────────────────────

    private val Terminal = """/__terminals/([^/]+)""".r

    private def receiver(r: Recording.Request, s: Recording.Step): Recording.Response = r.path match
      case Terminal(id) =>
        puts += 1
        val recorded = r.body.flatMap(b => Option(b.get("properties"))).map(_.properties.asScala.map(e => e.getKey -> e.getValue.asText).toMap).getOrElse(Map.empty)
        val stubs    = PspStub.credentials(provider)
        val values   = stubs ++ recorded.map {
          case (k, "<redacted>") if k == "privateKey" && puts > 1 => k -> PspStub.freshEd25519Pem()
          case (k, "<redacted>")                                  => k -> stubs.getOrElse(k, s"replay-$k")
          case kv                                                 => kv
        }
        kit.terminal(id, values)
        secrets = values
        if recorded.isEmpty then kit.merchant.removeCredential(subBrand, provider.wire) else kit.credential(subBrand, provider, values)
        if Kit.PayoutProviders.contains(provider) then kit.payoutTerminals(brand, subBrand)
        Recording.Response(200, Some(Json.obj("properties" -> recorded.size, "terminalId" -> id)))
      case "/__script" =>
        val b     = r.body.getOrElse(Json.obj())
        val steps = b.objects("answers").map(a => ScriptStep(a.long("status").getOrElse(200L).toInt, a.long("silentMs").getOrElse(0L)))
        kit.merchant.script(b.textOr("match", ""), steps)
        Recording.Response(200, Some(Json.obj("answers" -> steps.size, "match" -> b.textOr("match", ""))))
      case path if path.startsWith("/__received") =>
        val ids = path.dropWhile(_ != '?').drop(1).split('&').toVector.collect { case kv if kv.startsWith("path=") => URLDecoder.decode(kv.drop(5), UTF_8) }
        delivered(s, ids)
      case _ => Recording.Response(404, Some(Json.obj("unknown" -> r.path)))

    private val DepositOutcome = """/v1/payments/([^/]+)/(capture|fail)""".r
    private val PayoutOutcome  = """/internal/v1/casino/withdrawal-requests/([^/]+)/outcome""".r

    /** An outcome told, as the original's receiver logged it: the session, the verb or payout outcome, and the answer it had. */
    private def told(n: JsonNode): String =
      val what = fill(n.textOr("path", "")) match
        case DepositOutcome(id, verb) => s"$id $verb"
        case PayoutOutcome(id)        => s"$id ${n.at("/body/outcome").asText("?").toLowerCase}"
        case other                    => other
      s"$what -> ${n.long("answered").getOrElse(0L)}"

    private def toldHere(ids: Vector[String]): Vector[String] =
      def mine(key: String) = ids.exists(id => key.startsWith(id + "/"))
      (kit.merchant.delivered ++ kit.payouts.delivered(brand)).filter(d => mine(d.key)).map(d => s"${d.key.replace('/', ' ')} -> ${d.answered}")

    /** The outcomes the receiver was told, waited for as the original's were, and compared with them. */
    private def delivered(s: Recording.Step, ids: Vector[String]): Recording.Response =
      val expected = s.response.body.toVector.flatMap(_.elements.asScala).map(told)
      val waited   = Try(kit.eventually("the outcomes told", 60.seconds)(Option(toldHere(ids)).filter(_.size >= expected.size))).getOrElse(toldHere(ids))
      Thread.sleep(if expected.isEmpty then 1500 else 500) // a duplicate, or an outcome that should not come, has its chance
      val got = toldHere(ids)
      // In order for each session; across sessions the order is the consumer's, not a behaviour.
      def bySession(v: Vector[String]) = v.groupBy(_.takeWhile(_ != ' ')).toVector.sortBy(_._1).flatMap(_._2)
      if bySession(got) != bySession(expected) then differ("delivered", expected.mkString("[", ", ", "]"), got.mkString("[", ", ", "]"))
      Recording.Response(200, Some(Json.arr(got*)))

    // ── the gateway ───────────────────────────────────────────────────────────────────────────────

    private val Callback = """/v1/payments/([^/]+)/capture""".r
    private val Payout   = """/v1/psp/payout/([a-z0-9]+)""".r
    private val Status   = """/v1/psp/payout/[a-z0-9]+/([^/?]+)/status.*""".r

    private def gateway(r: Recording.Request, s: Recording.Step): Recording.Response = r.path match
      case "/v1/psp/hpp/initiate" =>
        val b  = r.body.map(_.deepCopy[JsonNode]()).collect { case o: ObjectNode => o }.getOrElse(Json.obj())
        val id = b.text("callbackUrl").flatMap(Callback.findFirstMatchIn(_)).map(_.group(1)).getOrElse(UUID.randomUUID().toString)
        b.put("paymentId", id)
        if !payments.contains(id) then payments :+= id
        answered(kit.request("POST", r.path, Some(Json.render(b)), kit.asMerchant))
      case Payout(_) if r.method == "POST" => payout(r)
      case Status(id)                      => payoutStatus(id)
      case "/internal/v1/psp/redrive-undelivered" => redrive(s)
      case path if path.startsWith("/v1/webhooks/") => webhook(r, s)
      case path =>
        val (body, contentType) = raw(r)
        answered(kit.request(r.method, path, body, kit.asBrowser ++ headers(r), contentType))

    private def raw(r: Recording.Request): (Option[String], String) =
      (r.body.map(b => if b.isTextual then b.asText else Json.render(b)), r.headers.getOrElse("Content-Type", "application/json"))

    private def headers(r: Recording.Request): Seq[(String, String)] = r.headers.toSeq.filterNot(_._1.equalsIgnoreCase("Content-Type"))

    /** The answer as the recorder keeps it: JSON as it is, any other text as a JSON string, trimmed. */
    private def answered(r: Reply): Recording.Response =
      Recording.Response(r.status, Option.when(r.text.trim.nonEmpty)(Json.parse(r.text).getOrElse(Json.mapper.getNodeFactory.textNode(r.text.trim))))

    private def payout(r: Recording.Request): Recording.Response =
      val b         = r.body.getOrElse(Json.obj())
      val requestId = b.textOr("withdrawalRequestId", UUID.randomUUID().toString)
      if !requests.contains(requestId) then requests :+= requestId
      val amount = b.textOr("amount", "5000") match
        case a if a.contains('.') => (BigDecimal(a) * 100).toLongExact.toString
        case a                    => a
      val body = kit.withdrawalBody(brand, requestId, provider, "subBrandId" -> subBrand, "amount" -> amount,
        "customerId" -> b.textOr("customerId", "player-1"), "currency" -> b.textOr("currency", Kit.PayoutMethod(provider)._2))
      val reply = kit.request("POST", "/v1/payments/withdrawals", Some(body), kit.asPayouts(brand))
      Json.parse(reply.text).filter(_.has("status")).fold(answered(reply)) { a =>
        val ref    = a.text("providerReference")
        val status = a.textOr("status", "").toUpperCase
        val words: Vector[(String, Any)] = provider match
          case Provider.UnityFinance => Vector("paymentNo" -> requestId, "reason" -> a.textOr("reason", ""), "status" -> status, "ufWithdrawalId" -> ref.getOrElse(""))
          case Provider.Cozeni       => Vector("paymentRef" -> requestId, "status" -> status) ++ ref.map("cozeniTxnId" -> _)
          case Provider.Morphosis    => Vector("paymentRef" -> requestId, "status" -> status) ++ ref.map("wpbId" -> _)
          case _                     => Vector("paymentRef" -> requestId, "status" -> status)
        Recording.Response(reply.status, Some(Json.obj(words*)))
      }

    private def payoutStatus(requestId: String): Recording.Response =
      val reply = kit.withdrawal(brand, requestId)
      Json.parse(reply.text).filter(_ => reply.status == 200).flatMap(_.text("status")).fold(answered(reply)) {
        case "pending"   => Recording.Response(200, Some(Json.obj("resolved" -> false)))
        case "no_record" => Recording.Response(200, Some(Json.obj("neverCreated" -> true, "resolved" -> false)))
        case outcome     => Recording.Response(200, Some(Json.obj("outcome" -> outcome.toUpperCase, "resolved" -> true)))
      }

    private def redrive(s: Recording.Step): Recording.Response =
      val settled = payments.filter(kit.deposit(_).outcome.isDefined)
      settled.foreach(id => Try(kit.eventually(s"$id's delivery ended", 60.seconds)(Option(kit.deposit(id)).filter(_.delivery != Delivery.NotYet))))
      val redriven = settled.count { id =>
        if s.name.contains("operator") then kit.request("POST", s"/v1/psp/outcomes/$id/redrive", None, kit.asOperator(subject = "op-replay")).status == 200
        else kit.components.forEventSourcedEntity(EntityId(id)).call(DepositSession.redrive).invoke(Redrive(DepositSession.Timer, kit.now())).redriven
      }
      Recording.Response(200, Some(Json.obj("redriven" -> redriven)))

    /** A forged webhook, signed again over what this replay sends (see the object's comment). */
    private def webhook(r: Recording.Request, s: Recording.Step): Recording.Response =
      val (body, contentType) = raw(r)
      val path = r.path match
        case p if provider == Provider.Morphosis && p.startsWith("/v1/webhooks/morphosis/deposit/") && !p.endsWith("/00000000-0000-4000-8000-000000000000") =>
          s"/v1/webhooks/morphosis/deposit/$morphosisToken"
        case p => p
      val (sent, extra) = if s.name.contains("another key") then (body, headers(r)) else signed(body.getOrElse(""), headers(r))
      answered(kit.request("POST", path, Some(sent.getOrElse("")), kit.asProvider ++ extra, contentType))

    private def form(text: String): Vector[(String, String)] =
      text.split('&').toVector.filter(_.nonEmpty).map(_.split("=", 2) match
        case Array(k, v) => URLDecoder.decode(k, UTF_8) -> URLDecoder.decode(v, UTF_8)
        case Array(k)    => URLDecoder.decode(k, UTF_8) -> "")
    private def encoded(fields: Vector[(String, String)]): String =
      fields.map((k, v) => s"${URLEncoder.encode(k, UTF_8)}=${URLEncoder.encode(v, UTF_8)}").mkString("&")

    private def signed(body: String, sent: Seq[(String, String)]): (Option[String], Seq[(String, String)]) =
      provider match
        case Provider.DreamzPay =>
          val f   = form(body)
          val get = f.toMap.withDefaultValue("")
          val sum = DreamzPay.inboundChecksum(get("paymentId"), get("merchantTransactionId"), get("amount"),
            if get("transactionStatus").nonEmpty then get("transactionStatus") else get("status"), secrets("secureKey"))
          (Some(encoded(f.map((k, v) => k -> (if k == "checksum" then sum else v)))), sent)
        case Provider.T365 =>
          val b = Json.parseObject(body).get
          b.put("hash", T365.sign(secrets("secretKey"), b.textOr("amount", ""), b.textOr("currency", ""), b.textOr("orderId", ""), b.textOr("status", "")))
          (Some(Json.render(b)), sent)
        case Provider.Digitarium =>
          (Some(body), sent.filterNot(_._1 == "Signature") :+ ("Signature" -> Digests.hex(Digests.hmacSha256(secrets("signingKey"), body))))
        case Provider.BounceMoney =>
          val now = kit.now().getEpochSecond
          val b   = Json.parseObject(body).get
          b.put("created", now)
          val text = Json.render(b)
          val sig  = Digests.base64Url(Digests.hmacSha256(secrets("signingSecret"), s"$now.".getBytes(UTF_8) ++ text.getBytes(UTF_8)))
          (Some(text), sent.filterNot(_._1 == "X-BounceMoney-Signature") :+ ("X-BounceMoney-Signature" -> s"t=$now,v1=$sig"))
        case Provider.UnityFinance =>
          val f    = form(body)
          val sign = UnityFinance.sign(f.filterNot(_._1 == "ik_sign").toMap, secrets("signKey"))
          (Some(encoded(f.map((k, v) => k -> (if k == "ik_sign" then sign else v)))), sent)
        case Provider.Morphosis =>
          // The id is the one Morphosis gave the session, which on the original's run was its own.
          val b    = Json.parseObject(body).get
          val ours = payments.lastOption.flatMap(kit.deposit(_).initiation).flatMap(_.reference)
          ours.filter(_ => b.textOr("id", "").nonEmpty).foreach(b.put("id", _))
          (Some(Json.render(b)), sent)
        case _ => (Some(body), sent)

  def replay(recording: Recording): Vector[Replay.Difference] =
    val kit = SharedKit.kit
    kit.reset()
    val fresh = minting(recording)
    val r     = Replaying(kit, recording, fresh)
    try Replay.run(minted(recording, fresh), r.send) ++ r.found
    finally kit.reset()

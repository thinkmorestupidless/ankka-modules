package io.eitheror.casino.merchant.port

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.contract.port.{Recording, Replay}
import io.eitheror.casino.merchant.Json
import io.eitheror.casino.merchant.Json.*
import io.eitheror.casino.merchant.hierarchy.*
import io.eitheror.casino.merchant.it.{FakeGateway, Kit, Reply, SharedKit}
import io.eitheror.casino.merchant.keys.{MerchantKeyIndex, RawKeys}
import io.eitheror.casino.merchant.payments.Status
import io.eitheror.casino.merchant.routing.Level
import io.eitheror.casino.merchant.security.{Hmac, Secrets}

import java.nio.file.{Files, Path, Paths}
import java.time.{Instant, Duration as JDuration}
import java.util.UUID
import scala.jdk.CollectionConverters.*

/**
 * Replays one recording of tests/port/merchant against the shared kit (contracts/recording.md, *Replay*).
 * What is not this service's is stood in for, and each stand-in is a divergence of plan.md:
 *
 * - the original's seed routes (`/internal/v1/seed/hierarchy`, `/seed/merchants`) are kit setup: a
 *   holding, company and sub-brand, the `SIMULATOR` credential `SIM`, a catch-all tree to it, a key;
 * - a public cashier step under a `merchantKey` header is `ingest`'s command, the sub-brand resolved from
 *   the key's digest through the index, as `ingest` does (research R4), `amountCents` as `amount`;
 * - a capture or fail under the internal secret is sent as `psp-gateway`'s identity (the secret dropped), and one
 *   the original refused for presenting none is sent as another caller (`ingest`);
 * - a signed capture keeps its stamp's age (from the recording's newest stamp) and, where the original
 *   accepted its signature, is signed again over the body sent under the secret this replay set;
 * - the sweep `expire-stale` is the deadline passing: a recording that sweeps makes its payments under a
 *   3-second deadline, and the sweep waits for it;
 * - the internal abandon is the token-admitted abandon of the session that made the payment, and another
 *   player's is sent under a token of no session;
 * - substrate's twin writes are the kit's: a credential's webhook secret, the gateway's undelivered marker;
 * - the Go suite's operator steps are sent under a platform operator's token (the realm's token steps are
 *   answered as recorded; creating an operator reaches the kit's `FakeKeycloak`);
 * - another service's step is answered as recorded (Keycloak, the casino's players, the gateway's relay page,
 *   substrate's merchant index), except what stands for this service's work: the realm's lookup of a user by
 *   email is answered as recorded when the kit's `FakeKeycloak` holds that user with the recorded scope; the gateway's DreamzPay webhook
 *   is the capture the gateway then makes, sent as `psp-gateway`; substrate's read of the payment twin is
 *   answered as recorded when the payment is captured here; and its read of the brand's wallet when the fake
 *   wallet was credited once, for the payment's amount;
 * - a recording that reads the brand's wallet makes its sub-brand name the brand its customer reference
 *   carries: the sub-brand is the authority for the brand now (spec.md Clarifications), where the original
 *   credited a `Payments` sub-brand's deposit to the brand in `customer_ref`.
 */
object Parity:
  val root: Path = Paths.get(sys.props.getOrElse("casino.root", "../..")).toAbsolutePath.normalize

  def recordings(directory: Path): Vector[Path] =
    if !Files.isDirectory(directory) then Vector.empty
    else Files.list(directory).iterator.asScala.filter(p => p.toString.endsWith(".json") && !p.getFileName.toString.startsWith("suite-result")).toVector.sorted

  /**
   * Where the service answers differently from the original on purpose (plan.md Divergences): the fields of
   * the answer, the steps of a recording it holds at, and why. A difference there is named in the test, not
   * failed, and must still be there (it is demonstrated).
   */
  final case class Divergence(fields: Seq[String], why: String, holds: (Recording, Recording.Step) => Boolean):
    def reached(recording: Recording): Boolean = recording.steps.exists(holds(recording, _))
    def covers(field: String): Boolean         = fields.exists(f => field == f || field.startsWith(f + ".") || field.startsWith(f + "["))

  private def posts(s: Recording.Step, path: String) = s.request.method == "POST" && s.request.path == path
  private val OutcomePath = """/v1/payments/[^/]+/(capture|fail)""".r
  private val Abandon     = """/internal/v1/cashier/payments/([^/]+)/abandon.*""".r
  private val SweepPath   = """/internal/v1/cashier/payments/expire-stale(?:\?.*)?""".r

  /** A read after the sweep in a recording whose gateway holds the payment's outcome undelivered: the payment waits. */
  private def readWhileWaiting(r: Recording, s: Recording.Step): Boolean =
    val at = r.steps.indexWhere(_ eq s)
    s.request.method == "GET" && s.request.path.startsWith("/v1/payments/") &&
      r.steps.take(at).exists(_.request.path.startsWith("/twins/psp-checkout-session/")) && r.steps.take(at).exists(p => SweepPath.matches(p.request.path))

  /** A recording that reads the brand's wallet: its sub-brand is made the brand's (see the object's comment). */
  def readsWallet(recording: Recording): Boolean = recording.steps.exists(_.request.path.contains("wallet%2F"))

  /** A capture or fail the original refused for presenting no internal secret: sent as a caller other than the gateway. */
  def forged(s: Recording.Step): Boolean =
    OutcomePath.matches(s.request.path) && s.response.status == 401 && s.response.body.flatMap(_.text("error")).contains("UNAUTHORIZED")

  val divergences: Vector[Divergence] = Vector(
    Divergence(Seq(".body.resolvedFrom"), "a session's answer names the level its tree was resolved from (FR-011)",
      (_, s) => posts(s, "/v1/cashier/sessions") && s.response.status == 200),
    Divergence(Seq(".status", ".body"), "the internal secret is the gateway's identity: another caller is refused 403, not 401",
      (_, s) => forged(s)),
    Divergence(Seq(".body.withdrawalTurnoverDefinition", ".body.withdrawalTurnoverMultiplier"), "withdrawal turnover is the wallet's (spec 002 FR-004a)",
      (_, s) => posts(s, "/v1/sub-brands") && s.response.body.exists(_.has("withdrawalTurnoverMultiplier"))),
    Divergence(Seq(".body.type", ".body.brand"), "the sub-brand is the authority for the brand a deposit is credited to (spec.md Clarifications)",
      (r, s) => readsWallet(r) && posts(s, "/v1/sub-brands")),
    Divergence(Seq(".body.error"), "the abandon is admitted by the session's token: a player without it is refused SESSION_NOT_FOUND, not PAYMENT_NOT_FOUND",
      (_, s) => Abandon.matches(s.request.path) && s.response.status == 404),
    Divergence(Seq(".body.waitingReason"), "a payment waiting for an undelivered outcome says why in its read (FR-020)",
      readWhileWaiting),
    Divergence(Seq(".body.status", ".body.failureReason"), "the deadline is one clock: the original's sweep decided before expires_at, so its read was not yet past it; ours is, and reads EXPIRED (FR-017) while the payment waits",
      readWhileWaiting),
    Divergence(Seq(".status", ".body"), "health is the runtime's readiness, GET /ready on the probe port, not a route",
      (_, s) => s.request.method == "GET" && s.request.path == "/health")
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

  /** The recording with every value the scenario minted made fresh for this replay; captures are left to the replayer. */
  def minted(recording: Recording): Recording =
    val captures = recording.steps.flatMap(_.capture.keys).toSet
    val text     = recording.steps.map(s => s.request.path + " " + s.request.body.map(Json.render).getOrElse("") + " " + s.request.headers.values.mkString(" ")).mkString("\n")
    val names    = Placeholder.findAllMatchIn(text).map(_.group(1)).toSet -- captures
    val fresh    = names.map(n => n -> s"$n-${UUID.randomUUID().toString.take(8)}").toMap
    def fill(s: String) = fresh.foldLeft(s)((t, kv) => t.replace(s"{${kv._1}}", kv._2))
    recording.copy(steps = recording.steps.map(st => st.copy(request = st.request.copy(path = fill(st.request.path),
      body = st.request.body.map(b => Json.mapper.readTree(fill(Json.mapper.writeValueAsString(b))))))))

  private final class Replaying(kit: Kit, recording: Recording, recordedAt: Option[Instant]):
    var step     = -1 // the recording's step being sent: Replay.run sends them in order
    var webhookSecret = Option.empty[String]
    var subBrand = ""
    var credential = ""
    var sessionOf  = Map.empty[String, String] // payment id -> the session token that made it
    var lastToken  = ""
    var rawKey     = "" // the replayer fills paths and bodies, not headers: the key the seed minted stands for `{merchantKey}`
    var lastPayment = ""

    /** The brand a recording that reads the brand's wallet deposits to: the one its customer reference names. */
    private val walletBrand: Option[String] =
      Option.when(readsWallet(recording))(recording.steps.flatMap(_.request.body).flatMap(_.text("customerId")))
        .flatMap(_.headOption).flatMap(_.split(':').headOption)

    private val OperatorRoute = """/v1/(operators|holdings|companies|sub-brands|merchants|payments/routing-rules)(/.*)?""".r
    private val Webhook       = """/v1/webhooks/dreamzpay""".r
    private val PaymentTwin   = """/internal/twins/cashier-payment%2F([^/]+)""".r
    private val WalletTwin    = """/internal/twins/wallet%2F([^/]+)""".r
    private val RealmUser     = """/admin/realms/[^/]+/users\?email=([^&]+)""".r

    private def recorded: Recording.Response = recording.steps(step).response

    /**
     * When the original's stamps were made, in epoch seconds: the newest stamp the recording carries, or its
     * `recorded_at` rounded up, whichever is later. `recorded_at` is taken just before the steps, so a stamp's
     * age measured from it would be short by up to a second, and one made 301 seconds old would replay as 300.
     */
    private val stampedAt: Option[Long] =
      val stamps = recording.steps.flatMap(_.request.headers.get("X-PSP-Timestamp")).flatMap(_.toLongOption)
      val at     = recordedAt.map(i => i.getEpochSecond + (if i.getNano > 0 then 1 else 0))
      (stamps ++ at).maxOption

    /** Another service's step, as the object's comment says. */
    private def elsewhere(r: Recording.Request): Recording.Response = r.path match
      case Webhook() | WalletTwin(_) if lastPayment.isEmpty => Recording.Response(0, Some(Json.obj("unmade" -> "no payment was submitted")))
      case Webhook() =>
        val txn   = kit.paymentState(lastPayment).opened.map(_.pspTransactionId).getOrElse("")
        val reply = kit.request("POST", s"/v1/payments/$lastPayment/capture", Some(Json.render(Json.obj("outcome" -> "success", "psp_transaction_id" -> txn))), kit.asGateway)
        if reply.status == 200 then recorded else answered(reply)
      case PaymentTwin(id) =>
        val status = kit.paymentState(id).status
        if status == Status.Captured then recorded else ok("status" -> status)
      case WalletTwin(_) =>
        val amount   = kit.paymentState(lastPayment).opened.map(_.amount)
        val deposits = kit.wallet.depositsOf(lastPayment)
        if deposits.size == 1 && deposits.head.json.flatMap(_.long("amount")) == amount then recorded
        else ok("deposits" -> deposits.size, "amount" -> deposits.headOption.flatMap(_.json.flatMap(_.long("amount"))))
      case RealmUser(email) =>
        // The realm holds the operator this service created, with its scope: what the original's lookup found.
        val wanted = Seq("eitheror_scope_id", "eitheror_scope_level").map(a => a -> recorded.body.flatMap(b => Option(b.get(0)))
          .flatMap(u => Option(u.at(s"/attributes/$a"))).map(_.elements.asScala.map(_.asText).toVector).getOrElse(Vector.empty))
        val held = kit.keycloak.byEmail(java.net.URLDecoder.decode(email, "UTF-8"))
        val has  = held.map(u => wanted.map((a, _) => a -> kit.keycloak.attribute(u.id, a)))
        if has.contains(wanted) then recorded
        else ok("held" -> held.isDefined, "attributes" -> has.map(_.toMap.view.mapValues(_.mkString(",")).toMap.toString).getOrElse(""))
      case _ => recorded

    private def operator(r: Recording.Request, body: Option[String]): Recording.Response =
      val sent = (r.method, r.path, walletBrand) match
        case ("POST", "/v1/sub-brands", Some(brand)) =>
          body.flatMap(Json.parseObject).map { b => b.put("type", "Casino"); b.put("brand", brand); Json.render(b) }
        case _ => body
      val reply = kit.request(r.method, r.path, sent, kit.asOperator())
      Option(reply.str("rawKey")).filter(_.nonEmpty).foreach(rawKey = _)
      answered(reply)

    /** The answer as the recorder keeps it: JSON as it is, any other text as a JSON string. */
    private def answered(r: Reply): Recording.Response =
      Recording.Response(r.status, Option.when(r.text.trim.nonEmpty)(Json.parse(r.text).getOrElse(Json.mapper.getNodeFactory.textNode(r.text))))
    private def ok(fields: (String, Any)*): Recording.Response = Recording.Response(200, Some(Json.obj(fields*)))

    private val SeedHierarchy = """/internal/v1/seed/hierarchy/([^/]+)""".r
    private val SeedMerchant  = """/internal/v1/seed/merchants/([^/]+)""".r
    private val Submit        = """/v1/cashier/sessions/([^/]+)/submit""".r
    private val Read          = """/v1/payments/([^/?]+)""".r
    private val Outcome       = """/v1/payments/([^/]+)/(capture|fail)""".r
    private val CredIndex     = """/twins/eitheror-psp-cred-index/.*""".r
    private val Credential    = """/twins/eitheror-psp-credential/([^/]+)""".r
    private val Checkout      = """/twins/psp-checkout-session/([^/]+)""".r
    private val Sweep         = """/internal/v1/cashier/payments/expire-stale(?:\?.*)?""".r
    def sweeps(path: String): Boolean = Sweep.matches(path)

    /** The provider's headers as this replay would have them sent (see the object's comment). */
    private def signed(headers: Map[String, String], body: Option[String]): Seq[(String, String)] =
      val expected   = recording.steps(step).response
      val refusedSig = expected.body.flatMap(_.text("message")).contains("PSP signature verification failed")
      headers.toSeq.collect {
        case (k, v) if k == "X-PSP-Timestamp" =>
          k -> v.toLongOption.zip(stampedAt).fold(v)((t, at) => (kit.now().getEpochSecond - (at - t)).toString)
        case (k, v) if k == "X-PSP-Signature" =>
          k -> (if refusedSig then v else webhookSecret.fold(v)(Hmac.sign(_, body.getOrElse(""))))
      }

    def send(r: Recording.Request): Recording.Response =
      step += 1
      val body = r.body.map(Json.render)
      // A capture an earlier step did not answer leaves its placeholder: a difference, not a crash.
      if Placeholder.findFirstIn(r.path).isDefined then Recording.Response(0, Some(Json.obj("unfilled" -> r.path)))
      else r.path match
        case SeedHierarchy(brand) =>
          val ready = kit.subBrand(brand = Some(brand), credentialName = "SIM", provider = "SIMULATOR", deploy = false, scheme = "CARD_VISA")
          subBrand = ready.subBrandId
          credential = ready.credential.id
          ok("holdingId" -> ready.holdingId, "companyId" -> ready.companyId, "subBrandId" -> ready.subBrandId)
        case SeedMerchant(_) =>
          kit.deployTree(Level.SubBrand, subBrand, Kit.catchAll("SIM"))
          val raw = RawKeys.mint()
          kit.components.forEventSourcedEntity(EntityId(subBrand)).call(SubBrand.generateKey)
            .invoke(GenerateKey(UUID.randomUUID().toString, "default", RawKeys.digest(raw), Actor.seed, kit.now())): Unit
          rawKey = raw
          ok("merchantId" -> subBrand, "merchantKey" -> raw)
        case CredIndex() => ok("ids" -> s"|$credential|")
        case Credential(_) =>
          val secrets = r.body.flatMap(_.text("credentials")).flatMap(Json.parseObject).map(_.properties.asScala.map(e => e.getKey -> e.getValue.asText).toMap)
            .getOrElse(Map.empty) ++ Map("secureKey" -> "sk-replay")
          webhookSecret = secrets.get(Secrets.WebhookSecret)
          Secrets(kit.testKit.secrets).putMap(Secrets.credential(credential), secrets)
          kit.components.forEventSourcedEntity(EntityId(subBrand)).call(SubBrand.changeCredentialSecrets)
            .invoke(ChangeCredentialSecrets(credential, secrets.keys.toVector, Actor.seed, kit.now())): Unit
          Secrets.outage = None
          ok()
        case Checkout(_) =>
          // The original's provider reference is not this replay's: the session marked is the payment this replay made.
          if lastPayment.isEmpty then Recording.Response(0, Some(Json.obj("unmade" -> "no payment was submitted")))
          else
            kit.gateway.session(lastPayment, FakeGateway.Session.Undelivered)
            ok()
        case _ if r.service.exists(_ != "merchant") => elsewhere(r)
        case "/v1/cashier/sessions" =>
          val key = r.headers.get("merchantKey").filterNot(_ == "{merchantKey}").getOrElse(rawKey)
          // The index is a consumer's: wait for it as ingest's cache waits for the key's fact (FR-005).
          def lookup = kit.components.forKeyValueEntity(EntityId(RawKeys.digest(key))).call(MerchantKeyIndex.get).invoke()
          val entry  = scala.util.Try(kit.eventually("the key indexed", scala.concurrent.duration.Duration(20, "s"))(Option(lookup).filter(_.active))).getOrElse(lookup)
          if !entry.active then Recording.Response(401, Some(Json.obj("error" -> "INVALID_MERCHANT_KEY")))
          else
            val b = Json.parseObject(body.getOrElse("{}")).get
            b.put("subBrandId", entry.subBrandId)
            Option(b.get("amountCents")).foreach(a => b.set("amount", a): Unit)
            val reply = kit.request("POST", "/v1/cashier/sessions", Some(Json.render(b)), kit.asIngest)
            lastToken = reply.str("session_token")
            answered(reply)
        case Submit(token) =>
          val reply = kit.request("POST", s"/v1/cashier/sessions/$token/submit", body, kit.asIngest)
          if reply.status == 200 then
            sessionOf += reply.str("paymentId") -> token
            lastPayment = reply.str("paymentId")
          answered(reply)
        case Outcome(id, verb) =>
          val headers = signed(r.headers.filter(_._1.startsWith("X-PSP")), body)
          val caller  = if forged(recording.steps(step)) then kit.asIngest else kit.asGateway
          answered(kit.request("POST", s"/v1/payments/$id/$verb", body, caller ++ headers))
        case Sweep() =>
          // The deadline passes: every payment of the scenario not yet resolved reaches its deadline's step.
          sessionOf.keys.foreach { id =>
            if !Status.isTerminal(kit.paymentState(id).status) then
              kit.eventually(s"$id past its deadline", scala.concurrent.duration.Duration(60, "s"))(
                Option(kit.flow(id)).filter(f => Set("expired", "waiting", "credited", "failed").contains(f.status)))
          }
          ok("expired" -> sessionOf.keys.count(id => kit.paymentState(id).status == Status.Expired))
        case Abandon(id) =>
          // The token is the player's proof of the payment: a player other than the payment's holds none of its.
          val player = """[?&]playerId=([^&]+)""".r.findFirstMatchIn(r.path).map(_.group(1))
          val owner  = kit.paymentState(id).opened.map(_.customerId.split(':').last)
          val token  = sessionOf.get(id).filter(_ => player.isEmpty || player == owner).getOrElse(UUID.randomUUID().toString)
          val reply  = kit.request("POST", s"/v1/cashier/sessions/$token/abandon", None, kit.asIngest)
          if reply.status == 200 then ok("ok" -> true) else answered(reply)
        case Read(id) if r.method == "GET" => answered(kit.request("GET", s"/v1/cashier/payments/$id", None, kit.asIngest))
        case OperatorRoute(_, _) => operator(r, body)
        case other    => answered(kit.request(r.method, other, body, kit.asIngest))

  /** When the original answered: the recording's `recorded_at`, which the decoded recording does not keep. */
  private def recordedAt(recording: Recording): Option[Instant] =
    scala.util.Try(Json.mapper.readTree(Files.readString(recording.path)).get("recorded_at").asText).toOption.flatMap(t => scala.util.Try(Instant.parse(t)).toOption)

  def replay(recording: Recording): Vector[Replay.Difference] =
    val kit = SharedKit.kit
    kit.reset()
    val r = Replaying(kit, recording, recordedAt(recording))
    // A payment's deadline is its own timer, set when it is made: a recording that sweeps makes its payments under
    // a short one, long enough for the steps between the submit and the sweep.
    if recording.steps.exists(s => r.sweeps(s.request.path)) then kit.rewire(kit.settings.copy(paymentDeadline = JDuration.ofSeconds(3)))
    try Replay.run(minted(recording), r.send)
    finally kit.reset()

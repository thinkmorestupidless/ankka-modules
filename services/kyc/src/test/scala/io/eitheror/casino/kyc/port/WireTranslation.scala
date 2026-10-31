package io.eitheror.casino.kyc.port

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.{ArrayNode, ObjectNode}
import io.eitheror.casino.kyc.Json
import io.eitheror.casino.kyc.Json.*

import scala.jdk.CollectionConverters.*

/**
 * The one documented translation between the original's KYC routes and this service's
 * (contracts/wire.md), applied in one place: a recorded request, its placeholders already filled, becomes
 * this service's, and this service's answer is read back in the recording's terms before the replay
 * compares. A request the table does not name is not this service's, and is answered as recorded.
 */
object WireTranslation:

  /** Who sends the translated request: whoever the recording's step was, or the service the original's route stood for. */
  enum Caller:
    case AsRecorded, Players, GameProvider

  /** Which row of the table a request is: the answer is translated by its row. */
  enum Row:
    case Upload, Confirm, Documents, View, Approve, Reject, Mode, Status, Required, Override, ApproveWithReason,
      Policy, Requirements, DepositCheck, PlayCheck

  /** An upload this service offered: `<d>` captured beside the object name the replay captured as `<k>`. */
  final case class Offer(documentId: String, url: String, contentType: String)

  /**
   * What a path needs that it does not carry: the brand served, the player the replay signed in as, and
   * the uploads offered so far, by object name.
   */
  final case class Context(brand: String, player: Option[String] = None, offers: Map[String, Offer] = Map.empty):
    def documentOf(key: String): String =
      val k = java.net.URLDecoder.decode(key, java.nio.charset.StandardCharsets.UTF_8)
      offers.get(k).map(_.documentId).getOrElse(k)
    def keyOf(documentId: String): String = offers.collectFirst { case (k, o) if o.documentId == documentId => k }.getOrElse(documentId)

  /** A request as this service takes it; `put` is the signed `PUT` the replay makes first (a confirmation). */
  final case class Request(row: Row, method: String, path: String, body: Option[JsonNode], caller: Caller = Caller.AsRecorded,
      put: Option[Offer] = None)

  private val Seg = "([^/?]+)"

  private val PlayerKyc   = s"/v1/casino/$Seg/players/$Seg/kyc/(.+)".r
  private val UploadUrl   = "upload-url"
  private val DocList     = "documents"
  // An object name holds slashes: recorded raw or encoded, it is everything between `documents/` and the action.
  private val ViewUrl     = "documents/(.+)/view-url".r
  private val ApproveDoc  = "documents/(.+)/approve".r
  private val RejectDoc   = "documents/(.+)/reject".r
  private val BackApprove = s"/v1/casino/backoffice/$Seg/$Seg/kyc/approve".r
  private val PolicyPath  = s"/v1/casino/backoffice/$Seg/kyc-policy(/profile)?".r
  private val Reqs        = s"/v1/casino/players/$Seg/kyc/requirements".r
  private val Notify      = s"/internal/v1/casino/$Seg/notify/deposit".r
  private val Launch      = s"/v1/casino/sessions/$Seg/launch".r

  /**
   * The recorded request as this service takes it, or none when it is not one of the table's routes.
   * `operator` is whether the recording's step was signed in as an operator: the view route is the same
   * path for both in the original.
   */
  def request(method: String, recorded: String, body: Option[JsonNode], ctx: Context, operator: Boolean = false): Option[Request] =
    val b           = ctx.brand
    val path        = recorded.takeWhile(_ != '?')
    def player(p: String) = s"/v1/casino/kyc/$b/$p"
    def back(p: String)   = s"/v1/casino/backoffice/$b/kyc/$p"
    def field(name: String) = body.flatMap(_.text(name))
    (method, path) match
      case ("POST", PlayerKyc(_, p, UploadUrl)) =>
        Some(Request(Row.Upload, "POST", s"${player(p)}/uploads",
          Some(Json.obj("category" -> field("docType"), "contentType" -> field("contentType")))))
      case ("POST", PlayerKyc(_, p, DocList)) =>
        val key = field("key").getOrElse("")
        Some(Request(Row.Confirm, "POST", s"${player(p)}/documents", Some(Json.obj("documentId" -> ctx.documentOf(key))),
          put = ctx.offers.get(key)))
      case ("GET", PlayerKyc(_, p, DocList)) =>
        Some(Request(Row.Documents, "GET", s"${player(p)}/documents", None))
      case ("GET", PlayerKyc(_, p, ViewUrl(k))) =>
        val route = if operator then back(s"$p/views") else s"${player(p)}/views"
        Some(Request(Row.View, "GET", s"$route/${ctx.documentOf(k)}", None))
      case ("PATCH", PlayerKyc(_, p, ApproveDoc(k))) =>
        Some(Request(Row.Approve, "POST", back(s"$p/reviews/${ctx.documentOf(k)}"), Some(Json.obj("outcome" -> "approved"))))
      case ("PATCH", PlayerKyc(_, p, RejectDoc(k))) =>
        Some(Request(Row.Reject, "POST", back(s"$p/reviews/${ctx.documentOf(k)}"),
          Some(Json.obj("outcome" -> "rejected", "reasonCode" -> field("reason")))))
      case ("PUT", PlayerKyc(_, p, "verification-mode")) =>
        Some(Request(Row.Mode, "PUT", back(s"$p/mode"), Some(Json.obj("mode" -> field("mode")))))
      case ("PUT", PlayerKyc(_, p, "status")) =>
        Some(Request(Row.Status, "PUT", back(s"$p/status"), Some(Json.obj("status" -> field("status")))))
      case ("PUT", PlayerKyc(_, p, "required-doc-types")) =>
        Some(Request(Row.Required, "PUT", back(s"$p/required"),
          Some(Json.obj("categories" -> body.map(_.strings("docTypes")).getOrElse(Vector.empty)))))
      case ("PATCH", PlayerKyc(_, p, verdict @ ("verify" | "reject"))) =>
        val status = if verdict == "verify" then "verified" else "rejected"
        Some(Request(Row.Override, "POST", back(s"$p/override"), Some(Json.obj("status" -> status, "reason" -> field("reason")))))
      case ("POST", BackApprove(_, p)) =>
        Some(Request(Row.ApproveWithReason, "POST", back(s"$p/override"), Some(Json.obj("status" -> "verified", "reason" -> field("reason")))))
      case (m, PolicyPath(_, profile)) =>
        Some(Request(Row.Policy, m, back("policy") + Option(profile).getOrElse(""), body))
      case ("GET", Reqs(_)) =>
        Some(Request(Row.Requirements, "GET", s"/v1/casino/kyc-requirements/$b", None))
      case ("POST", Notify(_)) =>
        val amount = body.flatMap(n => Option(n.get("amount"))).map(a => BigDecimal(a.asText)).map(a => (a * 100).toLongExact)
        Some(Request(Row.DepositCheck, "POST", s"/v1/casino/brands/$b/kyc/deposit-check",
          Some(Json.obj("playerId" -> field("playerId"), "amount" -> amount, "currency" -> field("currency"))), Caller.Players))
      case ("POST", Launch(_)) =>
        val p = field("playerId").orElse(ctx.player).getOrElse(sys.error(s"$method $recorded needs the player the replay signed in as"))
        Some(Request(Row.PlayCheck, "GET", s"/v1/casino/brands/$b/kyc/$p/play-check", None, Caller.GameProvider))
      case _ => None

  /** What an answer to an upload gives the replay to capture: the object name, as `<k>`, and the offer beside it. */
  def offered(observed: JsonNode): Option[(String, Offer)] =
    for
      key <- observed.text("objectName")
      id  <- observed.text("documentId")
      url <- observed.text("url")
      ct  <- observed.text("contentType")
    yield key -> Offer(id, url, ct)

  /**
   * This service's answer in the recording's terms: the row's shape (wire.md, *Answer translated*), then
   * only the fields the original answered. `body` is the request as recorded, for the fields an answer
   * repeats from it.
   */
  def answer(request: Request, status: Int, observed: Option[JsonNode], recorded: Option[JsonNode], ctx: Context,
      body: Option[JsonNode] = None): (Int, Option[JsonNode]) =
    val ok = status / 100 == 2
    val (s, shaped) =
      if !ok then (status, observed)
      else request.row match
        case Row.Upload            => (status, observed.map(o => Json.obj("url" -> o.text("url"), "key" -> o.text("objectName"), "contentType" -> o.text("contentType"))))
        case Row.Confirm           => (status, Some(Json.obj("status" -> "recorded")))
        case Row.Documents         => (status, observed.map(documents(_, ctx)))
        case Row.Approve | Row.Reject => (status, observed.map(reviewed(_, ctx)))
        case Row.Mode              => (status, observed.map(o => Json.obj("mode" -> o.text("mode"))))
        case Row.Status            => (status, observed.map(o => Json.obj("kycStatus" -> o.text("status"))))
        case Row.Required          => (status, observed.map(o => Json.obj("docTypes" -> o.strings("categories"),
                                        "verificationMode" -> o.text("mode"), "kycStatus" -> o.text("status"))))
        case Row.Override          => (status, observed.map(o => Json.obj("kycStatus" -> o.text("status"))))
        case Row.ApproveWithReason =>
          val player = request.path.split('/').reverse.lift(1)
          (status, observed.map(o => Json.obj("playerId" -> player, "kycStatus" -> o.text("status"), "approvedBy" -> o.text("by"),
            "approvedAt" -> o.text("at"), "reason" -> body.flatMap(_.text("reason")))))
        case Row.Policy            => (status, observed.map(withoutNulls))
        case Row.DepositCheck | Row.PlayCheck => gate(request.row, status, observed, recorded)
        case Row.View | Row.Requirements      => (status, observed)
    (s, narrowed(recorded, shaped))

  /**
   * A gate's refusal is a 200 here and the original's 403; its allowance is the original's 2xx, compared by
   * status, its body being what the original's route did next (the deposit, the launch), not this service's.
   */
  private def gate(row: Row, status: Int, observed: Option[JsonNode], recorded: Option[JsonNode]): (Int, Option[JsonNode]) =
    observed.filter(_.bool("allowed").contains(false)) match
      case Some(o) =>
        val error = if row == Row.PlayCheck then o.text("error") else o.text("code")
        (403, Some(Json.obj("error" -> error, "kycStatus" -> o.text("kycStatus"), "message" -> o.text("message"))))
      case None => (status, recorded)

  /**
   * The player's read as the original's list: every document, the categories an operator required of this
   * player (the original's `kyc_required_doc_types`, empty until one is set; the policy's are not listed),
   * the status.
   */
  private def documents(o: JsonNode, ctx: Context): JsonNode =
    val categories = o.objects("categories")
    val docs       = categories.flatMap(c => Option(c.get("document")).filter(_.isObject)) ++ o.objects("other")
    val required   = categories.filter(_.bool("requiredOfPlayer").contains(true)).flatMap(_.text("category"))
    Json.obj("documents" -> Json.arr(docs.map(document(_, ctx))*), "requiredDocTypes" -> required, "kycStatus" -> o.text("status"))

  private def document(d: JsonNode, ctx: Context): JsonNode =
    val status = d.text("status")
    val reason = d.text("reasonCode")
    Json.obj("key" -> d.text("documentId").map(ctx.keyOf), "docType" -> d.text("category"), "uploadedAt" -> d.text("uploadedAt"),
      "reviewStatus" -> status.map(_.toLowerCase), "status" -> status.map(_.toUpperCase), "rejectReason" -> reason, "rejectionReason" -> reason,
      "reviewedAt" -> d.text("reviewedAt"))

  private def reviewed(o: JsonNode, ctx: Context): JsonNode =
    val doc = Option(o.get("document")).filter(_.isObject)
    Json.obj("docKey" -> doc.flatMap(_.text("documentId")).map(ctx.keyOf), "status" -> doc.flatMap(_.text("status")).map(_.toUpperCase),
      "kycStatus" -> o.text("status"))

  /** The policy as the original answered it: a field with no value left out, where this service writes `null`. */
  private def withoutNulls(o: JsonNode): JsonNode = o match
    case obj: ObjectNode =>
      val out = obj.objectNode()
      obj.properties.asScala.foreach(e => if !e.getValue.isNull then out.set[JsonNode](e.getKey, e.getValue))
      out
    case other => other

  /**
   * An object keeps only the fields the original answered (this service may say more, `message` beside
   * `error` for one, never less), recursively; an array element by element on the same rule.
   */
  def narrowed(recorded: Option[JsonNode], observed: Option[JsonNode]): Option[JsonNode] =
    (recorded, observed) match
      case (Some(r), Some(o)) => Some(narrow(r, o))
      case _                  => observed

  private def narrow(recorded: JsonNode, observed: JsonNode): JsonNode =
    (recorded, observed) match
      case (r: ObjectNode, o: ObjectNode) =>
        val out = o.objectNode()
        r.fieldNames.asScala.foreach(f => Option(o.get(f)).foreach(v => out.set[JsonNode](f, narrow(r.get(f), v))))
        out
      case (r: ArrayNode, o: ArrayNode) =>
        val out = o.arrayNode()
        o.elements.asScala.zipWithIndex.foreach((v, i) => out.add(if i < r.size then narrow(r.get(i), v) else v))
        out
      case (_, o) => o

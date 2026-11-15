package io.eitheror.casino.kyc.api

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.http.*
import io.eitheror.casino.contract.access.Routes
import io.eitheror.casino.kyc.Json
import io.eitheror.casino.kyc.Json.*
import io.eitheror.casino.kyc.policy.{ApplyProfile, Change, ChangePolicy, PolicyAnswer, PolicyEntity}
import io.eitheror.casino.kyc.queue.KycQueue
import io.eitheror.casino.kyc.record.*

import scala.jdk.CollectionConverters.*

import Answers.*

/**
 * `/v1/casino/backoffice/<slug>/kyc`: the brand's operators (FR-002, R14). Each route requires one
 * permission of the brand's roles (spec 030 amendment K1, `Routes.operatorMay` over the roles as
 * `ctx.bags` last read them): `identity.read` for a player's identity status, `identity.review` for
 * everything else on a player, `config.brand` for the brand's policy.
 */
final class OperatorEndpoint(ctx: Ctx) extends HttpEndpoint(s"/v1/casino/backoffice/${ctx.slug}/kyc"):
  // every route names its own permission; this is never reached
  val acl: Acl = Acl.DenyAll

  private def may(permission: String): Acl = Routes.operatorMay(ctx.slug, permission, ctx.bags, ctx.authenticate())

  /** Whether the caller's roles hold `permission`, for what a route shows beyond what admitted them. */
  private def holds(permission: String): Boolean = ctx.bags.permissionsOf(principal.roles).exists(_(permission))

  private val documents = DocumentRoutes(ctx)

  private def body(text: String)(f: JsonNode => Reply): Reply = Json.parseObject(text).fold(badBody)(f)

  // ── the brand's policy (US3) ───────────────────────────────────────────────────────────────────

  private def policyAnswer(a: PolicyAnswer): Reply = a.refusal match
    case None => ok(Render.policy(a.policy))
    case Some(code) =>
      val named = a.field.map(f => if code == "invalid_category" then "category" -> f else "field" -> OperatorEndpoint.PolicyWire.getOrElse(f, f)).toSeq ++
        Option.when(code == "unknown_profile")("knownProfiles" -> Json.arr(a.known*)).toSeq
      refused(code, named*)

  withAcl(may("config.brand")) {
  get("/policy") { () => ok(Render.policy(ctx.policy)) }
  }

  withAcl(may("config.brand")) {
  putBody("/policy") { (text: String) =>
    body(text) { b =>
      OperatorEndpoint.change(b) match
        case Left(field) => refused("invalid_policy", "field" -> field)
        case Right(change) =>
          policyAnswer(ctx.policyEntity.call(PolicyEntity.change).invoke(ChangePolicy(change, operatorId, ctx.now())))
    }
  }
  }

  withAcl(may("config.brand")) {
  postBody("/policy/profile") { (text: String) =>
    body(text)(b => policyAnswer(ctx.policyEntity.call(PolicyEntity.applyProfile)
      .invoke(ApplyProfile(b.text("profile"), operatorId, ctx.now()))))
  }
  }

  withAcl(may("identity.review")) {
  postBody("/{playerId}/uploads") { (p: String, text: String) =>
    body(text) { b =>
      b.text("reason").fold(refused("reason_required"))(reason => documents.upload(p, b, Uploader.support(principal.subject, reason)))
    }
  }
  }
  withAcl(may("identity.review")) {
  postBody("/{playerId}/documents") { (p: String, text: String) => body(text)(documents.confirm(p, _)) }
  }
  withAcl(may("identity.review")) {
  get("/{playerId}/views/{documentId}") { (p: String, d: String) =>
    documents.view(p, d, Actor.operator(principal.subject))
  }
  }

  // ── the player's account (US2) ─────────────────────────────────────────────────────────────────

  private def operatorId = principal.subject

  /**
   * The queue (FR-035): a filter's rows, oldest first by its time unless `sort=newest`, the filter's `total` and
   * every filter's live count. `page` and `pageSize` page it (spec 030 K2) and are answered back; with neither,
   * the first `QueueLimit` rows are listed as before.
   */
  withAcl(may("identity.review")) {
  get("/queue") { () =>
    KycQueue.Filter.of(request.query.raw("filter"), request.query.raw("reason")) match
      case None => refused("invalid_filter")
      case Some(filter) =>
        OperatorEndpoint.paging(request.query.raw) match
          case Left(field) => refused("invalid_page", "field" -> field)
          case Right(paging) =>
            val queue  = ctx.views.forView(KycQueue)
            val now    = ctx.now()
            val newest = request.query.raw("sort").contains("newest")
            val shown  = paging.fold(filter.rows(queue, OperatorEndpoint.QueueLimit, newest))((p, size) => filter.page(queue, p, size, newest))
            val paged  = paging.toSeq.flatMap((p, size) => Seq[(String, Any)]("page" -> p, "pageSize" -> size))
            ok(Json.obj((Seq[(String, Any)]("rows" -> Json.arr(shown.map(Render.queueRow(_, filter.since, now))*),
              "total" -> filter.total(queue)) ++ paged ++ Seq("counts" -> Json.obj(KycQueue.counts(queue)*)))*))
  }
  }

  /** A player's identity status (K1): a reader without `identity.review` is shown it without the documents. */
  withAcl(may("identity.read")) {
  get("/{playerId}") { (p: String) =>
    val s = ctx.state(p)
    if s.isEmpty then refused("player_not_found") else ok(Render.operator(s, ctx.policy, ctx.now(), withDocuments = holds("identity.review")))
  }
  }

  /** The reason code is checked before the document (R8); an outcome neither approved nor rejected is not a review. */
  withAcl(may("identity.review")) {
  postBody("/{playerId}/reviews/{documentId}") { (p: String, d: String, text: String) =>
    body(text) { b =>
      b.text("outcome").flatMap(Outcome.of) match
        case None => refusal(400, "invalid_body", "outcome is approved or rejected")
        case Some(outcome) =>
          val a = ctx.record(p).call(KycRecord.review)
            .invoke(ReviewDocument(d, outcome, b.text("reasonCode"), operatorId, ctx.policy.required, ctx.now()))
          a.refusal.fold(ok(Json.obj("document" -> a.state.document(d).map(Render.document(_, operator = true)),
            "status" -> a.state.status.wire)))(refused)
    }
  }
  }

  withAcl(may("identity.review")) {
  putBody("/{playerId}/mode") { (p: String, text: String) =>
    body(text) { b =>
      b.text("mode").flatMap(Mode.of).fold(refused("invalid_mode")) { mode =>
        val a = ctx.record(p).call(KycRecord.setMode).invoke(SetMode(mode, operatorId, ctx.policy.required, ctx.now()))
        a.refusal.fold(ok(Json.obj("mode" -> a.state.mode.wire, "status" -> a.state.status.wire,
          "readyAt" -> a.state.readyAt.map(_.toString).orNull)))(refused)
      }
    }
  }
  }

  /** An operator sets pending or verified; verified needs the profile complete (FR-015). */
  withAcl(may("identity.review")) {
  putBody("/{playerId}/status") { (p: String, text: String) =>
    body(text) { b =>
      b.text("status").flatMap(Status.of).filter(OperatorEndpoint.Settable) match
        case None => refused("invalid_status")
        case Some(status) =>
          val a = ctx.record(p).call(KycRecord.setStatus).invoke(SetStatus(status, operatorId, ctx.policy.required, ctx.now()))
          a.refusal.fold(ok(Json.obj("status" -> a.state.status.wire)))(refused)
    }
  }
  }

  withAcl(may("identity.review")) {
  putBody("/{playerId}/required") { (p: String, text: String) =>
    body(text) { b =>
      val a = ctx.record(p).call(KycRecord.setRequired)
        .invoke(SetRequired(b.strings("categories"), operatorId, ctx.policy.required, ctx.now()))
      a.refusal.fold(ok(Json.obj("categories" -> a.state.required.map(_.wire), "mode" -> a.state.mode.wire,
        "status" -> a.state.status.wire)))(refused)
    }
  }
  }

  /** The original's approve and reject: verified or rejected regardless of the documents and the profile (R8). */
  withAcl(may("identity.review")) {
  postBody("/{playerId}/override") { (p: String, text: String) =>
    body(text) { b =>
      b.text("status").flatMap(Status.of).filter(OperatorEndpoint.Overridable) match
        case None => refused("invalid_status")
        case Some(status) =>
          val at = ctx.now()
          val a  = ctx.record(p).call(KycRecord.overrideStatus).invoke(Override(status, b.text("reason"), operatorId, ctx.policy.required, at))
          a.refusal.fold(ok(Json.obj("status" -> a.state.status.wire, "by" -> operatorId, "at" -> at.toString)))(refused)
    }
  }
  }

  /** The source of funds review (FR-023): cleared closes the source of funds flag; unverified sets it back. */
  withAcl(may("identity.review")) {
  postBody("/{playerId}/sof") { (p: String, text: String) =>
    body(text) { b =>
      val record = ctx.record(p)
      b.text("status") match
        case Some("cleared")    => ok(Json.obj("sof" -> record.call(KycRecord.clearSof).invoke(ClearSof(operatorId, ctx.now())).state.sof.wire))
        case Some("unverified") => ok(Json.obj("sof" -> record.call(KycRecord.resetSof).invoke(ResetSof(operatorId, ctx.now())).state.sof.wire))
        case _                  => refused("invalid_status")
    }
  }
  }

  /** An open flag dismissed with a note (FR-024); the note is personal and kept only on the record. */
  withAcl(may("identity.review")) {
  postBody("/{playerId}/flags/{reason}/dismiss") { (p: String, reason: String, text: String) =>
    body(text) { b =>
      val a = ctx.record(p).call(KycRecord.dismissFlag).invoke(DismissFlag(reason, b.text("note").getOrElse(""), operatorId, ctx.now()))
      a.refusal.fold(ok(Json.obj("flags" -> Render.flags(a.state))))(refused)
    }
  }
  }

object OperatorEndpoint:
  /** The most rows one read of the queue lists. */
  val QueueLimit = 1000

  val MaxPageSize = 200

  /**
   * The queue's `page` (from 1) and `pageSize` (1 to 200, 50 by default), when either is given; with neither,
   * none. Left names the first that is not valid.
   */
  def paging(q: String => Option[String]): Either[String, Option[(Int, Int)]] =
    def opt(name: String) = q(name).map(_.trim).filter(_.nonEmpty)
    def number(name: String, default: Int, max: Int) =
      opt(name).fold[Either[String, Int]](Right(default))(v => v.toIntOption.filter(n => n >= 1 && n <= max).toRight(name))
    if opt("page").isEmpty && opt("pageSize").isEmpty then Right(None)
    else
      for
        page <- number("page", 1, Int.MaxValue / MaxPageSize)
        size <- number("pageSize", 50, MaxPageSize)
      yield Some((page, size))

  /** What `PUT /status` sets; `POST /override` sets the other two. */
  val Settable: Set[Status]    = Set(Status.Pending, Status.Verified)
  val Overridable: Set[Status] = Set(Status.Verified, Status.Rejected)

  /** The policy's fields by the names the routes use (the original's, contracts/wire.md). */
  val PolicyWire: Map[String, String] = Map(
    "depositGate" -> "depositGate", "playGate" -> "playGate", "currency" -> "kycCurrency",
    "depositThreshold" -> "depositThresholdMinorUnits", "sofThreshold" -> "sofThresholdMinorUnits", "windowDays" -> "kycWindowDays",
    "required" -> "requiredDocTypes", "suspendOnExpiry" -> "autoSuspendOnWindowExpiry"
  )

  /**
   * A `PUT /policy` body as a change: a field left out keeps its value, `null` clears a nullable one, and
   * a value of the wrong type is refused naming the field. `licenceProfile` is not read: any change is
   * `CUSTOM`.
   */
  def change(b: JsonNode): Either[String, Change] =
    def field(name: String): Option[JsonNode] = Option(b.get(name))
    def text(name: String): Either[String, Option[String]] = field(name) match
      case None                    => Right(None)
      case Some(n) if n.isTextual  => Right(Some(n.asText))
      case Some(_)                 => Left(name)
    def nullable[A](name: String)(read: JsonNode => Option[A]): Either[String, Option[Option[A]]] = field(name) match
      case None                 => Right(None)
      case Some(n) if n.isNull  => Right(Some(None))
      case Some(n)              => read(n).map(v => Some(Some(v))).toRight(name)
    def whole(n: JsonNode): Option[Long] = Option.when(n.isIntegralNumber && n.canConvertToLong)(n.asLong)
    for
      deposit <- text("depositGate")
      play    <- text("playGate")
      ccy     <- nullable("kycCurrency")(n => Option.when(n.isTextual)(n.asText))
      dt      <- nullable("depositThresholdMinorUnits")(whole)
      sof     <- nullable("sofThresholdMinorUnits")(whole)
      window  <- nullable("kycWindowDays")(n => whole(n).filter(_.isValidInt).map(_.toInt))
      req     <- field("requiredDocTypes") match
                   case None                                                 => Right(None)
                   case Some(n) if n.isArray && n.elements.asScala.forall(_.isTextual) => Right(Some(n.elements.asScala.map(_.asText).toVector))
                   case Some(_)                                              => Left("requiredDocTypes")
      suspend <- field("autoSuspendOnWindowExpiry") match
                   case None                    => Right(None)
                   case Some(n) if n.isBoolean  => Right(Some(n.asBoolean))
                   case Some(_)                 => Left("autoSuspendOnWindowExpiry")
    yield Change(deposit, play, ccy, dt, sof, window, req, suspend)

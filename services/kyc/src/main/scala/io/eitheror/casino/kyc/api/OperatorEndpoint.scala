package io.eitheror.casino.kyc.api

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.http.*
import io.eitheror.casino.contract.access.Routes
import io.eitheror.casino.kyc.Json
import io.eitheror.casino.kyc.Json.*
import io.eitheror.casino.kyc.record.*

import Answers.*

/**
 * `/v1/casino/backoffice/<slug>/kyc`: the brand's compliance operators (FR-002, R14). The ACL admits an
 * operator of the brand; each route then asks for one of the compliance roles, and any other is `forbidden`.
 */
final class OperatorEndpoint(ctx: Ctx) extends HttpEndpoint(s"/v1/casino/backoffice/${ctx.slug}/kyc"):
  val acl: Acl = Routes.operator(ctx.slug, ctx.authenticate())

  private val documents = DocumentRoutes(ctx)

  private def compliance(f: => Reply): Reply =
    if principal.roles.exists(OperatorEndpoint.ComplianceRoles) then f else refused("forbidden")

  private def body(text: String)(f: JsonNode => Reply): Reply = Json.parseObject(text).fold(badBody)(f)

  postBody("/{playerId}/uploads") { (p: String, text: String) =>
    compliance(body(text) { b =>
      b.text("reason").fold(refused("reason_required"))(reason => documents.upload(p, b, Uploader.support(principal.subject, reason)))
    })
  }
  postBody("/{playerId}/documents") { (p: String, text: String) => compliance(body(text)(documents.confirm(p, _))) }
  get("/{playerId}/views/{documentId}") { (p: String, d: String) =>
    compliance(documents.view(p, d, Actor.operator(principal.subject)))
  }

  // ── the player's account (US2) ─────────────────────────────────────────────────────────────────

  private def operatorId = principal.subject

  get("/{playerId}") { (p: String) =>
    compliance {
      val s = ctx.state(p)
      if s.isEmpty then refused("player_not_found") else ok(Render.operator(s, ctx.policy, ctx.now()))
    }
  }

  /** The reason code is checked before the document (R8); an outcome neither approved nor rejected is not a review. */
  postBody("/{playerId}/reviews/{documentId}") { (p: String, d: String, text: String) =>
    compliance(body(text) { b =>
      b.text("outcome").flatMap(Outcome.of) match
        case None => refusal(400, "invalid_body", "outcome is approved or rejected")
        case Some(outcome) =>
          val a = ctx.record(p).call(KycRecord.review)
            .invoke(ReviewDocument(d, outcome, b.text("reasonCode"), operatorId, ctx.policy.required, ctx.now()))
          a.refusal.fold(ok(Json.obj("document" -> a.state.document(d).map(Render.document(_, operator = true)),
            "status" -> a.state.status.wire)))(refused)
    })
  }

  putBody("/{playerId}/mode") { (p: String, text: String) =>
    compliance(body(text) { b =>
      b.text("mode").flatMap(Mode.of).fold(refused("invalid_mode")) { mode =>
        val a = ctx.record(p).call(KycRecord.setMode).invoke(SetMode(mode, operatorId, ctx.policy.required, ctx.now()))
        a.refusal.fold(ok(Json.obj("mode" -> a.state.mode.wire, "status" -> a.state.status.wire,
          "readyAt" -> a.state.readyAt.map(_.toString).orNull)))(refused)
      }
    })
  }

  /** An operator sets pending or verified; verified needs the profile complete (FR-015). */
  putBody("/{playerId}/status") { (p: String, text: String) =>
    compliance(body(text) { b =>
      b.text("status").flatMap(Status.of).filter(OperatorEndpoint.Settable) match
        case None => refused("invalid_status")
        case Some(status) =>
          val a = ctx.record(p).call(KycRecord.setStatus).invoke(SetStatus(status, operatorId, ctx.policy.required, ctx.now()))
          a.refusal.fold(ok(Json.obj("status" -> a.state.status.wire)))(refused)
    })
  }

  putBody("/{playerId}/required") { (p: String, text: String) =>
    compliance(body(text) { b =>
      val a = ctx.record(p).call(KycRecord.setRequired)
        .invoke(SetRequired(b.strings("categories"), operatorId, ctx.policy.required, ctx.now()))
      a.refusal.fold(ok(Json.obj("categories" -> a.state.required.map(_.wire), "mode" -> a.state.mode.wire,
        "status" -> a.state.status.wire)))(refused)
    })
  }

  /** The original's approve and reject: verified or rejected regardless of the documents and the profile (R8). */
  postBody("/{playerId}/override") { (p: String, text: String) =>
    compliance(body(text) { b =>
      b.text("status").flatMap(Status.of).filter(OperatorEndpoint.Overridable) match
        case None => refused("invalid_status")
        case Some(status) =>
          val at = ctx.now()
          val a  = ctx.record(p).call(KycRecord.overrideStatus).invoke(Override(status, b.text("reason"), operatorId, ctx.policy.required, at))
          a.refusal.fold(ok(Json.obj("status" -> a.state.status.wire, "by" -> operatorId, "at" -> at.toString)))(refused)
    })
  }

object OperatorEndpoint:
  /** What `PUT /status` sets; `POST /override` sets the other two. */
  val Settable: Set[Status]    = Set(Status.Pending, Status.Verified)
  val Overridable: Set[Status] = Set(Status.Verified, Status.Rejected)

  /** The roles that may act on a player's KYC and on the brand's policy (FR-002). */
  val ComplianceRoles: Set[String] = Set("brand_admin", "brand_compliance", "compliance_officer", "resource_admin", "platform_admin")

package io.eitheror.casino.kyc.api

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.http.*
import io.eitheror.casino.contract.access.Routes
import io.eitheror.casino.kyc.Json
import io.eitheror.casino.kyc.Json.*
import io.eitheror.casino.kyc.documents.ObjectNames
import io.eitheror.casino.kyc.record.{DepositCheck, Erase, Gates, KycRecord}

import Answers.*

/**
 * `/v1/casino/brands/<slug>/kyc`: the gates the brand's services ask (FR-003, FR-019, FR-020), admitted
 * by identity with no token. A refusal is an answer, `200` with `allowed: false` (R5), so a caller never
 * reads it as an outage. A player with no record is answered as an empty one: unverified, no documents.
 */
final class ServiceEndpoint(ctx: Ctx) extends HttpEndpoint(s"/v1/casino/brands/${ctx.slug}/kyc"):
  val acl: Acl = Routes.service()

  private def body(text: String)(f: JsonNode => Reply): Reply = Json.parseObject(text).fold(badBody)(f)

  /** The policy read, then the check on the record, which raises the flag a refusal names, once. */
  withAcl(Routes.serviceOf("players")) {
    postBody("/deposit-check") { (text: String) =>
      body(text) { b =>
        (b.text("playerId"), b.long("amount").filter(_ >= 0)) match
          case (Some(p), Some(amount)) =>
            val a = ctx.record(p).call(KycRecord.depositCheck).invoke(DepositCheck(p, ctx.policy, b.text("currency"), amount, ctx.now()))
            val g = a.gate.getOrElse(Gates.Allowed)
            if g.allowed then ok(Json.obj("allowed" -> true))
            else ok(Json.obj("allowed" -> false, "code" -> g.code, "message" -> g.message,
              "kycStatus" -> Option.when(g.code.exists(_.startsWith("DEPOSIT_BLOCKED_KYC")))(a.state.status.wire)))
          case _ => refusal(400, "invalid_body", "playerId and a whole, non-negative amount in minor units are required")
      }
    }
  }

  /**
   * Erasure (FR-033, R15): every version of every object under the player's prefix deleted, then `Erase`,
   * which blanks every personal value and keeps ids, categories, times, statuses and reason codes. It runs
   * in full on every call, so an object written after an earlier call goes too. A store that cannot be
   * reached answers no certificate. The export is the records without the objects.
   */
  withAcl(Routes.serviceOf("players")) {
    post("/erasure/players/{playerId}") { (p: String) =>
      ctx.store.deleteAll(ObjectNames.prefix(p)) match
        case Left(_) => storageUnavailable
        case Right(deleted) =>
          val at = ctx.now()
          val s  = ctx.record(p).call(KycRecord.erase).invoke(Erase(at)).state
          ok(Json.obj("service" -> "kyc", "playerId" -> p, "objectsDeleted" -> deleted, "recordsKept" -> s.documents.size, "erasedAt" -> at.toString))
    }

    get("/exports/players/{playerId}") { (p: String) => ok(Render.exported(ctx.state(p))) }
  }

  /** A read: the play gate on the record as it stands. */
  withAcl(Routes.serviceOf("game-provider")) {
    get("/{playerId}/play-check") { (p: String) =>
      val s = ctx.state(p)
      val g = Gates.play(ctx.policy, s.status)
      if g.allowed then ok(Json.obj("allowed" -> true))
      else ok(Json.obj("allowed" -> false, "error" -> g.code, "code" -> g.code, "kycStatus" -> s.status.wire, "message" -> g.message))
    }
  }

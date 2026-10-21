package io.eitheror.casino.merchant.api

import com.thinkmorestupidless.ankka.http.*
import io.eitheror.casino.contract.access.Routes
import io.eitheror.casino.merchant.{Json, Metrics, Settings}
import io.eitheror.casino.merchant.api.Answers.*
import io.eitheror.casino.merchant.cashier.Commands
import io.eitheror.casino.merchant.keys.ActiveKeys
import io.eitheror.casino.merchant.security.Hmac
import io.eitheror.casino.merchant.terminals.catalogue.Catalogue

/** The project's services this one serves by identity (FR-033): `ingest` and `psp-gateway`, both of `payments`. */
object Identities:
  val Project     = "payments"
  val ingest: Acl = Acl.allowCallers(Callers.service(Project, "ingest"))
  val gateway: Acl = Acl.allowCallers(Callers.service(Project, "psp-gateway"))

/**
 * `/v1/payments`: one endpoint, two audiences (research R14). Operators read payments, the summary and
 * the uncredited captures, retry a credit, work the routing rules and list terminals; `psp-gateway`
 * reports a capture or a failure. Literal segments outrank `/{paymentId}`.
 */
final class PaymentsEndpoint(ctx: Ctx) extends HttpEndpoint("/v1/payments"):
  val acl: Acl = Acl.DenyAll
  private def op       = Operator.of(principal)
  private def q(n: String) = request.query.raw(n)
  private val back     = BackofficeRoutes(ctx)
  private val routing  = RoutingRoutes(ctx)
  private val captures = CaptureRoutes(ctx)
  private val ops      = MerchantOps(ctx)

  withAcl(Routes.paymentsOperator(ctx.authenticate())) {
    get("/cashier") { () => back.list(op, q("merchantId"), q("status"), q("search"), q("page"), q("pageSize")) }
    get("/cashier/summary") { () => back.summary(op, q("merchantId")) }
    get("/cashier/uncredited") { () => back.uncredited(op) }
    post("/cashier/{paymentId}/retry-credit") { (paymentId: String) => back.retryCredit(op, paymentId) }

    get("/routing-rules/state") { () => routing.state(op, q("level"), q("entityId")) }
    postBody("/routing-rules") { (text: String) => routing.saveDraft(op, text) }
    post("/routing-rules/deploy") { () => routing.deploy(op, q("level"), q("entityId")) }
    postBody("/routing-rules/simulate") { (text: String) => ops.simulate(text) }
    post("/routing-rules/rollback/{version}") { (version: String) => routing.rollback(op, version, q("level"), q("entityId")) }
    post("/routing-rules/retire") { () => routing.retire(op, q("level"), q("entityId")) }

    /** The gauge `eitheror_payments_uncredited_captures` as Prometheus text (research R18: the SDK offers no meter). */
    get("/metrics") { () =>
      val bound = ctx.live.uncreditedBound
      Metrics.uncredited(BackofficeRoutes.uncreditedRows(ctx).count(_.capturedAt.exists(_.isBefore(ctx.now().minus(bound)))))
      Respond(Bytes("text/plain; version=0.0.4", Metrics.prometheus.getBytes(java.nio.charset.StandardCharsets.UTF_8)), 200)
    }

    get("/terminals") { () =>
      q("merchantId") match
        case None => refuse(400, "INVALID_REQUEST", "merchantId is required")
        case Some(id) => ops.scoped(op, id)(s => ok(Json.obj("terminals" -> Json.arr(ops.terminalSummaries(s)*))))
    }

    get("/{paymentId}") { (paymentId: String) =>
      guarded {
        val read = Commands(ctx).read(paymentId)
        if read.status != 200 then read
        else
          val sb = ctx.subBrand(com.thinkmorestupidless.ankka.core.EntityId(paymentId).pipe(id =>
            ctx.components.forEventSourcedEntity(id).call(io.eitheror.casino.merchant.payments.Payment.get).invoke().opened.get.subBrandId))
          if op.permits(ctx.subBrandTarget(sb)) then read else scopeViolation
      }
    }
  }

  withAcl(Identities.gateway) {
    postBody("/{paymentId}/capture") { (paymentId: String, raw: String) =>
      captures.capture(paymentId, raw, request.header(Hmac.SignatureHeader), request.header(Hmac.TimestampHeader))
    }
    postBody("/{paymentId}/fail") { (paymentId: String, raw: String) => captures.fail(paymentId, raw) }
  }

  extension [A](a: A) private def pipe[B](f: A => B): B = f(a)

/** `/v1/cashier`: the commands `ingest` sends (contracts/commands.md), by its identity alone. */
final class CashierEndpoint(ctx: Ctx) extends HttpEndpoint("/v1/cashier"):
  val acl: Acl = Identities.ingest
  private val commands = Commands(ctx)

  postBody("/sessions") { (text: String) => commands.createSession(text) }
  postBody("/sessions/{token}/submit") { (token: String, text: String) => commands.submit(token, text) }
  post("/sessions/{token}/abandon") { (token: String) => commands.abandon(token) }
  get("/payments/{paymentId}") { (paymentId: String) => commands.read(paymentId) }

  /** The active key index, for `ingest`'s refresh (FR-005); in no request path. */
  get("/keys") { () =>
    val keys = ctx.views.forView(ActiveKeys).all(100_000).flatMap(r => r.keys.filter(_.active).map(k =>
      Json.obj("digest" -> k.digest, "keyId" -> k.keyId, "subBrandId" -> r.subBrandId)))
    ok(Json.obj("keys" -> Json.arr(keys*)))
  }

/**
 * `/internal/v1`: what `psp-gateway` reads of this service at the moment of use (FR-008), at the
 * original's paths: a sub-brand's usable credential's secrets and a terminal's properties, secrets
 * included, and a brand's terminals without their secrets. The gateway holds no credential of its own.
 */
final class InternalEndpoint(ctx: Ctx) extends HttpEndpoint("/internal/v1"):
  val acl: Acl = Identities.gateway
  private val ops = MerchantOps(ctx)

  get("/sub-brands/{subBrandId}/psp-credentials/{provider}") { (subBrandId: String, provider: String) =>
    guarded {
      ctx.subBrand(subBrandId).usableCredential(provider) match
        case None => refuse(404, "NOT_FOUND")
        case Some(c) =>
          ctx.secrets.credential(c.id).fold(e => refuse(503, "UNAVAILABLE", e.reason), m =>
            if m.isEmpty then refuse(404, "NOT_FOUND") else ok(Json.obj("provider" -> c.provider, "credentials" -> Json.objOf(m))))
    }
  }

  get("/terminals/{terminalId}/properties") { (terminalId: String) =>
    guarded {
      val t = ctx.components.forEventSourcedEntity(com.thinkmorestupidless.ankka.core.EntityId(terminalId))
        .call(io.eitheror.casino.merchant.terminals.Terminal.get).invoke()
      if !t.exists then refuse(404, "NOT_FOUND")
      else ctx.secrets.terminal(terminalId).fold(e => refuse(503, "UNAVAILABLE", e.reason), m => ok(Json.obj("properties" -> Json.objOf(t.properties ++ m))))
    }
  }

  /**
   * A brand's terminals, for the gateway's payout listing (spec 005, research R12): every terminal of
   * every sub-brand the brand names, with the properties kept on the entity. The secret store is not
   * read, and a key the catalogue calls a secret is dropped even if one was kept on the entity.
   */
  get("/brands/{brand}/terminals") { (brand: String) =>
    guarded {
      import com.thinkmorestupidless.ankka.runtime.SqlSyntax.*
      val subBrands = ctx.views.forView(io.eitheror.casino.merchant.hierarchy.SubBrandsView)
        .where(jsonText("brand") ++ sql" = $brand", 10_000).map(_.id).sorted
      val terminals = subBrands.flatMap(ops.terminalsOf).map { t =>
        val open = t.properties.filter((k, _) => !t.secretKeys.contains(k) && !Catalogue.isSecret(t.gateway, t.scheme, k))
        Json.obj("id" -> t.id, "subBrandId" -> t.subBrandId, "gateway" -> t.gateway, "scheme" -> t.scheme, "name" -> t.name,
          "status" -> t.status, "supportsDeposit" -> t.supportsDeposit, "supportsWithdrawal" -> t.supportsWithdrawal,
          "requiresPayoutDestination" -> t.requiresPayoutDestination, "properties" -> Json.objOf(open))
      }
      ok(Json.obj("terminals" -> Json.arr(terminals*)))
    }
  }

/** `/status` (FR-023): captured payments not credited for longer than the bound, for the project and its gauge. */
final class StatusEndpoint(ctx: Ctx) extends HttpEndpoint("/status"):
  val acl: Acl = Acl.allowCallers(Callers.anyInProject)

  get("/") { () =>
    val bound = ctx.live.uncreditedBound
    val cut   = ctx.now().minus(bound)
    val n     = BackofficeRoutes.uncreditedRows(ctx).count(_.capturedAt.exists(_.isBefore(cut)))
    Metrics.uncredited(n)
    ok(Json.obj("uncreditedCaptures" -> n, "bound" -> Settings.render(bound)))
  }

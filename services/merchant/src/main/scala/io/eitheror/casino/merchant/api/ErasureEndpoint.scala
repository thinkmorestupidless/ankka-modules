package io.eitheror.casino.merchant.api

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.http.*
import com.thinkmorestupidless.ankka.runtime.SqlSyntax.*
import io.eitheror.casino.merchant.Json
import io.eitheror.casino.merchant.api.Answers.*
import io.eitheror.casino.merchant.cashier.{Session, SessionsByCustomer}
import io.eitheror.casino.merchant.payments.{PaymentCustomer, PaymentsView}

/**
 * The erasure handler (FR-032, research R12): a brand's `players` erases a player's customer fields
 * here under the shared manifest's `erasure` grant, until ankka 042 does it from the subject. It deletes
 * the customer's payment customer records and live sessions, and confirms; a payment's journal holds no
 * personal field, so nothing else is touched. Another brand's `players` is refused: the customer id
 * `brand:player` names the brand that may erase it.
 */
final class ErasureEndpoint(ctx: Ctx) extends HttpEndpoint("/erasure"):
  /** Any brand project's `players` (the manifest's grant), named by service; the brand is checked per customer. */
  val acl: Acl = Acl.AllowIf(r => r.caller match
    case Caller.Service(_, "players") | Caller.Local => true
    case _                                            => false)

  post("/players/{customerId}") { (customerId: String) =>
    guarded {
      val brand = customerId.takeWhile(_ != ':')
      caller match
        case Caller.Service(project, _) if !customerId.contains(':') || project != brand =>
          refuse(403, "FORBIDDEN", s"$project may erase only its own players")
        case _ =>
          val rows     = ctx.views.forView(PaymentsView).where(jsonText("customerId") ++ sql" = $customerId", 100_000)
          val payments = rows.count(r => ctx.components.forKeyValueEntity(EntityId(r.paymentId)).call(PaymentCustomer.erase).invoke())
          val sessions = ctx.views.forView(SessionsByCustomer).where(jsonText("customerId") ++ sql" = $customerId", 100_000)
            .count(s => ctx.components.forKeyValueEntity(EntityId(s.token)).call(Session.erase).invoke())
          ok(Json.obj("subject" -> s"player/$customerId", "confirmed" -> true, "payments" -> payments, "sessions" -> sessions))
    }
  }

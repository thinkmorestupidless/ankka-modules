package io.eitheror.casino.merchant.audit

import com.thinkmorestupidless.ankka.testkit.{ConsumerTestKit, LogCapturing}
import io.eitheror.casino.contract.facts.AuditRecord
import io.eitheror.casino.merchant.hierarchy.*
import io.eitheror.casino.merchant.payments.*
import io.eitheror.casino.merchant.routing.RoutingTreeEvent
import io.eitheror.casino.merchant.terminals.{Capability, TerminalEvent}

import java.time.Instant

/** FR-034: an audit record per change, in the contract's shape, with no secret and no personal field. */
class AuditFactsSuite extends munit.FunSuite with LogCapturing:

  private val at = Instant.parse("2026-10-12T10:00:00Z")
  private val op = Actor.operator("op-7")

  private def one(r: ConsumerTestKit.Result[?]): AuditRecord = r.payloads.collect { case a: AuditRecord => a }.head

  test("a holding created is one record naming the operator, the subject and the change") {
    val r = one(ConsumerTestKit.of(HoldingAudit).onMessage(OrgEvent.Created("Acme", None, op, at), subject = "h-1", sequenceNumber = 1))
    assertEquals((r.service, r.brand, r.action, r.actorKind, r.actorId, r.subjectKind, r.subjectId, r.recordId),
      ("merchant", "payments", "holding created", "operator", "op-7", "holding", "h-1", "merchant/holding/h-1/1"))
  }

  test("a credential's secrets changing is recorded by the credential's id, never a key's value") {
    val r = one(ConsumerTestKit.of(SubBrandAudit).onMessage(SubBrandEvent.CredentialSecretsChanged("cr-1", Vector("secureKey"), op, at), subject = "sb-1", sequenceNumber = 4))
    assertEquals(r.action, "credential secrets changed")
    assertEquals(r.changes.map(c => c.field -> c.after), Vector("credentialId" -> Some("cr-1")))
  }

  test("a terminal's properties changing names the keys, not the values") {
    val r = one(ConsumerTestKit.of(TerminalAudit).onMessage(TerminalEvent.PropertiesChanged(Map("memberId" -> "m-123"), Vector("secureKey"), op, at),
      subject = "t-1", sequenceNumber = 2))
    assertEquals(r.changes.flatMap(_.after), Vector("memberId,secureKey"))
    assert(!r.toString.contains("m-123"), "a property's value is not audited")
  }

  test("a routing deploy, a key generated and a payment's outcome are recorded; a payment's record names its brand") {
    assertEquals(one(ConsumerTestKit.of(RoutingAudit).onMessage(RoutingTreeEvent.Deployed(2, op, at), subject = "subBrand/sb-1", sequenceNumber = 3)).action,
      "routing tree deployed")
    val key = MerchantKey("k-1", "frontend", "d", true, at)
    assertEquals(one(ConsumerTestKit.of(SubBrandAudit).onMessage(SubBrandEvent.KeyGenerated(key, op), subject = "sb-1", sequenceNumber = 5)).action,
      "merchant key generated")
    val opened = Opened("sb-1", Some("goldvibe"), "goldvibe:p1", "p1", 5000, "EUR", "DREAMZPAY", "VISA", "main", "tx", "psp", "r", None, at, at)
    val p = one(ConsumerTestKit.of(PaymentAudit).onMessage(PaymentEvent.Initiated(opened, Status.AwaitingRedirect), subject = "pay-1", sequenceNumber = 1))
    assertEquals((p.brand, p.action, p.recordClass, p.subjectKind), ("goldvibe", "payment initiated", "money", "payment"))
  }

  test("a mirrored flag and an acceptance name the system and the operator themselves") {
    import io.eitheror.casino.merchant.operators.OperatorEvent
    val m = one(ConsumerTestKit.of(OperatorAudit).onMessage(OperatorEvent.EnabledMirrored(false, at), subject = "u-1", sequenceNumber = 4))
    assertEquals((m.actorKind, m.actorId), ("system", "operator-sync"))
  }

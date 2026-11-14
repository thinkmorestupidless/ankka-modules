package io.eitheror.casino.support.it

import com.thinkmorestupidless.ankka.agent.Json as AgentJson
import io.eitheror.casino.contract.facts.{AuditChange, AuditRecord, PlatformAudit}

import java.nio.charset.StandardCharsets.UTF_8

import scala.concurrent.duration.*

/**
 * The audit on the in-memory broker (T054; FR-028, SC-006, contracts/audit.md): a record within one minute of each
 * approval request, decision, tool call, guardrail rejection and configuration version, with no message text, no
 * `feedback` and no personal value.
 */
class AuditSuite extends munit.FunSuite:
  override val munitTimeout = 180.seconds
  private lazy val kit = SharedKit.kit

  override def beforeEach(context: BeforeEach): Unit =
    kit.reset()
    kit.configure(): Unit

  private def records(session: String, action: String, n: Int = 1): Vector[AuditRecord] =
    kit.eventually(s"$n $action for $session", within = 1.minute) {
      Option(kit.audits.filter(r => r.correlationId == session && r.action == action)).filter(_.size >= n)
    }

  private def withdrawal = AgentJson.obj("amount" -> AgentJson.num(5000d), "currency" -> AgentJson.str("GBP"))

  test("an approved write is audited as its request, the player's decision and the call, each naming the approval"):
    kit.model.expectToolCall("request_withdrawal", withdrawal, "call-w")
    assertEquals(kit.chat("p-aud", "audit-approve", "withdraw £50 for my holiday").names(), Vector("confirm_action", "done"))
    val id = kit.conversation("audit-approve").awaiting.map(_._1).getOrElse(fail("no approval awaited"))
    kit.model.expectText("Done.")
    assertEquals(kit.resume("p-aud", "audit-approve", "approve").names().last, "done")
    val requested = records("audit-approve", "support.approval.requested").head
    val decided   = records("audit-approve", "support.approval.decided").head
    val called    = records("audit-approve", "support.tool.request_withdrawal").head
    assertEquals((requested.outcome, requested.causationId, requested.subjectId), ("awaiting", id, "p-aud"))
    assertEquals((decided.outcome, decided.actorKind, decided.actorId), ("approved", "player", "p-aud"))
    assertEquals((called.outcome, called.approverId), ("ok", Some(id)))
    assertEquals(called.changes, Vector(AuditChange("amount", None, Some("5000")), AuditChange("currency", None, Some("GBP"))))
    assertEquals(called.key, "player/p-aud")

  test("a refused decision is audited without the player's feedback"):
    kit.model.expectToolCall("request_withdrawal", withdrawal, "call-w")
    assertEquals(kit.chat("p-aud", "audit-reject", "withdraw £50").names().last, "done")
    kit.model.expectText("Nothing was withdrawn.")
    assertEquals(kit.resume("p-aud", "audit-reject", "reject", Some("my partner would see it")).names().last, "done")
    assertEquals(records("audit-reject", "support.approval.decided").head.outcome, "refused")
    kit.testKit.assertNoPersonalValueIn(kit.broker)("my partner would see it", "withdraw £50")

  test("a guardrail's rejection is a security record naming the guardrail and the side"):
    kit.chat("p-aud", "audit-guard", "what does someone@example.com have?").names(): Unit
    val r = records("audit-guard", "support.guardrail.rejected").head
    assertEquals((r.reasonCode, r.recordClass, r.changes.find(_.field == "side").flatMap(_.after)), (Some("other-player"), "security", Some("input")))
    kit.testKit.assertNoPersonalValueIn(kit.broker)("someone@example.com")

  test("a configuration version is audited as the operator's, naming the fields, never the prompt's text"):
    val put = kit.request("PUT", s"/v1/casino/agent-config/${kit.slug}/", Some("""{"prompt": "A secret prompt.", "turnsPerMinute": 30}"""),
      kit.asOperator(subject = "op-aud"))
    assertEquals(put.status, 200)
    val version = io.eitheror.casino.support.Json.parse(put.text).flatMap(n => Option(n.get("version"))).map(_.asLong).get
    val r = kit.eventually("the version's record", within = 1.minute) {
      kit.audits.find(_.recordId == s"support-config/${kit.slug}/$version")
    }
    assertEquals((r.actorKind, r.actorId, r.subjectKind, r.action), ("operator", "op-aud", "brand", "support.config.changed"))
    assertEquals(r.changes.toSet, Set(AuditChange("prompt", None, None), AuditChange("turnsPerMinute", None, Some("30"))))
    // The prompt is the configuration's to keep; only the audit must not carry it.
    val published = kit.broker.publishedTo(PlatformAudit.name).map(d => String(d.message.payload, UTF_8))
    assert(!published.exists(_.contains("A secret prompt.")), "an audit record carries the prompt")

package io.eitheror.casino.contract.audit

import com.thinkmorestupidless.ankka.sdk.{ChangeSource, ConsumerContext}
import com.thinkmorestupidless.ankka.testkit.ConsumerTestKit
import io.eitheror.casino.contract.facts.{AuditRecord, PlatformAudit}
import com.thinkmorestupidless.ankka.core.Serializer

import java.time.Instant

/** The hoisted audit base (spec 006 T003): one record per change the mapping names, none otherwise, `ce-id` its record id. */
class AuditConsumerSuite extends munit.FunSuite:
  import AuditConsumerSuite.*

  private val kit = ConsumerTestKit.of(Refusals)

  test("a change the mapping names is one record on platform.audit, keyed by its key, with ce-id its record id") {
    val r = kit.onMessage("refused", subject = "r-1", sequenceNumber = 1)
    assertEquals(r.payloads.size, 1)
    val record = r.payloads.head.asInstanceOf[AuditRecord]
    assertEquals((record.recordId, record.service, record.subjectId), ("test/r-1", "test", "r-1"))
    assertEquals(r.keys, Vector(Some(record.key)))
    assertEquals(r.messages.head.metadata.get("ce-id"), Some("test/r-1"))
  }

  test("a change the mapping does not name produces nothing") {
    assertEquals(kit.onMessage("served", subject = "r-2", sequenceNumber = 1).payloads, Vector.empty)
  }

  test("the companion produces to platform.audit under its contract") {
    assertEquals(Refusals.produceTo, Some(PlatformAudit.name))
    assertEquals(Refusals.produces.flatMap(_.contract), Some(PlatformAudit.contract))
  }

object AuditConsumerSuite:
  private val at = Instant.parse("2026-10-22T10:00:00Z")

  final class RefusalAudit extends AuditConsumer[String]:
    protected def audit(change: String): Option[AuditRecord] =
      Option.when(change == "refused") {
        val id = s"test/${messageContext.subject}"
        AuditRecord(id, "payments", "test", "create", "unknown", "unknown", None, "request", messageContext.subject, "refused",
          Some("key_unknown"), Vector.empty, messageContext.subject, id, "security", at)
      }

  object Refusals extends AuditCompanion[RefusalAudit, String]("refusal-audit",
        ChangeSource.KeyValue(com.thinkmorestupidless.ankka.core.ComponentId("refusals"), Serializer.json[String]("test-refusal")(using
          com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker.make[String]))):
    def create(ctx: ConsumerContext) = new RefusalAudit

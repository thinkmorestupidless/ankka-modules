package io.eitheror.casino.players.erasure

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.contract.facts.{AuditRecord, PlatformAudit}
import io.eitheror.casino.players.facts.Facts
import io.eitheror.casino.players.it.{Reply, SharedKit}
import io.eitheror.casino.players.player.{PlayerEntity, PlayerEvent}

import java.time.{Duration, Instant}
import scala.concurrent.duration.*

/**
 * Spec 018 FR-019 (T048): an erasure request writes one record per state, filed, held, applied and
 * completed, on the brand's `platform.audit`, under the erasure's correlation id, with nothing personal.
 */
class ErasureAuditSuite extends munit.FunSuite with LogCapturing:
  override val munitTimeout = 180.seconds
  private lazy val kit = SharedKit.kit

  override def beforeEach(context: BeforeEach): Unit = kit.reset()

  private def op(method: String, path: String): Reply =
    kit.request(method, kit.operatorPath(path), if method == "GET" then None else Some("{}"), kit.asOperator(subject = "op-6"))

  private def erasureRecords(id: String): Vector[AuditRecord] =
    kit.facts(PlatformAudit).collect { case a: AuditRecord if a.subjectId == id && a.action.startsWith("erasure.") => a }

  test("the mapping: filed from the filing, each later state from its own event, and nothing else") {
    val at = Instant.parse("2026-11-09T08:00:00Z")
    val filed = Facts.erasureAudit("goldvibe", "p-1", 7, PlayerEvent.ErasureFiled("corr-1", at, at)).map(_.fact)
    assertEquals(filed.map(r => (r.action, r.actorKind, r.recordClass, r.correlationId, r.changes.head.after)),
      Some(("erasure.filed", "system", "erasure", "corr-1", Some("filed"))))
    val held = Facts.erasureAudit("goldvibe", "p-1", 8, PlayerEvent.ErasureStageReached("corr-1", "held", at)).map(_.fact)
    assertEquals(held.map(_.action), Some("erasure.held"))
    // A personal field's name with a value would be quarantined by the shared audit (spec 018 FR-003).
    assert((filed ++ held).flatMap(_.changes).forall(c => !io.eitheror.casino.contract.personal.PersonalFields.names(c.field)))
    assertEquals(Facts.erasureAudit("goldvibe", "p-1", 9, PlayerEvent.Erased(at)), None)
    assertEquals(Facts.playerAudit("goldvibe", "p-1", 7, PlayerEvent.ErasureFiled("corr-1", at, at)), None)
  }

  test("an erasure filed, held, applied and completed is one record each, in order, under its correlation id") {
    val (email, id) = kit.activePlayer("firstName" -> "Ida", "lastName" -> "Rune", "dateOfBirth" -> "1981-02-03")
    assertEquals(op("POST", s"/players/$id/close").status, 200)
    val filed = op("POST", s"/players/$id/erasure")
    assertEquals(filed.status, 202, filed.text)
    val correlation = filed.str("correlationId")
    kit.eventually("held")(Option.when(erasureRecords(id).exists(_.action == "erasure.held"))(()))
    kit.advance(Duration.ofDays(ErasureWorkflow.DefaultRetentionDays.toLong + 1))
    val records = kit.eventually("every state", 30.seconds)(Option(erasureRecords(id)).filter(_.exists(_.action == "erasure.completed")))
    assertEquals(records.map(_.action), Vector("erasure.filed", "erasure.held", "erasure.applied", "erasure.completed"))
    assert(records.forall(r => r.correlationId == correlation && r.brand == kit.slug && r.outcome == "accepted"))
    val texts = kit.factTexts(PlatformAudit).filter(_.contains(correlation))
    assert(!texts.exists(t => t.contains(email) || t.contains("Rune")), texts.mkString("\n"))
    // The entity holds each state once: asked again, it records nothing more.
    kit.components.forEventSourcedEntity(PlayerEntity.id(id)).call(PlayerEntity.erasureStage)
      .invoke(io.eitheror.casino.players.player.ErasureStage("held", kit.now())): Unit
    Thread.sleep(500)
    assertEquals(erasureRecords(id).count(_.action == "erasure.held"), 1)
  }

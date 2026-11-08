package io.eitheror.casino.engagement.facts

import io.eitheror.casino.contract.facts.PlatformAudit
import io.eitheror.casino.engagement.config.{ConfigNote, CrmConfig}
import io.eitheror.casino.engagement.profile.ProfileState
import io.eitheror.casino.engagement.send.*

import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant

/** contracts/facts.md's records on `platform.audit`: which updates make one, what they say, and no personal value. */
class AuditSuite extends munit.FunSuite:
  private val at = Instant.parse("2026-11-03T10:00:00Z")
  private val request = SendRequest("deposit_confirmed/f-1", "p-1", "deposit_confirmed", Some("deposit_confirmed"), "marketing",
    SendRequest.Crm, "f-1", at, transactional = Some("DepositConfirmed"),
    recipient = Some(ProfileState.personal("p-1", "ada@example.com")),
    data = Vector(DataField("amount", ProfileState.personal("p-1", "10.00"))))
  private val base = SendState(request = Some(request), updatedAt = Some(at))

  private def text(r: io.eitheror.casino.contract.facts.AuditRecord) = String(PlatformAudit.serializer.toBytes(r), UTF_8)

  test("a send refused under FR-011 is one record naming the reason, the trigger and the class") {
    val s = base.copy(outcome = Outcome.MarketingSuppressed, reason = Some("self_excluded")).noted(Send.RefusedNote, None, at.plusSeconds(1))
    val r = Audit.send("goldvibe", request.key, s).get
    assertEquals((r.action, r.actorKind, r.subjectKind, r.subjectId, r.outcome, r.reasonCode),
      ("engagement.send.refused", "system", "player", "p-1", "refused", Some("marketing_suppressed")))
    assertEquals(r.changes.map(c => c.field -> c.after), Vector("send" -> Some(request.key), "trigger" -> Some("deposit_confirmed"),
      "class" -> Some("marketing"), "reason" -> Some("self_excluded")))
    assertEquals(r.recordId, s"send/${request.key}/refused/1")
  }

  test("a later update of the same send writes no record again") {
    val s = base.noted(Send.Retried, Some("op-1"), at.plusSeconds(1))
    assert(Audit.send("goldvibe", request.key, s).isDefined)
    assertEquals(Audit.send("goldvibe", request.key, s.copy(updatedAt = Some(at.plusSeconds(2)))), None)
    assertEquals(Audit.send("goldvibe", request.key, base), None)
  }

  test("an operator's retry and discard name the operator, and the discard's reason is not on the record") {
    val retried = base.copy(outcome = Outcome.Pending).noted(Send.Retried, Some("op-1"), at.plusSeconds(1))
    val r = Audit.send("goldvibe", request.key, retried).get
    assertEquals((r.action, r.actorKind, r.actorId, r.recordClass), ("engagement.send.retried", "operator", "op-1", "operator"))
    val discarded = retried.copy(outcome = Outcome.Discarded, discardedBy = Some("op-2"),
      discardReason = Some(ProfileState.personal("p-1", "the player's lawyer asked"))).noted(Send.DiscardedNote, Some("op-2"), at.plusSeconds(2))
    val d = Audit.send("goldvibe", request.key, discarded).get
    assertEquals((d.action, d.actorId, d.outcome, d.recordId), ("engagement.send.discarded", "op-2", "discarded", s"send/${request.key}/discarded/2"))
    assert(!text(d).contains("lawyer"), text(d))
  }

  test("no record holds the recipient, the data or the player's address") {
    val records = Vector(
      base.copy(outcome = Outcome.AccountClosed, reason = Some("account_closed")).noted(Send.RefusedNote, None, at.plusSeconds(1)),
      base.noted(Send.Retried, Some("op-1"), at.plusSeconds(1))
    ).flatMap(Audit.send("goldvibe", request.key, _))
    assertEquals(records.size, 2)
    for r <- records; v <- Seq("ada@example.com", "10.00") do assert(!text(r).contains(v), text(r))
  }

  test("a configuration change names the fields and the keys rewritten, never a value") {
    val c = CrmConfig("site-1", "eu", "hello@goldvibe.example", Map("DepositConfirmed" -> "7"),
      lastChange = Some(ConfigNote(3, "op-1", Vector("region", "trackApiKey"), at)))
    val r = Audit.configs("goldvibe", "goldvibe", c).last
    assertEquals((r.action, r.subjectKind, r.subjectId, r.actorId, r.recordId),
      ("engagement.config.changed", "brand", "goldvibe", "op-1", "crm-config/goldvibe/3"))
    assertEquals(r.changes.map(ch => (ch.field, ch.before, ch.after)), Vector(("region", None, None), ("trackApiKey", None, None)))
    for v <- Seq("site-1", "hello@goldvibe.example", "eu\"") do assert(!text(r).contains(v), text(r))
    assertEquals(Audit.configs("goldvibe", "goldvibe", c.copy(lastChange = None)), Vector.empty)
  }

  test("each change the state keeps is a record, so a skipped state loses none (FR-029); its id is the change's") {
    val notes = Vector(ConfigNote(4, "op-1", Vector("region"), at), ConfigNote(5, "op-2", Vector("siteId"), at.plusSeconds(1)))
    val c     = CrmConfig("site-2", "us", lastChange = notes.lastOption, recentChanges = notes)
    assertEquals(Audit.configs("goldvibe", "goldvibe", c).map(r => (r.recordId, r.actorId)),
      Vector(("crm-config/goldvibe/4", "op-1"), ("crm-config/goldvibe/5", "op-2")))
  }

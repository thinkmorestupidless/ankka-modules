package io.eitheror.casino.players.facts

import io.eitheror.casino.contract.personal.PersonalFields
import io.eitheror.casino.players.player.PlayerEvent

import java.time.Instant

/**
 * The player's audit records name no personal field with a value: the shared audit quarantines one
 * that does (spec 018 FR-003), and the action would never reach its store.
 */
class AuditMappingSuite extends munit.FunSuite:
  private val at = Instant.parse("2026-11-09T08:00:00Z")

  test("a consent change, by the player or an operator, on every channel, names the consent and not the channel") {
    val events = for
      channel <- Vector("email", "sms", "push", "phone")
      (source, actor) <- Vector("player" -> None, "operator" -> Some("op-1"))
    yield PlayerEvent.ConsentChanged(channel, granted = true, source, actor, at)
    val records = events.flatMap(e => Facts.playerAudit("goldvibe", "p-1", 3, e).map(_.fact))
    assertEquals(records.size, events.size)
    val changes = records.flatMap(_.changes)
    assertEquals(changes.map(_.field).distinct.sorted, Vector("consent.email", "consent.phone", "consent.push", "consent.sms"))
    assert(changes.forall(c => !PersonalFields.names(c.field)), changes.map(_.field).toString)
  }

  test("manager tags set (spec 030 P7) are one record with the operator, the player, and the list before and after") {
    val r = Facts.playerAudit("goldvibe", "p-1", 4, PlayerEvent.ManagerTagsSet(Vector("host: ana", "high value"), Vector("vip"), "op-1", at))
      .map(_.fact).getOrElse(fail("no record"))
    assertEquals((r.action, r.actorId, r.subjectId, r.occurredAt), ("manager-tags", "op-1", "p-1", at))
    assertEquals(r.changes.map(c => (c.field, c.before, c.after)), Vector(("managerTags", Some("vip"), Some("host: ana, high value"))))
    assert(r.changes.forall(c => !PersonalFields.names(c.field)))
  }

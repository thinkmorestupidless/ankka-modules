package io.eitheror.casino.audit.it

import io.eitheror.casino.audit.store.Records
import io.eitheror.casino.contract.facts.AuditChange

import scala.concurrent.duration.*

/**
 * SC-002: every real id a run uses is searched for in every table of the service's database, every
 * answer, every export and the captured log. Only the registry's `identity` rows may hold one. A kit of
 * its own, so its database holds this suite's work alone. The routes' answers and the exports are added
 * to the search as their stories land.
 */
class LeakSuite extends munit.FunSuite:
  override val munitTimeout: Duration = 5.minutes
  private var kit: Kit = null

  override def beforeAll(): Unit = kit = Kit()
  override def afterAll(): Unit  = if kit != null then kit.stop()

  // Real ids carry a marker no other field has, so a match anywhere is a leak.
  private val player    = "leakplayer-7f3a91"
  private val operator  = "leakoperator-2c8e44"
  private val approver  = "leakapprover-5d1b07"
  private val affiliate = "leakaffiliate-9e6c12"
  private val realIds   = Vector(player, operator, approver, affiliate)

  test("no real id rests anywhere but the registry's identities, nor in a log line") {
    kit.rewire()
    val records = Vector(
      Kit.record(id = s"wallet/$player/EUR/k-1", actorId = operator, approverId = Some(approver), subjectId = player,
        correlationId = s"corr/$player", causationId = s"cmd/$operator", category = "money",
        changes = Vector(AuditChange("amount", None, Some("100")), AuditChange("target", Some(s"$player/bonus"), Some(s"$player/cash")))),
      Kit.record(id = s"players/$player/email", actorKind = "player", actorId = player, subjectId = player, category = "account",
        changes = Vector(AuditChange("email", None, None))),
      Kit.record(id = s"programme/$affiliate/x", actorKind = "affiliate", actorId = affiliate, subjectKind = "market", subjectId = "m-1"),
      Kit.record(id = s"refused/$player", actorKind = "service", actorId = s"wallet/$player", subjectId = player, outcome = "refused",
        category = "money")
    )
    records.foreach(kit.publish("goldvibe", _))
    kit.publish("payments", Kit.record(id = s"merchant/$player", service = "merchant", actorId = operator, subjectId = player))
    // A malformed record naming a player, quarantined sealed.
    kit.publishBytes("goldvibe", player, s"""{"type":"audit_record","actorId":"$player"""".getBytes)
    kit.eventually(60.seconds)(Option.when(kit.records.size >= records.size + 1)(()))
    Thread.sleep(1500)

    val db    = kit.database()
    val found = for
      id         <- realIds
      (table, row) <- db
      if row.contains(id) && !row.contains("audit.identity")
    yield s"$id in $table: ${row.take(300)}"
    assertEquals(found, Vector.empty)
    val identities = db.map(_._2).filter(_.contains("audit.identity")).mkString("\n")
    Vector(player, operator, approver, affiliate).foreach(id => assert(identities.contains(id), s"the registry holds $id"))

    val rows = kit.views.forView(Records).all(100000).map(_.toString)
    assertEquals(realIds.filter(id => rows.exists(_.contains(id))), Vector.empty)

    val logged = kit.logLines.filter(line => realIds.exists(line.contains))
    assertEquals(logged, Vector.empty)
  }

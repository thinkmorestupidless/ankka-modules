package io.eitheror.casino.engagement.it

import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.contract.facts as f
import io.eitheror.casino.contract.personal.Personal
import io.eitheror.casino.engagement.Json
import io.eitheror.casino.engagement.profile.{MoneyFact, Profile}
import io.eitheror.casino.engagement.send.Outcome

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration.*

/**
 * SC-006 (FR-015): what a dump of the database holds — every row of every table of the service's schema as text, the
 * journal, the views, the workflows' state and the timers, which is what `pg_dump` writes as data — has no readable
 * email address, name, date of birth or transactional text, though each was stored: in the profile, in a parked send
 * and in an accepted route's send.
 */
class DumpSuite extends munit.FunSuite:
  override val munitTimeout = 180.seconds
  private lazy val kit   = SharedKit.kit
  private lazy val facts = Facts(kit)

  override def beforeAll(): Unit =
    kit.reset()
    kit.configure(Map("DepositConfirmed" -> "7", "PasswordResetRequest" -> "21"))

  test("no table holds the address, the names, the date of birth or the transactional text in the clear") {
    kit.crm.reset()
    kit.crm.expect(FakeCrm.SiteId, FakeCrm.TrackKey, FakeCrm.AppKey)
    val tag = UUID.randomUUID().toString.take(8)
    val p   = s"dump-$tag"
    val at  = Instant.now().minusSeconds(600)
    val address = s"ada.$tag@lovelace.example"
    facts.players(f.PlayerRegistered(p, at, None, None, None, Some("GB"), Some("EUR"), None, at,
      givenName = Some(Personal(s"Augusta$tag")), familyName = Some(Personal(s"Byron$tag")),
      dateOfBirth = Some(Personal("1815-12-10")), email = Some(Personal(address))))
    facts.players(f.PlayerConsentChanged(p, "email", true, "registration", at))
    // A deposit before, so the parked one is not also a first deposit sharing the CRM's failures.
    kit.components.forKeyValueEntity(EntityId(p)).call(Profile.money).invoke(MoneyFact(facts.deposit(p, at.plusSeconds(60)))): Unit

    kit.crm.fail(503, 4)
    val parked = facts.deposit(p)
    facts.publish(parked)
    assertEquals(facts.settled(facts.key("deposit_confirmed", parked)).outcome, Outcome.Parked)

    val text = s"reset-link-$tag"
    val r = kit.json("POST", kit.engagementPath("/sends"), Json.obj("key" -> tag, "playerId" -> p, "trigger" -> "PasswordResetRequest",
      "to" -> address, "link" -> s"https://x/$text", "data" -> Json.obj("note" -> s"free text $tag")), kit.asService("players"))
    assertEquals(r.str("status"), "accepted", r.text)
    assert(kit.crm.sends.exists(_.path("to").asText == address), "the CRM was sent the address")

    val dump = kit.tables
    assert(dump.values.flatten.exists(_.contains(p)), "the dump holds the player's rows")
    for clear <- Vector(address, s"Augusta$tag", s"Byron$tag", "1815-12-10", text, s"free text $tag") do
      val holding = dump.collect { case (t, rows) if rows.exists(_.contains(clear)) => t }
      assertEquals(holding.toVector, Vector.empty, s"$clear is readable in")
  }

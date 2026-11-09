package io.eitheror.casino.audit.it

import io.eitheror.casino.audit.api.IdentitiesRoute
import io.eitheror.casino.audit.stored.Visibility

import scala.concurrent.duration.*

/** `/v1/audit-identities` (FR-017, FR-018, SC-005): both directions for the re-identifier; everyone else refused, and recorded. */
class ReidentifySuite extends munit.FunSuite:
  override val munitTimeout: Duration = 180.seconds
  private var kit: Kit = null

  override def beforeAll(): Unit = kit = Kit()
  override def afterAll(): Unit  = if kit != null then kit.stop()
  override def beforeEach(context: BeforeEach): Unit = kit.rewire()

  /** A player of goldvibe's pseudonym, issued by the lookup route. */
  private def pseudonymOf(real: String, brand: String = "goldvibe"): (String, Boolean) =
    val r = kit.identities("pseudonym", kit.asReidentifier(brand), s"""{"brand":"$brand","kind":"player","id":"$real","reasonCode":"setup"}""")
    assertEquals(r.status, 200, r.text)
    (r.json.get("pseudonym").asText, r.json.get("issued").asBoolean)

  private def recordFor(action: String, subject: String, outcome: String) =
    kit.eventually()(kit.own(action).find(r => r.subject == subject && r.outcome == outcome))

  test("the re-identifier is told who a pseudonym is; the question is recorded, visible to the brand, without the real id") {
    val real = kit.fresh("p")
    val (pseudonym, _) = pseudonymOf(real)
    val r = kit.identities("who", kit.asReidentifier("goldvibe", "officer-who"), s"""{"pseudonym":"$pseudonym","reasonCode":"regulator_request"}""")
    assertEquals(r.status, 200, r.text)
    assertEquals((r.json.get("brand").asText, r.json.get("kind").asText, r.json.get("id").asText), ("goldvibe", "player", real))
    val made = recordFor(IdentitiesRoute.Who, pseudonym, "done")
    assertEquals((made.brand, made.visibility), ("goldvibe", Visibility.Installation))
    assert(made.changes.exists(c => c.field == "reason" && c.after.contains("regulator_request")))
    assert(made.actor.nonEmpty && made.actor != "officer-who")
    assert(!made.toString.contains(real), made.toString)
    // The brand's compliance role reads it.
    val seen = kit.query(kit.asBrandCompliance("goldvibe"), "brand" -> "goldvibe", "action" -> IdentitiesRoute.Who, "subject" -> pseudonym)
    assertEquals(seen.records.size, 1, seen.text)
  }

  test("the pseudonym of a real id is answered when it exists and issued when not, and each lookup is recorded") {
    val real = kit.fresh("p")
    val (first, issued)  = pseudonymOf(real)
    val (again, issued2) = pseudonymOf(real)
    assertEquals((issued, issued2, again), (true, false, first))
    kit.eventually()(Option(kit.own(IdentitiesRoute.Lookup).filter(_.subject == first)).filter(_.size == 2))
    assertEquals(kit.identities("pseudonym", kit.asReidentifier("goldvibe"), s"""{"brand":"goldvibe","kind":"nobody","id":"x","reasonCode":"r"}""").status, 400)
    assertEquals(kit.identities("pseudonym", kit.asReidentifier("goldvibe"), s"""{"brand":"goldvibe","kind":"player","id":"x"}""").status, 400)
  }

  test("everyone else is refused as not_reidentifier, and the refusal is recorded") {
    val (pseudonym, _) = pseudonymOf(kit.fresh("p"))
    val others = Vector(
      kit.asBrandCompliance("goldvibe"),          // compliance without the re-identification role
      kit.asInstallationCompliance(),             // the installation's compliance role alone never may
      kit.asReidentifier("spinvibe"),             // another brand's re-identifier
      kit.asOwner()
    )
    others.foreach { as =>
      val r = kit.identities("who", as, s"""{"pseudonym":"$pseudonym","reasonCode":"curious"}""")
      assertEquals((r.status, r.code), (403, IdentitiesRoute.NotReidentifier), r.text)
    }
    val lookup = kit.identities("pseudonym", kit.asBrandCompliance("goldvibe"), s"""{"brand":"goldvibe","kind":"player","id":"p-x","reasonCode":"curious"}""")
    assertEquals((lookup.status, lookup.code), (403, IdentitiesRoute.NotReidentifier))
    val refusals = kit.eventually()(Option(kit.own(IdentitiesRoute.Who).filter(r => r.subject == pseudonym && r.outcome == "refused")).filter(_.size == 4))
    assert(refusals.forall(r => r.brand == "goldvibe" && r.reasonCode.contains(IdentitiesRoute.NotReidentifier)))
    kit.eventually()(kit.own(IdentitiesRoute.Lookup).find(r => r.outcome == "refused" && r.brand == "goldvibe"))
  }

  test("an unknown pseudonym is unknown to a re-identifier, and refused to anyone else") {
    val nobody = java.util.UUID.randomUUID().toString
    assertEquals(kit.identities("who", kit.asReidentifier("goldvibe"), s"""{"pseudonym":"$nobody","reasonCode":"r"}""").code, IdentitiesRoute.UnknownPseudonym)
    assertEquals(kit.identities("who", kit.asBrandCompliance("goldvibe"), s"""{"pseudonym":"$nobody","reasonCode":"r"}""").status, 403)
    assertEquals(kit.request("POST", "/v1/audit-identities/who", Some("{}")).status, 401)
  }

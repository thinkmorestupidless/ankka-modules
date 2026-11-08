package io.eitheror.casino.engagement.it

import com.thinkmorestupidless.ankka.core.{DataSubject, EntityId}
import com.thinkmorestupidless.ankka.sdk.ErasureContext
import io.eitheror.casino.engagement.api.Erasure
import io.eitheror.casino.engagement.profile.Profile
import io.eitheror.casino.engagement.reads.SendsView
import io.eitheror.casino.engagement.send.Outcome

import java.time.Instant
import scala.concurrent.duration.*

/**
 * SC-007 (FR-016; research R15): `players`' erasure of a player asks Customer.io to delete the person, ends every open
 * send of theirs `discarded`, erases the profile, and afterwards a replayed fact of theirs makes no call. Only
 * `players` may call it.
 */
class ErasureSuite extends munit.FunSuite:
  override val munitTimeout = 180.seconds
  private lazy val kit   = SharedKit.kit
  private lazy val facts = Facts(kit)

  override def beforeAll(): Unit =
    kit.reset()
    kit.configure(Map("DepositConfirmed" -> "7"))

  override def beforeEach(context: BeforeEach): Unit =
    kit.crm.reset()
    kit.crm.expect(FakeCrm.SiteId, FakeCrm.TrackKey, FakeCrm.AppKey)

  private def erase(p: String, as: Seq[(String, String)] = kit.asService("players")): Reply =
    kit.request("POST", kit.engagementPath(s"/erasure/players/$p"), None, as)

  test("the erasure deletes the person at the CRM, discards the open sends, erases the profile; a replayed fact is silent") {
    val p = facts.player("erase")
    val done = facts.deposit(p)
    facts.publish(done)
    assertEquals(facts.settled(facts.key("deposit_confirmed", done)).outcome, Outcome.Accepted)
    kit.crm.fail(503, 4)
    val parked = facts.deposit(p)
    facts.publish(parked)
    val k = facts.key("deposit_confirmed", parked)
    assertEquals(facts.settled(k).outcome, Outcome.Parked)
    kit.eventually("the parked send in the view")(SendsView.byPlayer(kit.views.forView(SendsView), p).rows.find(r => r.key == k && r.outcome == Outcome.Parked)): Unit

    val a = erase(p)
    assertEquals(a.status, 200, a.text)
    assertEquals((a.str("service"), a.str("subject"), a.str("crm"), a.json.get("sends").asInt), ("engagement", s"player/$p", "deleted", 1))
    assertEquals(kit.crm.deletes(p).size, 1)
    assertEquals(facts.settled(k).outcome, Outcome.Discarded)
    val profile = kit.components.forKeyValueEntity(EntityId(p)).call(Profile.get).invoke()
    assert(profile.erased && profile.email.isEmpty && profile.givenName.isEmpty, s"$profile")

    kit.crm.reset()
    facts.publish(done)
    val later = facts.deposit(p)
    facts.publish(later)
    assertEquals(facts.settled(facts.key("deposit_confirmed", later)).outcome, Outcome.Discarded)
    Thread.sleep(1000)
    assertEquals(facts.calls(p), Vector.empty)

    val again = erase(p)
    assertEquals((again.str("crm"), again.json.get("sends").asInt), ("deleted", 0))
    assertEquals(kit.crm.deletes(p).size, 0)
  }

  test("a CRM that cannot delete leaves the deletion parked and answers failed") {
    val p = facts.player("erase")
    kit.crm.fail(503, 4)
    val a = erase(p)
    assertEquals((a.status, a.str("crm")), (200, "failed"), a.text)
    assertEquals(facts.settled(s"erasure/$p").outcome, Outcome.Parked)
  }

  test("an erasure filed at the installation runs the same: the CRM's person deleted, the profile erased; another subject is not ours") {
    val p = facts.player("erase")
    def run(subject: String, again: Boolean = false) = Erasure.handler.run(new ErasureContext:
      def client   = kit.components
      def secrets  = throw UnsupportedOperationException("not read")
      def services = throw UnsupportedOperationException("not read")
      def storage  = throw UnsupportedOperationException("not read")
      def erasureSeq = 1L
      def rerun      = again
    )(DataSubject(subject))
    assertEquals(run(s"player/$p").objectsErased, 1L)
    assertEquals(kit.crm.deletes(p).size, 1)
    assert(kit.components.forKeyValueEntity(EntityId(p)).call(Profile.get).invoke().erased)
    run(s"player/$p", again = true): Unit
    assertEquals(kit.crm.deletes(p).size, 1, "a re-run deletes nothing more")
    assertEquals(run("merchant/m-1").objectsErased, 0L)
  }

  test("no service but players may erase") {
    val p = facts.player("erase")
    val other = erase(p, kit.asService("rewards"))
    assert(other.status == 401 || other.status == 403, s"${other.status} ${other.text}")
    assert(!kit.components.forKeyValueEntity(EntityId(p)).call(Profile.get).invoke().erased)
    assertEquals(kit.crm.deletes(p), Vector.empty)
  }

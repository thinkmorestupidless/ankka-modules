package io.eitheror.casino.reporting.it

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.reporting.documents.Document

import scala.concurrent.duration.*

/**
 * SC-006 (FR-025, research R10): with a player's email, network address and user agent folded into every view, a dispute
 * report kept synchronously and one rendered by a job, an export listing the player and a ledger searched by their
 * email, the service's database holds none of the three in the clear: not in a journal, a view, a document record, a
 * job's state, nor a timer. The documents themselves, in the bucket, hold them for their lifetime only.
 */
class DumpSuite extends munit.FunSuite with LogCapturing:
  override val munitTimeout = 300.seconds
  private lazy val kit = SharedKit.kit

  test("the store holds no personal value in the clear (SC-006)") {
    val d = kit.days()
    val p = Disputed(kit, d).usual().publish()
    val l = p.logins.head
    assertEquals(p.bySession(format = "csv").status, 200)
    val queued = p.byRange(d.minusDays(8), d, "pdf")
    assertEquals(queued.status, 202, queued.text)
    val ref = queued.json.path("reference").asText
    kit.eventually(s"$ref ready", 120.seconds)(Option.when(Disputed.document(kit, ref).status == Document.Ready)(()))
    val registered = d.minusDays(1)
    assertEquals(kit.get(s"/reports/players?from=$registered&to=$registered&currency=EUR&format=csv", kit.asFinance).status, 200)
    // The search reads the Players and Transactions views, which reach the published facts in their own time.
    kit.eventually("the ledger's rows found by the email", 60.seconds)(
      Some(kit.get(s"/reports/transactions/export?from=$d&to=$d&playerEmail=${p.email}&format=csv", kit.asFinance))
        .filter(r => r.status == 200 && r.text.contains(p.player))): Unit
    kit.testKit.assertNoPersonalValue(p.email, l.address, l.agent)
  }

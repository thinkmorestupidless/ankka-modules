package io.eitheror.casino.rg.it

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.rg.Json.*
import io.eitheror.casino.rg.profile.{CloseCase, OpenCase, ProfileEntity}

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration.*

/**
 * The brand's cases (spec 030 G2): `cases?status=` across every player, oldest opened first, paged, with the
 * status's total, and its view's freshness on `status`. The kit holds every test's cases, so these are opened
 * long before any other test's, which makes them the first of the list.
 */
class CasesSuite extends munit.FunSuite with LogCapturing:
  override val munitTimeout = 3.minutes

  private lazy val kit = SharedKit.kit
  override def beforeEach(context: BeforeEach): Unit = kit.reset()

  private def cases(query: String): Reply = kit.request("GET", kit.operatorPath(s"/cases?$query"), None, kit.asOperator())
  private def ids(j: JsonNode) = j.objects("cases").flatMap(_.text("caseId"))

  test("open cases of several players, oldest opened first, a page a slice of them, each answer with its total"):
    val n  = UUID.randomUUID().toString.take(8)
    val at = (d: Int) => Instant.parse(s"2000-01-0${d}T00:00:00Z")
    def open(d: Int): String =
      val p = s"cs-$n-$d"
      kit.components.forEventSourcedEntity(EntityId(p)).call(ProfileEntity.openCase)
        .invoke(OpenCase(p, "account_block", "kit", "op-1", at(d))).caseId.get
    val third = open(3); val first = open(1); val second = open(2)
    val page = kit.eventually("the three listed")(Some(cases("status=open&pageSize=3")).filter(r => r.status == 200 && ids(r.json) == Vector(first, second, third)))
    val j = page.json
    assertEquals((j.long("page"), j.long("pageSize")), (Some(1L), Some(3L)))
    val row = j.objects("cases").head
    assertEquals((row.text("playerId"), row.text("caseType"), row.text("status"), row.text("openedAt")),
      (Some(s"cs-$n-1"), Some("account_block"), Some("open"), Some("2000-01-01T00:00:00Z")))
    assert(!page.text.contains("kit"), page.text) // no note: they are personal and stay on the profile
    assertEquals(ids(cases("status=open&page=2&pageSize=1").json), Vector(second))
    val openTotal = j.long("total").get
    assert(openTotal >= 3L, page.text)

    // Closed, a case leaves the open list and is among the closed, its close on the row.
    kit.components.forEventSourcedEntity(EntityId(s"cs-$n-1")).call(ProfileEntity.closeCase)
      .invoke(CloseCase(first, "resolved", "op-1", at(4))): Unit
    val closed = kit.eventually("closed")(Some(cases("status=CLOSED&pageSize=200")).map(_.json).flatMap(_.objects("cases").find(_.text("caseId").contains(first))))
    assertEquals((closed.text("status"), closed.text("closedAt"), closed.text("closeReason")), (Some("closed"), Some("2000-01-04T00:00:00Z"), Some("closed")))
    // The operator's words are the close's note, personal, kept on the profile alone.
    assert(!closed.toString.contains("resolved"), closed.toString)
    assertEquals(ids(cases("status=open&pageSize=2").json), Vector(second, third))
    val all = cases("pageSize=1").json
    assert(all.long("total").exists(_ >= 3L), all.toString)
    assertEquals(cases(s"status=open&page=${openTotal + 10}&pageSize=1").json.objects("cases"), Vector.empty)

  test("a value the list does not take is a 400 naming it; the list requires protection.read"):
    for (query, field) <- Seq("status=pending" -> "status", "page=0" -> "page", "pageSize=201" -> "pageSize") do
      val r = cases(query)
      assertEquals((r.status, r.str("error"), r.str("field")), (400, "invalid_request", field), query)
    val player = kit.request("GET", kit.operatorPath("/cases"), None, kit.asPlayer("p-1"))
    assertEquals(player.status, 403)

  test("status (G4, F): the cases' view with when it last applied a change"):
    val p = s"cs-${UUID.randomUUID().toString.take(8)}"
    kit.components.forEventSourcedEntity(EntityId(p)).call(ProfileEntity.openCase).invoke(OpenCase(p, "account_block", "", "op-1", kit.now())): Unit
    val view = kit.eventually("the view marked")(
      Option(kit.request("GET", kit.servicePath("/status"), None, kit.asOperator())).filter(_.status == 200)
        .flatMap(_.json.objects("views").find(_.text("name").contains("rg-cases"))).filter(_.text("asOf").nonEmpty))
    assertEquals(view.get("rebuilding").asBoolean, false)

package io.eitheror.casino.reporting.it

import com.thinkmorestupidless.ankka.testkit.LogCapturing

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * The admission table of contracts/routes.md (FR-023, FR-024): each route built so far, for each audience, admitted
 * or refused with the route class's own code; an operator of another brand refused; another brand's path served by
 * no route; the Reports tab's flag answering `404 not_found` for the reports and nothing else. Each story's routes
 * join `routes` as they are built.
 */
class AccessSuite extends munit.FunSuite with LogCapturing:
  override val munitTimeout = 300.seconds
  private lazy val kit = SharedKit.kit

  /** The audiences of the table, by name: an operator holding a role, another brand's, a player, nobody. */
  private def audiences: Vector[(String, Seq[(String, String)])] = Vector(
    "brand_admin"      -> kit.asStaff(Seq("brand_admin")),
    "resource_admin"   -> kit.asStaff(Seq("resource_admin")),
    "platform_admin"   -> kit.asStaff(Seq("platform_admin"), brandRole = false),
    "brand_compliance" -> kit.asStaff(Seq("brand_compliance")),
    "brand_finance"    -> kit.asStaff(Seq("brand_finance")),
    "markets"          -> kit.asStaff(Seq("markets")),
    "operator"         -> kit.asOperator,
    "another brand"    -> kit.fromInternet.:+("Authorization" -> "Bearer staff|op-9|brand_silvervibe,brand_admin"),
    "player"           -> kit.asPlayer(),
    "nobody"           -> kit.fromInternet
  )

  private val reportRoles = Set("brand_admin", "resource_admin", "platform_admin")
  private val everyOperator = Set("brand_admin", "resource_admin", "platform_admin", "brand_compliance", "brand_finance", "markets", "operator")

  /** Each GET route built so far and who it admits. */
  private val routes: Vector[(String, Set[String])] = Vector(
    "/reports/ggr" -> reportRoles,
    "/reports/players" -> reportRoles,
    "/reports/transactions" -> reportRoles,
    "/reports/transactions/export" -> reportRoles,
    "/reports/bonus" -> reportRoles,
    "/reports/psp" -> reportRoles,
    "/compliance/rg-cases" -> (reportRoles + "brand_compliance"),
    "/compliance/kyc-decisions" -> (reportRoles + "brand_compliance"),
    "/status"      -> everyOperator,
    "/settings"    -> Set("brand_admin", "platform_admin")
  ) ++ Vector("kpis", "trend", "kpi-history", "geo", "rankings", "activity", "bonus-summary", "payment-health")
    .map(r => s"/dashboard/$r" -> everyOperator)

  for (route, admitted) <- routes do
    test(s"GET $route admits ${admitted.toVector.sorted.mkString(", ")} and refuses everyone else with the class's code") {
      for (who, as) <- audiences do
        val r = kit.get(route, as)
        if admitted(who) then assertEquals(r.status, 200, s"$who: ${r.text}")
        else
          who match
            case "nobody"        => assertEquals(r.status, 401, s"$who: ${r.text}")
            case "player"        => assertEquals((r.status, r.text.contains("wrong_issuer")), (403, true), s"$who: ${r.text}")
            case "another brand" => assertEquals((r.status, r.text.contains("brand_not_in_roles")), (403, true), s"$who: ${r.text}")
            case _               => assertEquals((r.status, r.text.contains("forbidden")), (403, true), s"$who: ${r.text}")
    }

  /** Routes whose admitted answer is not a 200 here (a day not closed): admitted is anything but the refusals. */
  private val refusedOnly: Vector[(String, Set[String])] = Vector(
    "/day-end/2099-01-01" -> (reportRoles + "brand_finance"),
    "/day-end/2099-01-01/export" -> (reportRoles + "brand_finance"))

  for (route, admitted) <- refusedOnly do
    test(s"GET $route admits ${admitted.toVector.sorted.mkString(", ")} and refuses everyone else") {
      for (who, as) <- audiences do
        val r = kit.get(route, as)
        if admitted(who) then assertEquals(r.status, 409, s"$who: ${r.text}")
        else assert(Set(401, 403)(r.status), s"$who: ${r.status} ${r.text}")
    }

  test("PUT /settings admits brand_admin and platform_admin only") {
    for (who, as) <- audiences do
      val r = kit.request("PUT", kit.path("/settings"), Some("{}"), as)
      assertEquals(r.status == 200, Set("brand_admin", "platform_admin")(who), s"$who: ${r.status} ${r.text}")
  }

  test("another brand's path is served by no route of this brand's service") {
    val r = kit.request("GET", kit.path("/status", slug = "silvervibe"), headers = kit.asStaff(Seq("platform_admin"), brandRole = false))
    assertEquals(r.status, 404, r.text)
  }

  test("a path no route serves is judged by the endpoint's ACL: refused alike for another brand, not found for the brand") {
    assertEquals(kit.get("/no-such-route", kit.fromInternet.:+("Authorization" -> "Bearer staff|op-9|brand_silvervibe")).status, 403)
    assertEquals(kit.get("/no-such-route", kit.asOperator).status, 404)
  }

  test("with the Reports tab off a report is not found, and the status and settings still answer") {
    try
      kit.setReporting(_.copy(reportsTab = false))
      for report <- routes.map(_._1).filter(_.startsWith("/reports/")) do
        val r = kit.get(report, kit.asFinance)
        assertEquals((r.status, r.error), (404, "not_found"), s"$report: ${r.text}")
      assertEquals(kit.get("/dashboard/kpis", kit.asOperator).status, 200)
      assertEquals(kit.get("/status", kit.asOperator).status, 200)
      assertEquals(kit.get("/settings", kit.asFinance).json.path("reportsTab").asBoolean(true), false)
    finally kit.setReporting(_.copy(reportsTab = true))
    assertEquals(kit.get("/reports/ggr", kit.asFinance).status, 200)
  }

  test("the settings read in camelCase with every kind's lifetime; a PUT changes what it names and keeps the rest") {
    val before = kit.reporting
    try
      val r = kit.request("PUT", kit.path("/settings"), Some("""{"syncRows": 250, "lifetimeHours": {"dispute-report": 72}}"""), kit.asFinance)
      assertEquals(r.status, 200, r.text)
      val j = kit.get("/settings", kit.asFinance).json
      assertEquals((j.path("syncRows").asInt, j.path("syncDays").asInt, j.path("reportsTab").asBoolean, j.path("excludeTestAccounts").asBoolean),
        (250, before.syncDays, true, false))
      assertEquals(j.path("lifetimeHours").path("dispute-report").asInt, 72)
      assertEquals(j.path("lifetimeHours").path("report-export").asInt, 24)
      assert(j.has("defaultCurrency") && j.path("defaultCurrency").isNull, j.toString)
      assertEquals(kit.reporting.syncRows, 250)
    finally kit.setReporting(_ => before)
  }

  test("a setting out of its range is refused invalid_setting naming the field and the range, and nothing changes") {
    val before = kit.reporting
    for (body, field) <- Vector(
        """{"syncRows": 0}"""                              -> "syncRows",
        """{"syncDays": 366}"""                            -> "syncDays",
        """{"lifetimeHours": {"report-export": 2161}}"""   -> "lifetimeHours.report-export",
        """{"syncRows": "many"}"""                         -> "syncRows",
        """{"defaultCurrency": "euro"}"""                  -> "defaultCurrency")
    do
      val r = kit.request("PUT", kit.path("/settings"), Some(body), kit.asFinance)
      assertEquals((r.status, r.error, r.json.path("field").asText), (400, "invalid_setting", field), r.text)
    val range = kit.request("PUT", kit.path("/settings"), Some("""{"syncRows": 0}"""), kit.asFinance).json.path("message").asText
    assert(range.contains("1 to 10000") && range.contains("not 0"), range)
    assertEquals(kit.request("PUT", kit.path("/settings"), Some("[1]"), kit.asFinance).error, "invalid_body")
    assertEquals(kit.reporting, before)
  }

  test("the status names the generation and, per view, the first complete day") {
    // After a rebuild the new generation's coverage arrives as its consumers replay the topics.
    val j = kit.eventually("the seed day's coverage")(Some(kit.get("/status", kit.asOperator).json)
      .filter(_.path("views").get(0).path("completeFrom").asText == Kit.SeedDay.toString))
    assertEquals(j.path("generation").asInt, kit.settings.generation)
    val days = j.path("views").get(0)
    assertEquals((days.path("name").asText, days.path("completeFrom").asText, days.path("rebuilding").asBoolean),
      ("days", Kit.SeedDay.toString, false))
    assertEquals(j.path("views").elements.asScala.map(_.path("name").asText).toVector,
      Vector("days", "wallets", "players", "sessions", "payments", "withdrawals", "rounds", "player-days", "activity", "transactions",
        "grants", "compliance"))
  }

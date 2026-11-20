package io.eitheror.casino.reporting.features

import io.eitheror.casino.contract.access.OperatorPermissions
import io.eitheror.casino.reporting.it.{DayEnded, Disputed, Reply}

/**
 * features/reporting/operator-roles.feature (spec 030 T025, amendment E3): each operator route requires one permission,
 * decided from the brand's roles; the kit's roles are the role matrix. Admitted is a 2xx answer, refused a 403 naming
 * the permission. The day-end is read for a day closed once for the feature; the dispute report for a player of its own.
 */
class OperatorRolesFeatures extends ReportingFeatures("operator-roles.feature"):

  private val Roles = Vector(OperatorPermissions.Support, OperatorPermissions.Finance, OperatorPermissions.Compliance,
    OperatorPermissions.Admin)

  private var tokens  = Map.empty[String, Seq[(String, String)]]
  private var answers = Map.empty[String, Reply]
  private var all     = Vector.empty[(String, Reply)]
  private var token   = Seq.empty[(String, String)]

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    tokens = Map.empty; answers = Map.empty; all = Vector.empty; token = Nil

  /** A day of facts, closed, whose day-end an admitted caller reads. */
  private lazy val closedDay: DayEnded =
    val d = DayEnded(kit, kit.days()).usual().publish().settle()
    d.close(): Unit
    d

  /** A player with a session, whose dispute report an admitted caller generates. */
  private lazy val disputed: Disputed = Disputed(kit, kit.days()).usual().publish()

  /** The action, as the operator `as` asks for it; its permission is the one the route names. */
  private def ask(action: String, as: Seq[(String, String)]): Reply = action match
    case "read the dashboard"                                       => kit.get("/dashboard/kpis", as)
    case "read a revenue, players, transactions or rewards report" => kit.get("/reports/ggr", as)
    case "read a day-end report"                                    => kit.get(s"/day-end/${closedDay.day}", as)
    case "read a compliance export"                                 => kit.get("/compliance/rg-cases", as)
    case "ask for a player's dispute report"                        =>
      disputed.report(s"sessionId=${disputed.logins.head.id}", as)
    case "change the reporting settings"                            => kit.request("PUT", kit.path("/settings"), Some("{}"), as)
    case other                                                      => fail(s"no action '$other'")

  private val Actions = Vector("read the dashboard", "read a revenue, players, transactions or rewards report",
    "read a day-end report", "read a compliance export", "ask for a player's dispute report", "change the reporting settings")

  private def admitted(r: Reply): Boolean     = r.status / 100 == 2
  private def refusedNaming(r: Reply): Boolean = r.status == 403 && r.text.contains("permission is needed")

  private def expect(role: String, outcome: String): Unit =
    val r = answers(role)
    outcome match
      case "admitted" => assert(admitted(r), s"$role: ${r.status} ${r.text.take(300)}")
      case "refused"  => assert(refusedNaming(r), s"$role: ${r.status} ${r.text.take(300)}")
      case other      => fail(s"no outcome '$other'")

  Given("operators of the brand holding each of the roles {string}, {string}, {string} and {string}, each carrying its default permissions") {
    (a: String, b: String, c: String, d: String) =>
      assertEquals(Vector(a, b, c, d), Roles)
      for r <- Roles do assertEquals(kit.roles.permissionsOf(kit.brand, Set(kit.named(r))), Some(OperatorPermissions.DefaultRoles(r)))
      tokens = Roles.map(r => r -> kit.asStaff(Seq(r), s"op-$r")).toMap
  }

  When("each asks for the action {string} on the reports") { (action: String) =>
    answers = Roles.map(r => r -> ask(action, tokens(r))).toMap
  }

  Then("the support operator is {string}") { (o: String) => expect(OperatorPermissions.Support, o) }
  Then("the finance operator is {string}") { (o: String) => expect(OperatorPermissions.Finance, o) }
  Then("the compliance operator is {string}") { (o: String) => expect(OperatorPermissions.Compliance, o) }
  Then("the admin operator is {string}") { (o: String) => expect(OperatorPermissions.Admin, o) }

  Given("an operator of the brand holding only the payments role {string}") { (role: String) =>
    token = kit.asStaff(Seq(role), "op-payments")
  }
  Given("an operator holding the role {string}") { (role: String) =>
    token = kit.asStaff(Seq(role), "op-platform", brandRole = false)
  }

  When("they ask for any action on the reports") { () => all = Actions.map(a => a -> ask(a, token)) }

  Then("they are refused, naming the permission they lack") { () =>
    all.foreach((a, r) => assert(refusedNaming(r), s"$a: ${r.status} ${r.text.take(300)}"))
  }
  Then("they are admitted to every action on the reports") { () =>
    all.foreach((a, r) => assert(admitted(r), s"$a: ${r.status} ${r.text.take(300)}"))
  }

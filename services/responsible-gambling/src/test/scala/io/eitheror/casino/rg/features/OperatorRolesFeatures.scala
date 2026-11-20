package io.eitheror.casino.rg.features

import io.eitheror.casino.contract.access.OperatorPermissions
import io.eitheror.casino.rg.it.Reply

import java.time.Duration
import java.util.UUID

/**
 * features/responsible-gambling/operator-roles.feature (spec 030 T021, amendment G1): each operator route
 * requires one permission, decided from the brand's roles; the kit's roles are the role matrix. Every ask is
 * on a player of its own, so one role's change never stands in the way of the next role's.
 */
class OperatorRolesFeatures extends RgFeatures("operator-roles.feature"):

  private val Roles = Vector(OperatorPermissions.Support, OperatorPermissions.Finance, OperatorPermissions.Compliance, OperatorPermissions.Admin)

  private var tokens  = Map.empty[String, String]
  private var answers = Map.empty[String, Reply]
  private var all     = Vector.empty[(String, Reply)]
  private var roles   = ""

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    tokens = Map.empty; answers = Map.empty; all = Vector.empty; roles = ""

  private def fresh(): String = s"or-${UUID.randomUUID().toString.take(8)}"

  /** The action, as the operator holding `roles` asks for it; its permission is the one the route names. */
  private def ask(action: String, roles: String): Reply =
    val as = kit.asOperator(s"op-${roles.hashCode.abs}", roles)
    val p  = fresh()
    action match
      case "read a player's limits, breaks and cases" => kit.request("GET", kit.operatorPath(s"/$p"), None, as)
      case "set or remove a limit" =>
        kit.json("PUT", kit.operatorPath(s"/$p/limits/DEPOSIT_DAILY"), json("value" -> "10000", "currency" -> "EUR"), as)
      case "start or lift a break" =>
        kit.json("PUT", kit.operatorPath(s"/$p/cooling-off"), json("until" -> kit.now().plus(Duration.ofDays(2)).toString), as)
      case "record a self-exclusion" => kit.json("POST", kit.operatorPath(s"/$p/self-exclude"), json("period" -> "permanent"), as)
      case "open, note or close a case" =>
        kit.json("POST", kit.operatorPath(s"/$p/cases"), json("caseType" -> "account_block", "note" -> "kit"), as)
      case "change the brand's protection settings" =>
        kit.json("PUT", kit.operatorPath("/settings"), json("minSessionMinutes" -> 60, "maxBreakDays" -> 42), as)
      case other => fail(s"no action '$other'")

  private val Actions = Vector("read a player's limits, breaks and cases", "set or remove a limit", "start or lift a break",
    "record a self-exclusion", "open, note or close a case", "change the brand's protection settings")

  private def admitted(r: Reply): Boolean = r.status / 100 == 2
  private def refusedNaming(r: Reply): Boolean = r.status == 403 && r.text.contains("permission is needed")

  private def expect(role: String, outcome: String): Unit =
    val r = answers(role)
    outcome match
      case "admitted" => assert(admitted(r), s"$role: $r")
      case "refused"  => assert(refusedNaming(r), s"$role: $r")
      case other      => fail(s"no outcome '$other'")

  Given("operators of the brand holding each of the roles {string}, {string}, {string} and {string}, each carrying its default permissions") {
    (a: String, b: String, c: String, d: String) =>
      assertEquals(Vector(a, b, c, d), Roles)
      for r <- Roles do assertEquals(kit.roles.permissionsOf(kit.slug, Set(kit.in(r))), Some(OperatorPermissions.DefaultRoles(r)))
      tokens = Roles.map(r => r -> s"brand_${kit.slug},${kit.in(r)}").toMap
  }

  When("each asks for the action {string} on a player's protections") { (action: String) =>
    answers = Roles.map(r => r -> ask(action, tokens(r))).toMap
  }

  Then("the support operator is {string}") { (o: String) => expect(OperatorPermissions.Support, o) }
  Then("the finance operator is {string}") { (o: String) => expect(OperatorPermissions.Finance, o) }
  Then("the compliance operator is {string}") { (o: String) => expect(OperatorPermissions.Compliance, o) }
  Then("the admin operator is {string}") { (o: String) => expect(OperatorPermissions.Admin, o) }

  Given("an operator of the brand holding only the payments role {string}") { (role: String) => roles = s"brand_${kit.slug},$role" }
  Given("an operator holding the role {string}") { (role: String) => roles = role }

  When("they ask for any action on a player's protections") { () => all = Actions.map(a => a -> ask(a, roles)) }

  Then("they are refused, naming the permission they lack") { () =>
    all.foreach((a, r) => assert(refusedNaming(r), s"$a: $r"))
  }
  Then("they are admitted to every action on a player's protections") { () =>
    all.foreach((a, r) => assert(admitted(r), s"$a: $r"))
  }

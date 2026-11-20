package io.eitheror.casino.support.features

import io.eitheror.casino.contract.access.OperatorPermissions
import io.eitheror.casino.support.it.Reply

/**
 * features/support-agent/operator-roles.feature (spec 030 T024, amendment E1): each operator route requires one
 * permission, decided from the brand's roles; the kit's roles are the role matrix. The configuration's change is a
 * `PUT` that changes nothing, so no scenario leaves the brand's configuration other than it found it.
 */
class OperatorRolesFeatures extends SupportFeatures("operator-roles.feature"):

  private val Roles = Vector(OperatorPermissions.Support, OperatorPermissions.Finance, OperatorPermissions.Compliance, OperatorPermissions.Admin)

  private var tokens  = Map.empty[String, String]
  private var answers = Map.empty[String, Reply]
  private var all     = Vector.empty[(String, Reply)]
  private var token   = ""

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    kit.configure(): Unit
    tokens = Map.empty; answers = Map.empty; all = Vector.empty; token = ""

  private def config(rest: String) = s"/v1/casino/agent-config/${kit.slug}$rest"

  private def as(token: String): Seq[(String, String)] = Seq(kit.asOperator().head, "Authorization" -> s"Bearer $token")

  /** The action, as the operator holding `token` asks for it; its permission is the one the route names. */
  private def ask(action: String, token: String): Reply = action match
    case "read a player's conversations" => kit.request("GET", config(s"/conversations?player=$player"), headers = as(token))
    case "change the support agent's configuration" =>
      kit.request("PUT", config("/"), Some("""{"enabled": true}"""), as(token))
    case other => fail(s"no action '$other'")

  private val Actions = Vector("read a player's conversations", "change the support agent's configuration")

  private def admitted(r: Reply): Boolean      = r.status / 100 == 2
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
      for r <- Roles do assertEquals(kit.roles.permissionsOf(kit.slug, Set(kit.named(r))), Some(OperatorPermissions.DefaultRoles(r)))
      tokens = Roles.map(r => r -> s"staff|op-$r|brand_${kit.slug},${kit.named(r)}").toMap
  }

  When("each asks for the action {string} on the support agent") { (action: String) =>
    answers = Roles.map(r => r -> ask(action, tokens(r))).toMap
  }

  Then("the support operator is {string}") { (o: String) => expect(OperatorPermissions.Support, o) }
  Then("the finance operator is {string}") { (o: String) => expect(OperatorPermissions.Finance, o) }
  Then("the compliance operator is {string}") { (o: String) => expect(OperatorPermissions.Compliance, o) }
  Then("the admin operator is {string}") { (o: String) => expect(OperatorPermissions.Admin, o) }

  Given("an operator of the brand holding only the payments role {string}") { (role: String) =>
    token = s"staff|op-payments|brand_${kit.slug},$role"
  }
  Given("an operator holding the role {string}") { (role: String) => token = s"staff|op-platform|$role" }

  When("they ask for any action on the support agent") { () => all = Actions.map(a => a -> ask(a, token)) }

  Then("they are refused, naming the permission they lack") { () =>
    all.foreach((a, r) => assert(refusedNaming(r), s"$a: $r"))
  }
  Then("they are admitted to every action on the support agent") { () =>
    all.foreach((a, r) => assert(admitted(r), s"$a: $r"))
  }

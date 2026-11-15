package io.eitheror.casino.payouts.features

import io.eitheror.casino.contract.access.OperatorPermissions
import io.eitheror.casino.payouts.it.Reply
import io.eitheror.casino.payouts.terminals.Terminal

/**
 * features/payouts/operator-roles.feature (spec 030 T018, amendment Y1): each operator route requires one
 * permission, decided from the brand's roles; the kit's roles are the role matrix. Each admitted ask is made
 * on a request of its own, made fresh, so a decision or a settlement answers 2xx.
 */
class OperatorRolesFeatures extends PayoutsFeatures("operator-roles.feature"):

  private val Roles = Vector(OperatorPermissions.Support, OperatorPermissions.Finance, OperatorPermissions.Compliance, OperatorPermissions.Admin)

  private val Read     = "read the queue and a withdrawal request"
  private val Decide   = "approve or reject a withdrawal request"
  private val Settle   = "confirm a withdrawal payment or mark it failed"
  private val Settings = "change the brand's withdrawal settings"
  private val Actions  = Vector(Read, Decide, Settle, Settings)

  private val manualTerminal: Terminal = terminal("t-manual", "paymentiq", destination = true)

  private var roles   = Map.empty[String, String]
  private var answers = Map.empty[String, Vector[Reply]]
  private var all     = Vector.empty[(String, Vector[Reply])]
  private var holding = ""

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    roles = Map.empty; answers = Map.empty; all = Vector.empty; holding = ""

  /** A request of a player of its own, pending review; or approved to a terminal with no automated payment. */
  private def fresh(manual: Boolean = false): String =
    player = newPlayer()
    if manual then
      val id = pending(to = manualTerminal)
      val a  = approve(id)
      assertEquals((a.status, a.str("status")), (200, "AWAITING_MANUAL_PAYOUT"), a.text)
      id
    else pending()

  private def config(body: String, as: String): Reply =
    kit.request("PUT", s"/v1/casino/brands/${kit.slug}/withdrawal-config", Some(body), kit.asOperator("op-roles", as))

  /** The action, as the operator holding `as` asks for it; each answer is a route's, whose permission it names. */
  private def ask(action: String, as: String): Vector[Reply] = action match
    case Read =>
      val id = fresh()
      Vector(operator("GET", "/", roles = as), operator("GET", s"/$id", roles = as),
        kit.request("GET", kit.playerPath(s"/$player/withdrawal-history"), None, kit.asOperator("op-roles", as)))
    case Decide =>
      Vector(approve(fresh(), roles = as),
        operator("POST", s"/${fresh()}/reject", Some("""{"reason":"kit"}"""), roles = as))
    case Settle =>
      Vector(operator("POST", s"/${fresh(manual = true)}/confirm-payout", Some("""{"note":"paid"}"""), roles = as),
        operator("POST", s"/${fresh(manual = true)}/mark-payout-failed", Some("""{"reason":"returned"}"""), roles = as))
    case Settings => Vector(config("""{"limits":{}}""", as))
    case other    => fail(s"no action '$other'")

  private def admitted(rs: Vector[Reply]): Boolean      = rs.forall(_.status / 100 == 2)
  private def refusedNaming(rs: Vector[Reply]): Boolean = rs.forall(r => r.status == 403 && r.text.contains("permission is needed"))

  private def expect(role: String, outcome: String): Unit =
    val rs = answers(role)
    outcome match
      case "admitted" => assert(admitted(rs), s"$role: $rs")
      case "refused"  => assert(refusedNaming(rs), s"$role: $rs")
      case other      => fail(s"no outcome '$other'")

  Given("operators of the brand holding each of the roles {string}, {string}, {string} and {string}, each carrying its default permissions") {
    (a: String, b: String, c: String, d: String) =>
      assertEquals(Vector(a, b, c, d), Roles)
      for r <- Roles do assertEquals(kit.roles.permissionsOf(Set(r)), Some(OperatorPermissions.DefaultRoles(r)))
      roles = Roles.map(r => r -> s"brand_${kit.slug},$r").toMap
  }

  When("each asks for the action {string} on a withdrawal request") { (action: String) =>
    answers = Roles.map(r => r -> ask(action, roles(r))).toMap
  }

  Then("the support operator is {string}") { (o: String) => expect(OperatorPermissions.Support, o) }
  Then("the finance operator is {string}") { (o: String) => expect(OperatorPermissions.Finance, o) }
  Then("the compliance operator is {string}") { (o: String) => expect(OperatorPermissions.Compliance, o) }
  Then("the admin operator is {string}") { (o: String) => expect(OperatorPermissions.Admin, o) }

  Given("an operator of the brand holding only the payments role {string}") { (role: String) =>
    holding = s"brand_${kit.slug},$role"
  }
  Given("an operator holding the role {string}") { (role: String) => holding = role }

  When("they ask for any action on a withdrawal request") { () => all = Actions.map(a => a -> ask(a, holding)) }

  Then("they are refused, naming the permission they lack") { () =>
    all.foreach((a, rs) => assert(refusedNaming(rs), s"$a: $rs"))
  }
  Then("they are admitted to every action on a withdrawal request") { () =>
    all.foreach((a, rs) => assert(admitted(rs), s"$a: $rs"))
  }

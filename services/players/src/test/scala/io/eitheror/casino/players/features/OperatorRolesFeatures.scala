package io.eitheror.casino.players.features

import io.eitheror.casino.contract.access.OperatorPermissions
import io.eitheror.casino.players.{Json, PlayersRuntime}
import io.eitheror.casino.players.it.Reply

/**
 * features/players/operor-roles.feature (spec 030 T019, amendment P1): each operator route requires one
 * permission, decided from the brand's roles as `players` keeps them (the role matrix, as seeded).
 * Admitted means the route's ACL let the request through, whatever the player's state then answered;
 * refused means 403 naming the permission.
 */
class OperatorRolesFeatures extends PlayersFeatures("operator-roles.feature"):
  protected def pending = Map.empty

  private val Roles = Vector(OperatorPermissions.Support, OperatorPermissions.Finance, OperatorPermissions.Compliance, OperatorPermissions.Admin)

  private var answers = Map.empty[String, Reply]
  private var all     = Vector.empty[(String, Reply)]
  private var roles   = ""

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    answers = Map.empty; all = Vector.empty; roles = ""

  private val Actions = Vector(
    "read a player's profile, sessions and activity", "add or pin a note", "send an inbox item",
    "suspend, reinstate, close or reopen an account", "change a profile or a date of birth", "force a session to end",
    "clear a no-deposit block", "review or dismiss a fraud case", "request an erasure", "change a VIP tier or a manager tag",
    "change the brand's sites, countries or currencies")

  private def ask(action: String, roles: String, subject: String): Reply =
    val id = kit.activePlayer()._2
    val as = kit.asOperator(roles, subject)
    def go(method: String, rest: String, body: (String, Any)*) =
      kit.request(method, kit.operatorPath(rest), Option.when(method != "GET")(Json.render(Json.obj(body*))), as)
    action match
      case "read a player's profile, sessions and activity"  => go("GET", s"/players/$id/profile")
      case "add or pin a note"                               => go("POST", s"/players/$id/notes", "text" -> "kit", "content" -> "kit")
      case "send an inbox item"                              => go("POST", s"/players/$id/messages", "title" -> "kit", "body" -> "kit")
      case "suspend, reinstate, close or reopen an account"  => go("PATCH", s"/players/$id/suspend", "reason" -> "kit")
      case "change a profile or a date of birth"             => go("PATCH", s"/players/$id/date-of-birth", "dateOfBirth" -> "1990-01-02")
      case "force a session to end"                          => go("POST", s"/players/$id/sessions/force-end-all")
      case "clear a no-deposit block"                        => go("POST", s"/players/$id/clear-no-deposit-block")
      case "review or dismiss a fraud case"                  => go("GET", "/fraud-cases")
      case "request an erasure"                              => go("GET", s"/players/$id/erasure")
      case "change a VIP tier or a manager tag"              => go("PUT", s"/players/$id/vip-tier", "tier" -> "gold")
      case "change the brand's sites, countries or currencies" => go("GET", "/brand-config")
      case other                                             => fail(s"no action '$other'")

  private def admitted(r: Reply): Boolean  = !Set(401, 403, 503)(r.status)
  private def refusedNaming(r: Reply): Boolean = r.status == 403 && r.text.contains("permission is needed")

  private def expect(role: String, outcome: String): Unit =
    val r = answers(role)
    outcome match
      case "admitted" => assert(admitted(r), s"$role: ${r.status} ${r.text}")
      case "refused"  => assert(refusedNaming(r), s"$role: ${r.status} ${r.text}")
      case other      => fail(s"no outcome '$other'")

  Given("operators of the brand holding each of the roles {string}, {string}, {string} and {string}, each carrying its default permissions") {
    (a: String, b: String, c: String, d: String) =>
      assertEquals(Vector(a, b, c, d), Roles)
      for r <- Roles do assertEquals(PlayersRuntime.bags.permissionsOf(Set(r)), Some(OperatorPermissions.DefaultRoles(r)), r)
  }

  When("each asks for the action {string} on a player") { (action: String) =>
    answers = Roles.map(r => r -> ask(action, s"brand_${kit.slug},$r", s"op-$r")).toMap
  }

  Then("the support operator is {string}") { (o: String) => expect(OperatorPermissions.Support, o) }
  Then("the finance operator is {string}") { (o: String) => expect(OperatorPermissions.Finance, o) }
  Then("the compliance operator is {string}") { (o: String) => expect(OperatorPermissions.Compliance, o) }
  Then("the admin operator is {string}") { (o: String) => expect(OperatorPermissions.Admin, o) }

  Given("an operator of the brand holding only the payments role {string}") { (role: String) => roles = s"brand_${kit.slug},$role" }
  Given("an operator holding the role {string}") { (role: String) => roles = role }

  When("they ask for any action on a player") { () => all = Actions.map(a => a -> ask(a, roles, "op-any")) }

  Then("they are refused, naming the permission they lack") { () =>
    all.foreach((a, r) => assert(refusedNaming(r), s"$a: ${r.status} ${r.text}"))
  }
  Then("they are admitted to every action on a player") { () =>
    all.foreach((a, r) => assert(admitted(r), s"$a: ${r.status} ${r.text}"))
  }

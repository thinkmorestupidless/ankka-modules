package io.eitheror.casino.gameprovider.features

import io.eitheror.casino.contract.access.{BrandRoles, OperatorPermissions}
import io.eitheror.casino.gameprovider.Json
import io.eitheror.casino.gameprovider.Json.*
import io.eitheror.casino.gameprovider.it.Reply

/**
 * features/game-provider/operator-roles.feature (spec 030 T023, amendment GP1): each operator route requires
 * one permission, decided from the brand's roles; the kit's roles are the role matrix.
 */
class OperatorRolesFeatures extends ProviderFeatures("operator-roles.feature"):

  private val Roles = Vector(OperatorPermissions.Support, OperatorPermissions.Finance, OperatorPermissions.Compliance, OperatorPermissions.Admin)

  private var tokens  = Map.empty[String, Seq[(String, String)]]
  private var answers = Map.empty[String, Reply]
  private var all     = Vector.empty[(String, Reply)]
  private var caller  = Seq.empty[(String, String)]
  private var asks    = 0

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    tokens = Map.empty; answers = Map.empty; all = Vector.empty; caller = Nil; asks = 0
    token = kit.sessionTicket(player)
    kit.wallet.set(player, currency, 100000)

  private def bearer(subject: String, roles: String) = Seq("Authorization" -> s"Bearer staff|$subject|$roles")
  private def backoffice(rest: String) = s"/v1/casino/backoffice/${kit.brand}/game-provider$rest"

  /** A credit the wallet did not acknowledge, fresh for each ask, listed once the view has it. */
  private def unacknowledged(): String =
    asks += 1
    val tx = s"or-$asks"
    assertEquals(debit(s"$tx-stake", "1.00", s"$tx-round").word, "ok", last.text)
    kit.wallet.down = true
    assertEquals(credit(tx, "2.00", Some(s"$tx-round")).word, "error", last.text)
    kit.wallet.down = false
    kit.eventually(s"${id(tx)} listed") {
      kit.request("GET", backoffice("/unacknowledged"), None, kit.asOperator()).json.objects("entries")
        .find(_.text("transactionId").contains(id(tx)))
    }
    id(tx)

  /** The action, as the operator `as` asks for it; its permission is the one the route names. */
  private def ask(action: String, as: Seq[(String, String)]): Reply = action match
    case "read a player's rounds" =>
      kit.request("GET", backoffice(s"/rounds?playerId=$player"), None, as)
    case "replay or resolve an unacknowledged call" =>
      val tx      = unacknowledged()
      val resolve = Some(Json.render(Json.obj("reason" -> "settled by hand")))
      val r       = kit.request("POST", backoffice(s"/unacknowledged/$tx/resolve"), resolve, as)
      // a refused caller leaves the call open: the admin clears it, so the kit's gauge is not left raised
      if r.status / 100 != 2 then assertEquals(kit.request("POST", backoffice(s"/unacknowledged/$tx/resolve"), resolve, kit.asOperator()).status, 204)
      r
    case "change a provider's configuration or the game catalogue" =>
      kit.configure("st8", kit.st8, keyDocument = false, as = as)
    case other => fail(s"no action '$other'")

  private val Actions = Vector("read a player's rounds", "replay or resolve an unacknowledged call",
    "change a provider's configuration or the game catalogue")

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
      for r <- Roles do assertEquals(kit.roles.permissionsOf(kit.brand, Set(BrandRoles.nameIn(kit.brand, r))), Some(OperatorPermissions.DefaultRoles(r)))
      tokens = Roles.map(r => r -> bearer(s"op-$r", s"brand_${kit.brand},${BrandRoles.nameIn(kit.brand, r)}")).toMap
  }

  When("each asks for the action {string} on the game provider part") { (action: String) =>
    answers = Roles.map(r => r -> ask(action, tokens(r))).toMap
  }

  Then("the support operator is {string}") { (o: String) => expect(OperatorPermissions.Support, o) }
  Then("the finance operator is {string}") { (o: String) => expect(OperatorPermissions.Finance, o) }
  Then("the compliance operator is {string}") { (o: String) => expect(OperatorPermissions.Compliance, o) }
  Then("the admin operator is {string}") { (o: String) => expect(OperatorPermissions.Admin, o) }

  Given("an operator of the brand holding only the payments role {string}") { (role: String) =>
    caller = bearer("op-payments", s"brand_${kit.brand},$role")
  }
  Given("an operator holding the role {string}") { (role: String) => caller = bearer("op-platform", role) }

  When("they ask for any action on the game provider part") { () => all = Actions.map(a => a -> ask(a, caller)) }

  Then("they are refused, naming the permission they lack") { () =>
    all.foreach((a, r) => assert(refusedNaming(r), s"$a: $r"))
  }
  Then("they are admitted to every action on the game provider part") { () =>
    all.foreach((a, r) => assert(admitted(r), s"$a: $r"))
  }

package io.eitheror.casino.wallet.features

import io.eitheror.casino.contract.access.OperatorPermissions
import io.eitheror.casino.wallet.it.TestTokens

/**
 * features/wallet/operator-roles.feature (spec 030 T017, amendment W1): each operator route requires one
 * permission, decided from the brand's roles; the harness's roles are the role matrix.
 */
class OperatorRolesFeatures extends RouteFeatures("operator-roles.feature"):

  private val Roles = Vector(OperatorPermissions.Support, OperatorPermissions.Finance, OperatorPermissions.Compliance, OperatorPermissions.Admin)

  private var tokens  = Map.empty[String, String]
  private var answers = Map.empty[String, (Int, String)]
  private var all     = Vector.empty[(String, (Int, String))]
  private var token   = ""

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    tokens = Map.empty; answers = Map.empty; all = Vector.empty; token = ""
    player = s"or${java.util.UUID.randomUUID().toString.take(8)}"

  private def backoffice(rest: String) = s"/v1/casino/backoffice/$brand$rest"

  /** The action, as the operator holding `token` asks for it; its permission is the one the route names. */
  private def ask(action: String, token: String): (Int, String) = action match
    case "read a wallet's balances and transactions" => sendWithToken("GET", backoffice(s"/wallets/$player/EUR/balance"), token)
    case "make an adjustment" =>
      sendWithToken("POST", backoffice(s"/wallets/$player/EUR/adjustments"), token,
        Some(s"""{"key":"${nextKey("adj")}","walletType":"real","amount":"1.00","reason":"kit","category":"goodwill"}"""))
    case "override a player's turnover" =>
      sendWithToken("PUT", backoffice(s"/wallets/$player/EUR/turnover-override"), token, Some("""{"multiplier":1}"""))
    case "change the brand's wagering settings" =>
      sendWithToken("PUT", backoffice("/wallet-config/turnover"), token, Some("""{"multiplier":0}"""))
    case other => fail(s"no action '$other'")

  private val Actions = Vector("read a wallet's balances and transactions", "make an adjustment", "override a player's turnover",
    "change the brand's wagering settings")

  private def admitted(r: (Int, String)): Boolean = r._1 / 100 == 2
  private def refusedNaming(r: (Int, String)): Boolean = r._1 == 403 && r._2.contains("permission is needed")

  private def expect(role: String, outcome: String): Unit =
    val r = answers(role)
    outcome match
      case "admitted" => assert(admitted(r), s"$role: $r")
      case "refused"  => assert(refusedNaming(r), s"$role: $r")
      case other      => fail(s"no outcome '$other'")

  Given("operators of the brand holding each of the roles {string}, {string}, {string} and {string}, each carrying its default permissions") {
    (a: String, b: String, c: String, d: String) =>
      assertEquals(Vector(a, b, c, d), Roles)
      for r <- Roles do assertEquals(roles.permissionsOf(Set(r)), Some(OperatorPermissions.DefaultRoles(r)))
      tokens = Roles.map(r => r -> TestTokens.operator(s"op-$r", s"brand_$brand,$r")).toMap
  }

  When("each asks for the action {string} on a wallet") { (action: String) =>
    answers = Roles.map(r => r -> ask(action, tokens(r))).toMap
  }

  Then("the support operator is {string}") { (o: String) => expect(OperatorPermissions.Support, o) }
  Then("the finance operator is {string}") { (o: String) => expect(OperatorPermissions.Finance, o) }
  Then("the compliance operator is {string}") { (o: String) => expect(OperatorPermissions.Compliance, o) }
  Then("the admin operator is {string}") { (o: String) => expect(OperatorPermissions.Admin, o) }

  Given("an operator of the brand holding only the payments role {string}") { (role: String) =>
    token = TestTokens.operator("op-payments", s"brand_$brand,$role")
  }
  Given("an operator holding the role {string}") { (role: String) => token = TestTokens.operator("op-platform", role) }

  When("they ask for any action on a wallet") { () => all = Actions.map(a => a -> ask(a, token)) }

  Then("they are refused, naming the permission they lack") { () =>
    all.foreach((a, r) => assert(refusedNaming(r), s"$a: $r"))
  }
  Then("they are admitted to every action on a wallet") { () =>
    all.foreach((a, r) => assert(admitted(r), s"$a: $r"))
  }

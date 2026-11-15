package io.eitheror.casino.rewards.features

import io.eitheror.casino.contract.access.OperatorPermissions
import io.eitheror.casino.rewards.it.{ClaimPaths, Reply}
import io.eitheror.casino.rewards.reads.RewardsView
import io.eitheror.casino.rewards.reward.RewardStatus

/**
 * features/rewards/operator-roles.feature (spec 030 T022, amendment RW1): each operator route requires one
 * permission, decided from the brand's roles; the kit's roles are the role matrix. Every ask has a fixture of its own,
 * made as `brand_admin`: a reward granted by hand to a new player, active, for the reads and the revoke; a new player
 * for the award; an offer created is deactivated again so no later scenario sees it.
 */
class OperatorRolesFeatures extends RewardsFeatures("operator-roles.feature"):
  private def paths = ClaimPaths(kit)

  private val Roles = Vector(OperatorPermissions.Support, OperatorPermissions.Finance, OperatorPermissions.Compliance, OperatorPermissions.Admin)

  private var tokens  = Map.empty[String, Seq[(String, String)]]
  private var answers = Map.empty[String, Reply]
  private var all     = Vector.empty[(String, Reply)]
  private var caller  = Seq.empty[(String, String)]

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    tokens = Map.empty; answers = Map.empty; all = Vector.empty; caller = Nil

  private def admin = kit.asOperator("brand_admin", "op-fixture")

  /** A reward granted by hand to a new player, once it is active: its id. */
  private def activeReward(): String =
    val p = paths.newPlayer("or-p")
    val r = kit.json("POST", kit.grantsPath(s"/$p"), json("bonusAmount" -> 1000L, "wager" -> 5, "currency" -> "EUR"), admin)
    assertEquals(r.status, 200, r.text)
    eventually("the reward active")(RewardsView.byPlayer(kit.views.forView(RewardsView), p).find(_.status == RewardStatus.Active.wire))
      .rewardId

  /** Both answers of a two-request action: the first that is not 2xx, else the last. */
  private def both(a: Reply, b: => Reply): Reply = if a.status / 100 != 2 then a else b

  /** The action, as the operator `as` asks for it; its permission is the one the route names. */
  private def ask(action: String, as: Seq[(String, String)]): Reply = action match
    case "read the brand's rewards and one reward" =>
      val id = activeReward()
      both(kit.request("GET", kit.grantsPath(""), None, as), kit.request("GET", kit.grantsPath(s"/$id"), None, as))
    case "give a reward from an offer or by hand" =>
      kit.json("POST", kit.grantsPath(s"/${paths.newPlayer("or-g")}"), json("bonusAmount" -> 1000L, "wager" -> 5, "currency" -> "EUR"), as)
    case "forfeit or reclaim a reward" =>
      kit.json("POST", kit.grantsPath(s"/${activeReward()}/revoke"), json("mode" -> "forfeit"), as)
    case "create or change an offer" =>
      val r = kit.json("POST", kit.offersPath(""), json("name" -> paths.unique("Roles"), "type" -> "DEPOSIT_MATCH",
        "bonusPercentage" -> 10000L, "maxBonusValue" -> 50000L, "wageringMultiplier" -> 30L, "currency" -> "EUR"), as)
      if r.status == 201 then
        assertEquals(kit.request("DELETE", kit.offersPath(s"/${r.str("id")}"), None, admin).status, 200)
      r
    case other => fail(s"no action '$other'")

  private val Actions = Vector("read the brand's rewards and one reward", "give a reward from an offer or by hand",
    "forfeit or reclaim a reward", "create or change an offer")

  private def admitted(r: Reply): Boolean      = r.status / 100 == 2
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
      for r <- Roles do assertEquals(kit.roles.permissionsOf(Set(r)), Some(OperatorPermissions.DefaultRoles(r)))
      tokens = Roles.map(r => r -> kit.asOperator(r, s"op-$r")).toMap
  }

  When("each asks for the action {string} on a reward") { (action: String) =>
    answers = Roles.map(r => r -> ask(action, tokens(r))).toMap
  }

  Then("the support operator is {string}") { (o: String) => expect(OperatorPermissions.Support, o) }
  Then("the finance operator is {string}") { (o: String) => expect(OperatorPermissions.Finance, o) }
  Then("the compliance operator is {string}") { (o: String) => expect(OperatorPermissions.Compliance, o) }
  Then("the admin operator is {string}") { (o: String) => expect(OperatorPermissions.Admin, o) }

  Given("an operator of the brand holding only the payments role {string}") { (role: String) =>
    caller = kit.asOperator(role, "op-payments")
  }
  Given("an operator holding the role {string}") { (role: String) => caller = kit.asStaff(role, "op-platform") }

  When("they ask for any action on a reward") { () => all = Actions.map(a => a -> ask(a, caller)) }

  Then("they are refused, naming the permission they lack") { () =>
    all.foreach((a, r) => assert(refusedNaming(r), s"$a: ${r.status} ${r.text}"))
  }
  Then("they are admitted to every action on a reward") { () =>
    all.foreach((a, r) => assert(admitted(r), s"$a: ${r.status} ${r.text}"))
  }

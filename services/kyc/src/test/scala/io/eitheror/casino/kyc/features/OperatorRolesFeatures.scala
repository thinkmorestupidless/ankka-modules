package io.eitheror.casino.kyc.features

import io.eitheror.casino.contract.access.OperatorPermissions
import io.eitheror.casino.kyc.it.Reply

/**
 * features/kyc/operator-roles.feature (spec 030 T020, amendment K1): each operator route requires one
 * permission, decided from the brand's roles; the kit's roles are the role matrix. Each ask is made on a
 * player of its own with a pending document, so an admitted caller's ask succeeds.
 */
class OperatorRolesFeatures extends KycFeatures("operator-roles.feature"):

  private val Roles = Vector(OperatorPermissions.Support, OperatorPermissions.Finance, OperatorPermissions.Compliance, OperatorPermissions.Admin)

  private var tokens  = Map.empty[String, Seq[(String, String)]]
  private var answers = Map.empty[String, Reply]
  private var all     = Vector.empty[(String, Reply)]
  private var as      = Seq.empty[(String, String)]

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    tokens = Map.empty; answers = Map.empty; all = Vector.empty; as = Seq.empty

  /** The action, as the operator `as` asks for it; its permission is the one the route names. */
  private def ask(action: String, as: Seq[(String, String)]): Reply =
    def fresh(): (String, String) =
      val p = newPlayer()
      (p, submit("identity_front", as = p))
    action match
      case "read a player's identity status" =>
        val (p, _) = fresh()
        kit.request("GET", kit.operatorPath(s"/$p"), None, as)
      case "read the queue" => kit.request("GET", kit.operatorPath("/queue?filter=awaiting"), None, as)
      case "view a document" =>
        val (p, d) = fresh()
        kit.request("GET", kit.operatorPath(s"/$p/views/$d"), None, as)
      case "approve or reject a document" =>
        val (p, d) = fresh()
        kit.json("POST", kit.operatorPath(s"/$p/reviews/$d"), json("outcome" -> "approved"), as)
      case "record the final decision" =>
        val (p, _) = fresh()
        kit.json("POST", kit.operatorPath(s"/$p/override"), json("status" -> "verified", "reason" -> "kit"), as)
      case "change the brand's KYC policy" =>
        kit.json("PUT", kit.operatorPath("/policy"), json("depositGate" -> "KYC_APPROVED"), as)
      case other => fail(s"no action '$other'")

  private val Actions = Vector("read a player's identity status", "read the queue", "view a document", "approve or reject a document",
    "record the final decision", "change the brand's KYC policy")

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
      tokens = Roles.map(r => r -> kit.asOperator(s"op-$r", s"brand_${kit.slug},${kit.named(r)}")).toMap
  }

  When("each asks for the action {string} on a player's identity") { (action: String) =>
    answers = Roles.map(r => r -> ask(action, tokens(r))).toMap
  }

  Then("the support operator is {string}") { (o: String) => expect(OperatorPermissions.Support, o) }
  Then("the finance operator is {string}") { (o: String) => expect(OperatorPermissions.Finance, o) }
  Then("the compliance operator is {string}") { (o: String) => expect(OperatorPermissions.Compliance, o) }
  Then("the admin operator is {string}") { (o: String) => expect(OperatorPermissions.Admin, o) }

  Given("an operator of the brand holding only the payments role {string}") { (role: String) =>
    as = kit.asOperator("op-payments", s"brand_${kit.slug},$role")
  }
  Given("an operator holding the role {string}") { (role: String) => as = kit.asOperator("op-platform", role) }

  When("they ask for any action on a player's identity") { () => all = Actions.map(a => a -> ask(a, as)) }

  Then("they are refused, naming the permission they lack") { () =>
    all.foreach((a, r) => assert(refusedNaming(r), s"$a: $r"))
  }
  Then("they are admitted to every action on a player's identity") { () =>
    all.foreach((a, r) => assert(admitted(r), s"$a: $r"))
  }

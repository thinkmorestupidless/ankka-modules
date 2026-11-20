package io.eitheror.casino.engagement.features

import io.eitheror.casino.contract.access.OperatorPermissions
import io.eitheror.casino.engagement.it.{Facts, FakeCrm, Reply}
import io.eitheror.casino.engagement.send.Outcome

import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8

/**
 * features/engagement/operator-roles.feature (spec 030 T024, amendment E1): each operator route requires one
 * permission, decided from the brand's roles; the kit's roles are the role matrix. A parked send is a deposit's send
 * with Customer.io failing all four attempts, one per ask; one still parked after the asks is discarded as the
 * brand's admin, so no other suite finds it.
 */
class OperatorRolesFeatures extends EngagementFeatures("operator-roles.feature"):

  private val Roles = Vector(OperatorPermissions.Support, OperatorPermissions.Finance, OperatorPermissions.Compliance, OperatorPermissions.Admin)

  private lazy val facts = Facts(kit)

  private var tokens  = Map.empty[String, String]
  private var answers = Map.empty[String, Reply]
  private var all     = Vector.empty[(String, Reply)]
  private var token   = ""
  private var parked  = Vector.empty[String]

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    tokens = Map.empty; answers = Map.empty; all = Vector.empty; token = ""; parked = Vector.empty

  override def afterEach(context: AfterEach): Unit =
    for key <- parked if facts.state(key).outcome == Outcome.Parked do
      discard(key, s"staff|op-cleanup|brand_${kit.slug},${kit.named(OperatorPermissions.Admin)}"): Unit
    super.afterEach(context)

  private def backoffice(rest: String) = s"/v1/casino/backoffice/${kit.slug}$rest"

  /** A deposit's send, parked after Customer.io failed its four attempts. */
  private def parkedSend(): String =
    val p = facts.player("roles")
    kit.crm.fail(503, 4)
    val d = facts.deposit(p)
    facts.publish(d)
    val key = facts.key("deposit_confirmed", d)
    assertEquals(facts.settled(key).outcome, Outcome.Parked)
    kit.crm.reset()
    kit.crm.expect(FakeCrm.SiteId, FakeCrm.TrackKey, FakeCrm.AppKey)
    parked :+= key
    key

  private def discard(key: String, token: String): Reply =
    kit.request("POST", backoffice(s"/parked-sends/${URLEncoder.encode(key, UTF_8)}/discard"), Some("""{"reason":"kit"}"""),
      kit.bearer(token))

  /** The action, as the operator holding `token` asks for it; its permission is the one the route names. */
  private def ask(action: String, token: String): Reply = action match
    case "read who is online"             => kit.request("GET", backoffice("/presence"), None, kit.bearer(token))
    case "retry or discard a parked send" => discard(parkedSend(), token)
    case "change the CRM configuration"   => kit.request("PUT", s"/v1/casino/crm-config/${kit.slug}", Some("{}"), kit.bearer(token))
    case other                            => fail(s"no action '$other'")

  private val Actions = Vector("read who is online", "retry or discard a parked send", "change the CRM configuration")

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

  When("each asks for the action {string} on engagement") { (action: String) =>
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

  When("they ask for any action on engagement") { () => all = Actions.map(a => a -> ask(a, token)) }

  Then("they are refused, naming the permission they lack") { () =>
    all.foreach((a, r) => assert(refusedNaming(r), s"$a: $r"))
  }
  Then("they are admitted to every action on engagement") { () =>
    all.foreach((a, r) => assert(admitted(r), s"$a: $r"))
  }

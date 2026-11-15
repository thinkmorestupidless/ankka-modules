package io.eitheror.casino.attribution.features

import io.eitheror.casino.attribution.Json
import io.eitheror.casino.attribution.carriers.{Delivery, DeliveryRules}
import io.eitheror.casino.attribution.it.Reply
import io.eitheror.casino.attribution.tag.Pseudonyms
import io.eitheror.casino.contract.access.OperatorPermissions

import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import scala.collection.mutable
import scala.concurrent.duration.*

/**
 * features/attribution/operator-roles.feature (spec 030 T025, amendment E1): each operator route requires one
 * permission, decided from the brand's roles; the kit's roles are the role matrix. Admitted is a 2xx answer, refused a
 * 403 naming the permission. Each admitted discard takes an undelivered fact of its own, made once for the feature.
 */
class OperatorRolesFeatures extends AttributionFeatures("operator-roles.feature"):

  private val Roles = Vector(OperatorPermissions.Support, OperatorPermissions.Finance, OperatorPermissions.Compliance,
    OperatorPermissions.Admin)

  private def backoffice = s"/v1/casino/backoffice/${kit.brand}/attribution"
  private def webhooks   = s"/v1/casino/backoffice/${kit.brand}/attribution-webhooks"

  private var tokens  = Map.empty[String, Seq[(String, String)]]
  private var answers = Map.empty[String, Reply]
  private var all     = Vector.empty[(String, Reply)]
  private var token   = Seq.empty[(String, String)]

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    tokens = Map.empty; answers = Map.empty; all = Vector.empty; token = Nil

  private def operator(op: String, role: String) = kit.asOperator(op, s"brand_${kit.brand},$role")

  /**
   * Two undelivered facts, each a delivery id: a subscription whose address answers 503, two registrations posted to
   * it, and a day and more, after which each is undelivered (FR-018). The subscription is then made inactive.
   */
  private lazy val undelivered: mutable.Queue[String] =
    kit.receiver.default = Some(503)
    val made = kit.request("POST", webhooks, Some(Json.render(Json.obj("network" -> "programme", "address" -> kit.receiver.hookUrl,
      "secret" -> kit.receiver.secret))), kit.asOperator())
    assertEquals(made.status, 201, made.text)
    val sub = made.json.get("id").asText
    kit.eventually("the subscription listed")(Option.when(
      kit.request("GET", s"$webhooks?network=programme", headers = kit.asOperator()).text.contains(sub))(())): Unit
    val key = Pseudonyms.keyBytes(kit.testKit.secrets.get(Pseudonyms.secretName("programme")).get)
    val facts = Vector.fill(2) {
      val player = kit.bindPlayer(Some(kit.network.mint("aff-1", kit.fresh("lnk"))))
      s"registration/${Pseudonyms.derive(key, player)}"
    }
    def delivery(fact: String) = kit.components.forWorkflow(Delivery.id(sub, fact)).call(Delivery.status).invoke()
    for f <- facts do kit.eventually(s"$f attempted")(Option.when(delivery(f).attempts >= 1)(())): Unit
    kit.clock.moveBy(25.hours)
    for f <- facts do kit.eventually(s"$f undelivered", 60.seconds)(Option.when(delivery(f).status == DeliveryRules.Undelivered)(())): Unit
    kit.request("PUT", s"$webhooks/$sub", Some("""{"active":false}"""), kit.asOperator()): Unit
    mutable.Queue.from(facts.map(f => URLEncoder.encode(s"$sub/$f", UTF_8)))

  /** The action, as the operator `as` asks for it; its permission is the one the route names. */
  private def ask(action: String, as: Seq[(String, String)], admitted: Boolean): Reply = action match
    case "read a player's attribution record" =>
      val player = kit.bindPlayer(Some(kit.network.mint("aff-1", kit.fresh("lnk"))))
      kit.eventually(s"$player bound")(kit.record(player).binding): Unit
      kit.request("GET", s"$backoffice/records/$player", headers = as)
    case "resend or discard an undelivered fact" =>
      val delivery = if admitted then undelivered.dequeue() else "none%2Fregistration%2Fnone"
      kit.request("POST", s"$webhooks/undelivered/$delivery/discard", headers = as)
    case "add a network, a webhook or a signing key" =>
      kit.request("POST", s"$backoffice/signing-keys", headers = as)
    case other => fail(s"no action '$other'")

  private val Actions = Vector("read a player's attribution record", "resend or discard an undelivered fact",
    "add a network, a webhook or a signing key")

  /** The permission each action's route requires, so that only an admitted caller takes an undelivered fact. */
  private val Permission = Map(Actions(0) -> "player.read", Actions(1) -> "fact.redeliver", Actions(2) -> "config.attribution")

  private def holds(roles: Set[String], action: String): Boolean = kit.roles.permissionsOf(roles).exists(_(Permission(action)))

  private def admitted(r: Reply): Boolean      = r.status / 100 == 2
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
      for r <- Roles do assertEquals(kit.roles.permissionsOf(Set(r)), Some(OperatorPermissions.DefaultRoles(r)))
      tokens = Roles.map(r => r -> operator(s"op-$r", r)).toMap
  }

  When("each asks for the action {string} on attribution") { (action: String) =>
    answers = Roles.map(r => r -> ask(action, tokens(r), holds(Set(r), action))).toMap
  }

  Then("the support operator is {string}") { (o: String) => expect(OperatorPermissions.Support, o) }
  Then("the finance operator is {string}") { (o: String) => expect(OperatorPermissions.Finance, o) }
  Then("the compliance operator is {string}") { (o: String) => expect(OperatorPermissions.Compliance, o) }
  Then("the admin operator is {string}") { (o: String) => expect(OperatorPermissions.Admin, o) }

  private var held = Set.empty[String]

  Given("an operator of the brand holding only the payments role {string}") { (role: String) =>
    held = Set(role); token = operator("op-payments", role)
  }
  Given("an operator holding the role {string}") { (role: String) =>
    held = Set(role); token = kit.asOperator("op-platform", role)
  }

  When("they ask for any action on attribution") { () => all = Actions.map(a => a -> ask(a, token, holds(held, a))) }

  Then("they are refused, naming the permission they lack") { () =>
    all.foreach((a, r) => assert(refusedNaming(r), s"$a: ${r.status} ${r.text.take(300)}"))
  }
  Then("they are admitted to every action on attribution") { () =>
    all.foreach((a, r) => assert(admitted(r), s"$a: ${r.status} ${r.text.take(300)}"))
  }

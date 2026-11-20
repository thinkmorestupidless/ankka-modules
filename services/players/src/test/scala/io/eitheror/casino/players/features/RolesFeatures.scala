package io.eitheror.casino.players.features

import io.eitheror.casino.contract.access.OperatorPermissions
import io.eitheror.casino.contract.facts.*
import io.eitheror.casino.players.Json
import io.eitheror.casino.players.Json.*
import io.eitheror.casino.players.it.Reply
import io.eitheror.casino.players.roles.RoleAssignment

import io.eitheror.casino.players.PlayersRuntime

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * features/players/roles.feature (spec 030 T012): the brand's roles as `players` keeps them, changed and
 * given through its operator routes. Entities and views outlive a scenario in the shared kit, so each
 * scenario starts from the role matrix: the default roles reset and the roles scenarios made deleted.
 */
class RolesFeatures extends PlayersFeatures("roles.feature"):
  protected def pending = RolesFeatures.Elsewhere

  private def brand = s"brand_${kit.slug}"
  private val Admin = "op-admin"

  private var reply: Reply   = null
  private var replies        = Vector.empty[Reply]
  private var holder         = ""
  private var supportBefore  = Set.empty[String]
  private var other          = ""

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    reply = null; replies = Vector.empty; holder = ""; supportBefore = Set.empty
    other = s"op-${java.util.UUID.randomUUID().toString.take(8)}"
    restore()

  // ── the brand's roles, through the routes ───────────────────────────────────────────────────────

  private def as(roles: String, subject: String) = kit.asOperator(s"$brand,${kit.in(roles)}", subject)
  private def asAdmin                            = as(OperatorPermissions.Admin, Admin)
  private def asPlatform                         = kit.asOperator(OperatorPermissions.PlatformAdmin, "op-platform")

  private def list(headers: Seq[(String, String)] = asAdmin): Reply = kit.request("GET", kit.operatorPath("/roles"), None, headers)

  private def roles: Map[String, Set[String]] =
    list(asPlatform).json.path("roles").elements().asScala.map(r => r.path("name").asText -> r.strings("permissions").toSet).toMap

  private def set(name: String, permissions: Set[String], headers: Seq[(String, String)] = asAdmin): Reply =
    kit.json("PUT", kit.operatorPath(s"/roles/$name"), Json.obj("description" -> "kit", "permissions" -> Json.arr(permissions.toVector.sorted*)), headers)

  private def reset(name: String, headers: Seq[(String, String)] = asAdmin): Reply =
    kit.request("POST", kit.operatorPath(s"/roles/$name/reset"), None, headers)

  private def delete(name: String, headers: Seq[(String, String)] = asAdmin): Reply =
    kit.request("DELETE", kit.operatorPath(s"/roles/$name"), None, headers)

  private def give(operator: String, roles: Seq[String], headers: Seq[(String, String)] = asAdmin): Reply =
    kit.json("PUT", kit.operatorPath(s"/role-assignments/$operator"), Json.obj("roles" -> Json.arr(roles*)), headers)

  /** Waits until the brand's roles, as `players` lists them, are `p`. */
  private def until(what: String)(p: Map[String, Set[String]] => Boolean): Map[String, Set[String]] =
    val settled = kit.eventually(what, 20.seconds)(Some(roles).filter(p))
    PlayersRuntime.bags.refresh()
    settled

  /**
   * Puts the brand's roles back to the role matrix. The list is read again on every attempt and only what
   * still differs is reset or deleted: a list that lags the last scenario's change shows a role as it was,
   * and one pass over it would leave that role as the scenario left it (run 44590271829).
   */
  private def restore(): Unit =
    until("the default roles seeded")(r => OperatorPermissions.DefaultRoles.keySet.subsetOf(r.keySet))
    kit.eventually("the role matrix", 60.seconds) {
      val now = roles
      if now == OperatorPermissions.DefaultRoles then Some(())
      else
        for (name, bag) <- OperatorPermissions.DefaultRoles if now.get(name).exists(_ != bag) do
          assertEquals(reset(name, asPlatform).status, 200)
        // A role deleted on an earlier attempt and still listed is answered 404: it is gone, as wanted.
        for name <- now.keySet -- OperatorPermissions.DefaultRoles.keySet do
          assert(Set(200, 404)(delete(name, asPlatform).status), s"deleting $name")
        Thread.sleep(500)
        None
    }
    PlayersRuntime.bags.refresh()

  private def audits(action: String, subject: String): Vector[AuditRecord] =
    kit.facts(PlatformAudit).collect { case a: AuditRecord if a.action == action && a.subjectId == subject => a }

  private def changes(a: AuditRecord, field: String): (Set[String], Set[String]) =
    val c = a.changes.find(_.field == field).getOrElse(fail(s"no $field change in $a"))
    def bag(s: Option[String]) = s.toVector.flatMap(_.split(',')).filter(_.nonEmpty).toSet
    (bag(c.before), bag(c.after))

  // ── defaults ────────────────────────────────────────────────────────────────────────────────────

  Given("a brand that has just been applied") { () => () }

  When("an operator reads the brand's roles") { () => reply = list(as(OperatorPermissions.Support, "op-1")) }

  Then("the roles {string}, {string}, {string} and {string} are listed") { (a: String, b: String, c: String, d: String) =>
    assertEquals(reply.status, 200, reply.text)
    val names = reply.json.path("roles").elements().asScala.map(_.path("name").asText).toSet
    assertEquals(names, Set(a, b, c, d))
  }

  Then("each carries its default permissions") { () =>
    val listed = reply.json.path("roles").elements().asScala.map(r => r.path("name").asText -> r.strings("permissions").toSet).toMap
    assertEquals(listed, OperatorPermissions.DefaultRoles)
    assert(reply.json.path("roles").elements().asScala.forall(_.path("default").asBoolean), reply.text)
  }

  // ── a change is recorded ────────────────────────────────────────────────────────────────────────

  Given("an admin operator of a brand") { () => () }

  When("the operator changes the permissions of a role") { () =>
    supportBefore = OperatorPermissions.DefaultRoles(OperatorPermissions.Support)
    reply = set(OperatorPermissions.Support, supportBefore + "report.read")
  }

  Then("an audit record names the operator, the role and the permissions before and after") { () =>
    assertEquals(reply.status, 200, reply.text)
    val a = kit.eventually("the role's audit record")(audits("role-change", OperatorPermissions.Support).find(_.actorId == Admin))
    assertEquals(a.subjectKind, "role")
    assertEquals(a.actorKind, "operator")
    assertEquals(changes(a, "permissions"), (supportBefore, supportBefore + "report.read"))
  }

  // ── an unknown permission ───────────────────────────────────────────────────────────────────────

  When("the operator creates a role carrying a permission that does not exist") { () =>
    reply = set("vip_desk", Set("player.read", "player.teleport"))
  }

  Then("the role is refused, naming the permission") { () =>
    assertEquals(reply.status, 400, reply.text)
    assertEquals(reply.str("error"), "unknown_permission")
    assertEquals(reply.str("permission"), "player.teleport")
    assert(!roles.contains("vip_desk"))
  }

  // ── the last role that may change roles ─────────────────────────────────────────────────────────

  Given("a brand whose only role carrying the permission to change roles is {string}") { (role: String) =>
    assertEquals(roles.collect { case (r, bag) if bag("roles.change") => r }.toSet, Set(role))
  }

  When("an admin operator removes that permission from {string}") { (role: String) =>
    reply = set(role, OperatorPermissions.All.toSet - "roles.change")
  }

  Then("the change is refused, because nobody could change roles after it") { () =>
    assertEquals(reply.status, 409, reply.text)
    assertEquals(reply.str("error"), "last_role_changer")
    assert(roles(OperatorPermissions.Admin)("roles.change"))
  }

  // ── reset and delete ────────────────────────────────────────────────────────────────────────────

  Given("a brand whose role {string} was changed") { (role: String) =>
    assertEquals(set(role, Set("player.read")).status, 200)
    until(s"$role changed")(_.get(role).contains(Set("player.read")))
  }

  When("an admin operator resets the role {string}") { (role: String) => reply = reset(role) }

  Then("it carries its default permissions") { () =>
    assertEquals(reply.status, 200, reply.text)
    val name = reply.str("name")
    assertEquals(reply.json.strings("permissions").toSet, OperatorPermissions.DefaultRoles(name))
    until(s"$name reset")(_.get(name).contains(OperatorPermissions.DefaultRoles(name)))
  }

  Then("deleting the role {string} is refused") { (role: String) =>
    val r = delete(role)
    assertEquals(r.status, 409, r.text)
    assertEquals(r.str("error"), "default_role")
    assert(roles.contains(role))
  }

  // ── a role the brand made, deleted ──────────────────────────────────────────────────────────────

  Given("a role {string} the brand created, held by an operator") { (role: String) =>
    // roles.read is a permission players itself requires, so the role's effect is seen in this service
    assertEquals(set(role, Set("roles.read")).status, 200)
    holder = "op-vip"
    kit.eventually(s"$role admits its holder")(Some(list(as(role, holder))).filter(_.status == 200))
  }

  When("an admin operator deletes the role {string}") { (role: String) => reply = delete(role) }

  Then("within {string} seconds that operator is refused what only {string} admitted") { (seconds: String, role: String) =>
    assertEquals(reply.status, 200, reply.text)
    val r = kit.eventually(s"$role no longer admits", seconds.toInt.seconds)(Some(list(as(role, holder))).filter(_.status == 403))
    assert(r.text.contains("roles.read"), r.text)
  }

  // ── platform_admin ──────────────────────────────────────────────────────────────────────────────

  Given("a brand whose roles were changed") { () =>
    assertEquals(set(OperatorPermissions.Admin, OperatorPermissions.All.toSet - "report.compliance").status, 200)
    assertEquals(set(OperatorPermissions.Support, Set.empty).status, 200)
    until("the roles changed")(r => r.get(OperatorPermissions.Support).contains(Set.empty))
  }

  When("an operator holding the role {string} asks for any action") { (role: String) =>
    val h = kit.asOperator(role, "op-platform")
    replies = Vector(list(h), set("desk", Set("player.read"), h), delete("desk", h), give(other, Seq(OperatorPermissions.Support), h))
  }

  Then("they are admitted") { () =>
    replies.foreach(r => assert(r.status == 200 || r.status == 202, r.text))
  }

  Then("the role {string} cannot be changed by the brand") { (role: String) =>
    for r <- Vector(set(role, Set("player.read")), delete(role), give(other, Seq(role))) do
      assertEquals(r.status, 400, r.text)
      assertEquals(r.str("error"), "reserved_role")
    assert(!roles.contains(role))
  }

  // ── giving roles ────────────────────────────────────────────────────────────────────────────────

  Given("an operator who may give roles and does not hold the permission to make an adjustment") { () =>
    assertEquals(set("giver", Set("roles.read", "roles.give")).status, 200)
    until("the giver role")(_.contains("giver"))
    holder = "op-giver"
  }

  When("the operator gives the role {string} to another operator") { (role: String) =>
    kit.wallet.clearReceived()
    reply = give(other, Seq(role), if holder.nonEmpty then as("giver", holder) else asAdmin)
  }

  Then("the brand refuses, naming the permission, and the shared payments are not asked") { () =>
    assertEquals(reply.status, 403, reply.text)
    assertEquals(reply.str("error"), "forbidden")
    assert(OperatorPermissions.DefaultRoles(OperatorPermissions.Finance)(reply.str("permission")), reply.text)
    assertEquals(kit.wallet.brandRoles, Vector.empty)
  }

  Then("the other operator's roles are unchanged") { () =>
    val s = kit.components.forEventSourcedEntity(RoleAssignment.id(other)).call(RoleAssignment.get).invoke()
    assert(!s.roles.contains(OperatorPermissions.Finance), s.toString)
  }

  Then("the shared payments are asked to set that operator's roles for the brand") { () =>
    assertEquals(reply.status, 202, reply.text)
    val calls = kit.wallet.brandRoles
    assertEquals(calls.map(_.path), Vector(s"/v1/operators/$other/brand-roles/${kit.slug}"))
    assertEquals(Json.parse(calls.head.body).get.strings("roles"), Vector(OperatorPermissions.Support))
  }

  Then("an audit record names both operators and the role") { () =>
    val a = kit.eventually("the giving's audit record")(audits("roles-give", other).find(_.actorId == Admin))
    assertEquals(a.subjectKind, "operator")
    assert(changes(a, "roles")._2.contains(OperatorPermissions.Support), a.toString)
  }

object RolesFeatures:
  private val Bags = "the contract's RoleBagsSuite: a service's kept copy of the roles, which every service uses alike"
  val Elsewhere: Map[String, String] = Map(
    "a role's change is in force in every part of the brand within fifteen seconds and no part is deployed again" ->
      "T128 (tests/it/roles.it.ts): across services on an installation",
    "a part that has not yet read the brand's roles answers that it is unavailable" -> Bags,
    "a part that cannot reach the brand's roles keeps using the last it read"       -> Bags
  )

package io.eitheror.casino.players.it

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.contract.access.OperatorPermissions
import io.eitheror.casino.players.{Json, PlayersRuntime}
import io.eitheror.casino.players.Json.*

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * Spec 030 P12, FR-008d through the routes: two changes at once each taking `roles.change` from a different role cannot both pass. Each test waits for
 * the roles it made to leave the view, so a suite after it does not find them listed and already deleted.
 */
class RoleChangesSuite extends munit.FunSuite with LogCapturing:
  override val munitTimeout = 2.minutes
  private lazy val kit = SharedKit.kit

  override def beforeEach(context: BeforeEach): Unit = kit.reset()

  private def brand      = s"brand_${kit.slug}"
  private def asPlatform = kit.asOperator(OperatorPermissions.PlatformAdmin, "op-platform")

  private def set(name: String, permissions: Set[String], headers: Seq[(String, String)]): Reply =
    kit.json("PUT", kit.operatorPath(s"/roles/$name"), Json.obj("description" -> "kit", "permissions" -> Json.arr(permissions.toVector.sorted*)), headers)

  private def delete(name: String): Reply = kit.request("DELETE", kit.operatorPath(s"/roles/$name"), None, asPlatform)

  private def roles: Map[String, Set[String]] =
    kit.request("GET", kit.operatorPath("/roles"), None, asPlatform).json.path("roles").elements().asScala
      .map(r => r.path("name").asText -> r.strings("permissions").toSet).toMap

  private def until(what: String)(p: Map[String, Set[String]] => Boolean): Unit =
    kit.eventually(what, 20.seconds)(Some(roles).filter(p)): Unit
    PlayersRuntime.bags.refresh()

  test("two changes at once, each taking roles.change from a different role: one passes, the other is refused") {
    given ExecutionContext = ExecutionContext.global
    val admin = OperatorPermissions.DefaultRoles(OperatorPermissions.Admin)
    assertEquals(set("ops", Set("roles.read", "roles.change"), asPlatform).status, 200)
    try
      until("ops beside brand_admin")(r => r.get("ops").exists(_("roles.change")) && r.get(OperatorPermissions.Admin).exists(_("roles.change")))
      val both = Future.sequence(Vector(
        Future(set(OperatorPermissions.Admin, admin - "roles.change", asPlatform)),
        Future(set("ops", Set("roles.read"), asPlatform))))
      val replies = Await.result(both, 60.seconds)
      assertEquals(replies.map(_.status).sorted, Vector(200, 409), replies.map(_.text).mkString(" | "))
      assertEquals(replies.find(_.status == 409).get.str("error"), "last_role_changer")
      until("the changes seen")(r => r.values.count(_("roles.change")) == 1)
    finally
      assertEquals(kit.request("POST", kit.operatorPath(s"/roles/${OperatorPermissions.Admin}/reset"), None, asPlatform).status, 200)
      until("brand_admin reset")(_.get(OperatorPermissions.Admin).contains(admin))
      assertEquals(delete("ops").status, 200)
      until("ops gone")(!_.contains("ops"))
  }

package io.eitheror.casino.players.it

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.contract.access.OperatorPermissions
import io.eitheror.casino.players.{Json, PlayersRuntime}
import io.eitheror.casino.players.Json.*
import io.eitheror.casino.players.roles.RoleAssignment

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * Spec 030 P13, FR-008c: a giving replaces the operator's roles, so a giver is held to the permissions of the
 * roles it takes away as well as those it gives.
 */
class RoleGivingSuite extends munit.FunSuite with LogCapturing:
  override val munitTimeout = 2.minutes
  private lazy val kit = SharedKit.kit

  override def beforeEach(context: BeforeEach): Unit = kit.reset()

  private def brand   = s"brand_${kit.slug}"
  private def asAdmin = kit.asOperator(s"$brand,${kit.in(OperatorPermissions.Admin)}", "op-admin")

  private def give(operator: String, roles: Seq[String], headers: Seq[(String, String)]): Reply =
    kit.json("PUT", kit.operatorPath(s"/role-assignments/$operator"), Json.obj("roles" -> Json.arr(roles*)), headers)

  private def listed: Set[String] =
    kit.request("GET", kit.operatorPath("/roles"), None, kit.asOperator(OperatorPermissions.PlatformAdmin, "op-platform"))
      .json.path("roles").elements().asScala.map(_.path("name").asText).toSet

  test("a giver who may give roles cannot take away a role carrying a permission it lacks; one it holds, it can") {
    val other = s"op-${java.util.UUID.randomUUID().toString.take(8)}"
    val made  = kit.json("PUT", kit.operatorPath("/roles/taker"), Json.obj("description" -> "kit",
      "permissions" -> Json.arr("roles.read", "roles.give", "player.read")), asAdmin)
    assertEquals(made.status, 200, made.text)
    try
      kit.eventually("the taker role", 20.seconds)(Option.when(listed("taker"))(()))
      PlayersRuntime.bags.refresh()
      val first = give(other, Seq(OperatorPermissions.Finance, "taker"), asAdmin)
      assertEquals(first.status, 200, first.text)
      kit.wallet.clearReceived()

      val taker = kit.asOperator(s"$brand,${kit.in("taker")}", "op-taker")
      val refused = give(other, Seq("taker"), taker)
      assertEquals(refused.status, 403, refused.text)
      assertEquals(refused.str("error"), "forbidden")
      assert(OperatorPermissions.DefaultRoles(OperatorPermissions.Finance)(refused.str("permission")), refused.text)
      assertEquals(kit.wallet.brandRoles, Vector.empty, "the shared payments are not asked")
      val held = kit.components.forEventSourcedEntity(RoleAssignment.id(other)).call(RoleAssignment.get).invoke().roles
      assertEquals(held, Set(OperatorPermissions.Finance, "taker"))

      assertEquals(give(other, Seq("taker"), asAdmin).status, 200)
      val taken = give(other, Seq.empty, taker)
      assertEquals(taken.status, 200, taken.text)
    finally
      assertEquals(kit.request("DELETE", kit.operatorPath("/roles/taker"), None, asAdmin).status, 200)
      // gone from the view before the next suite lists the roles, so none finds it listed and already deleted
      kit.eventually("the taker role gone", 20.seconds)(Option.when(!listed("taker"))(()))
  }

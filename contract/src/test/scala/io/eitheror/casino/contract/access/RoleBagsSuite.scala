package io.eitheror.casino.contract.access

import com.thinkmorestupidless.ankka.runtime.RotatingTls
import Requests.*

import java.time.{Clock, Instant, ZoneOffset}
import java.util.concurrent.atomic.AtomicReference

/** Spec 030 contracts/roles.md *How a service opens a bag*: the copy every service of a brand keeps. */
class RoleBagsSuite extends munit.FunSuite:

  private val self = RotatingTls.Identity("goldvibe", "wallet")

  /** A clock the test moves. */
  private final class Hands(var at: Instant) extends Clock:
    def getZone                 = ZoneOffset.UTC
    override def withZone(z: java.time.ZoneId): Clock = this
    def instant                 = at

  /** `players`' answer: the default roles, which a brand cannot delete, with `roles` over them. */
  private def answer(roles: (String, Seq[String])*): String =
    val defaults = OperatorPermissions.DefaultRoles.keys.toVector.sorted.map(_ -> Seq.empty[String])
    bare((defaults.filterNot(d => roles.exists(_._1 == d._1)) ++ roles)*)

  /** An answer of exactly `roles`. */
  private def bare(roles: (String, Seq[String])*): String =
    roles.map((n, ps) => s"""{"name":"$n","description":"","permissions":[${ps.map(p => s"\"$p\"").mkString(",")}],"default":false}""")
      .mkString("""{"roles":[""", ",", "]}")

  private def fixture(first: Either[String, String]) =
    val next  = AtomicReference(first)
    val calls = AtomicReference(0)
    val clock = Hands(Instant.parse("2026-11-15T09:00:00Z"))
    val bags  = RoleBags(() => { calls.updateAndGet(_ + 1); next.get }, clock)
    (bags, next, calls, clock)

  private def acl(bags: RoleBags, permission: String) = Routes.operatorMay("goldvibe", permission, bags, verified)

  test("until the first read it decides unavailable, never admit or refuse") {
    val (bags, _, _, _) = fixture(Left("players is not reachable"))
    assertEquals(bags.state, RoleBags.State.Unread)
    decide(acl(bags, "adjustment.make"), Request(token = Some(operatorWith("brand_goldvibe", "brand_finance@goldvibe"))), self) match
      case Outcome.Refused(503, reason) => assert(reason.contains("roles"), reason)
      case other                        => fail(other.toString)
    bags.refresh()
    assertEquals(bags.state, RoleBags.State.Unread)
    decide(acl(bags, "adjustment.make"), Request(token = Some(operatorWith("brand_goldvibe", "brand_finance@goldvibe"))), self) match
      case Outcome.Refused(503, _) => ()
      case other                   => fail(other.toString)
  }

  test("a token is admitted when the bag of one of its roles holds the permission, from the kept copy") {
    val (bags, _, calls, _) = fixture(Right(answer("brand_finance" -> Seq("adjustment.make"), "brand_support" -> Seq("player.read"))))
    bags.refresh()
    assertEquals(calls.get, 1)
    val finance = Request(token = Some(operatorWith("brand_goldvibe", "brand_finance@goldvibe")))
    assertEquals(decide(acl(bags, "adjustment.make"), finance, self), Outcome.Served)
    // no call is made in the request
    assertEquals(calls.get, 1)
  }

  test("a role whose bag lacks the permission is refused forbidden, naming the permission") {
    val (bags, _, _, _) = fixture(Right(answer("brand_support" -> Seq("player.read"))))
    bags.refresh()
    decide(acl(bags, "adjustment.make"), Request(token = Some(operatorWith("brand_goldvibe", "brand_support@goldvibe"))), self) match
      case Outcome.Refused(403, reason) => assertEquals(reason, "forbidden: the adjustment.make permission is needed")
      case other                        => fail(other.toString)
  }

  test("the brand is still needed, and a player's token is still the wrong issuer") {
    val (bags, _, _, _) = fixture(Right(answer("brand_finance" -> Seq("adjustment.make"))))
    bags.refresh()
    decide(acl(bags, "adjustment.make"), Request(token = Some(operatorWith("brand_spinvibe", "brand_finance"))), self) match
      case Outcome.Refused(403, reason) => assert(reason.startsWith("brand_not_in_roles"), reason)
      case other                        => fail(other.toString)
    decide(acl(bags, "adjustment.make"), Request(token = Some(playerOf("goldvibe"))), self) match
      case Outcome.Refused(403, reason) => assert(reason.startsWith("wrong_issuer"), reason)
      case other                        => fail(other.toString)
    assertEquals(decide(acl(bags, "adjustment.make"), Request(), self).asInstanceOf[Outcome.Refused].status, 401)
  }

  test("platform_admin is admitted to every permission, before the first read too") {
    val (bags, _, _, _) = fixture(Left("not yet"))
    assertEquals(decide(acl(bags, "roles.change"), Request(token = Some(operatorWith("platform_admin"))), self), Outcome.Served)
  }

  test("a payments role confers no casino action") {
    val (bags, _, _, _) = fixture(Right(answer("brand_finance" -> Seq("adjustment.make"))))
    bags.refresh()
    val token = Request(token = Some(operatorWith("brand_goldvibe", "resource_admin@goldvibe", "resource_finance@goldvibe")))
    assertEquals(decide(acl(bags, "adjustment.make"), token, self).asInstanceOf[Outcome.Refused].status, 403)
  }

  test("it reads again when 10 seconds have passed, and not before") {
    val (bags, next, calls, clock) = fixture(Right(answer("brand_finance" -> Seq())))
    bags.refreshIfDue()
    assertEquals(calls.get, 1)
    clock.at = clock.at.plusSeconds(9)
    bags.refreshIfDue()
    assertEquals(calls.get, 1)
    next.set(Right(answer("brand_finance" -> Seq("adjustment.make"))))
    clock.at = clock.at.plusSeconds(1)
    bags.refreshIfDue()
    assertEquals(calls.get, 2)
    assertEquals(decide(acl(bags, "adjustment.make"), Request(token = Some(operatorWith("brand_goldvibe", "brand_finance@goldvibe"))), self), Outcome.Served)
    assertEquals(RoleBags.Interval.toSeconds, 10L)
  }

  test("when players cannot be reached it keeps its last copy and says so") {
    val (bags, next, _, clock) = fixture(Right(answer("brand_finance" -> Seq("adjustment.make"))))
    bags.refresh()
    val readAt = clock.at
    next.set(Left("players answered 503"))
    clock.at = clock.at.plusSeconds(10)
    bags.refresh()
    bags.state match
      case RoleBags.State.Read(roles, at, problem) =>
        assertEquals(roles("brand_finance"), Set("adjustment.make"))
        assertEquals(at, readAt)
        assertEquals(problem, Some("players answered 503"))
      case other => fail(other.toString)
    assertEquals(decide(acl(bags, "adjustment.make"), Request(token = Some(operatorWith("brand_goldvibe", "brand_finance@goldvibe"))), self), Outcome.Served)
  }

  test("an answer that is not the roles' shape is a failed read, not an empty brand") {
    val (bags, next, _, _) = fixture(Right(answer("brand_finance" -> Seq("adjustment.make"))))
    bags.refresh()
    next.set(Right("""{"error":"x"}"""))
    bags.refresh()
    bags.state match
      case RoleBags.State.Read(roles, _, Some(_)) => assert(roles.contains("brand_finance"))
      case other                                  => fail(other.toString)
  }

  test("a permission the contract does not know is dropped from a bag") {
    val (bags, _, _, _) = fixture(Right(answer("desk" -> Seq("player.read", "player.teleport"))))
    bags.refresh()
    bags.state match
      case RoleBags.State.Read(roles, _, _) => assertEquals(roles("desk"), Set("player.read"))
      case other                            => fail(other.toString)
  }

  test("the operator's permissions are opened from the kept copy, None while unread") {
    val (bags, _, _, _) = fixture(Right(answer("desk" -> Seq("player.read"), "cashier" -> Seq("withdrawal.review"))))
    assertEquals(bags.permissionsOf("goldvibe", Set("desk@goldvibe")), None)
    bags.refresh()
    assertEquals(bags.permissionsOf("goldvibe", Set("desk@goldvibe", "cashier@goldvibe")), Some(Set("player.read", "withdrawal.review")))
    assertEquals(bags.permissionsOf("goldvibe", Set(OperatorPermissions.PlatformAdmin)), Some(OperatorPermissions.All.toSet))
  }

  test("an empty answer, or one missing a default role, is a failed read: the kept copy stays (review S2)") {
    val (bags, next, _, _) = fixture(Right(answer("brand_finance" -> Seq("adjustment.make"))))
    bags.refresh()
    val finance = Request(token = Some(operatorWith("brand_goldvibe", "brand_finance@goldvibe")))
    for body <- Seq("""{"roles":[]}""", """{"roles":[{"description":"no name"}]}""", bare("brand_finance" -> Seq("adjustment.make"), "brand_support" -> Seq())) do
      next.set(Right(body))
      bags.refresh()
      bags.state match
        case RoleBags.State.Read(roles, _, Some(why)) =>
          assertEquals(roles("brand_finance"), Set("adjustment.make"), body)
          assert(why.contains("default roles") || why.contains("lacks"), why)
        case other => fail(s"$body: $other")
      assertEquals(decide(acl(bags, "adjustment.make"), finance, self), Outcome.Served, body)
  }

  test("an empty first answer leaves the bags unread, and the status says why") {
    val (bags, _, _, _) = fixture(Right("""{"roles":[]}"""))
    bags.refresh()
    assertEquals(bags.state, RoleBags.State.Unread)
    assert(bags.status.contains("brand_admin"), bags.status)
  }

  test("a fixed copy holds the roles it is given, default roles or not") {
    val bags = RoleBags.fixed(Map("desk" -> Set("player.read", "player.teleport")))
    assertEquals(bags.permissionsOf("goldvibe", Set("desk@goldvibe")), Some(Set("player.read")))
  }

  test("a second start while the reader runs starts no second reader") {
    val (bags, _, calls, _) = fixture(Right(answer()))
    bags.start()
    bags.start()
    try
      assert(bags.started)
      Thread.sleep(200)
      assertEquals(calls.get, 1)
    finally bags.stop()
    assert(!bags.started)
  }

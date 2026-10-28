package io.eitheror.casino.contract.access

import com.thinkmorestupidless.ankka.http.*
import com.thinkmorestupidless.ankka.runtime.RotatingTls

/**
 * The machine route class (spec 012 FR-020, research R14): admitted by a grant on the route, held to a
 * registered machine by the handler. Decided as ankka's server decides a granted route: the caller, the
 * route being called and the grants naming this service.
 */
class MachineRouteSuite extends munit.FunSuite:

  private val self  = RotatingTls.Identity("goldvibe", "attribution")
  private val feed  = GrantTarget.Route("GET", "/v1/casino/brands/{brand}/attribution/networks/{network}/feed")
  private val other = GrantTarget.Route("GET", "/v1/casino/brands/{brand}/attribution/keys")
  private val acme  = Caller.Machine("acme", "reader")

  private val grants = Grants.of(
    GrantEntry(acme, feed),
    GrantEntry(Caller.Service("programme", "affiliate-programme"), feed)
  )

  private def admitted(caller: Caller, target: GrantTarget = feed): Boolean = Routes.machine match
    case Acl.AllowCallers(matchers) => matchers.exists(_.admits(caller, self, Some(target), grants))
    case other                      => fail(s"the machine class is an AllowCallers naming the granted, not $other")

  test("the machine class names the granted, so a project's owners can grant the route") {
    assert(Acl.namesGranted(Routes.machine))
  }

  test("a machine a grant names on the route is admitted, and its principal is the machine") {
    assert(admitted(acme))
    assertEquals(Routes.machineCaller(acme), Right(Some("acme" -> "reader")))
  }

  test("a machine with no grant on this route, or a grant on another, is refused") {
    assert(!admitted(Caller.Machine("acme", "other")))
    assert(!admitted(acme, other))
  }

  test("a gateway caller with no machine token is refused") {
    assert(!admitted(Caller.Gateway))
  }

  test("a service a grant names is admitted by the ACL and refused by the handler as not a machine") {
    val service = Caller.Service("programme", "affiliate-programme")
    assert(admitted(service))
    Routes.machineCaller(service) match
      case Left(problem) =>
        assertEquals(problem.status, 403)
        assert(problem.message.startsWith(Routes.Refusal.NotMachine), problem.message)
      case Right(other) => fail(s"a service read a machine's route: $other")
  }

  test("a local run admits its caller and names no machine") {
    assert(admitted(Caller.Local))
    assertEquals(Routes.machineCaller(Caller.Local), Right(None))
  }

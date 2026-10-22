package io.eitheror.casino.modules

import java.nio.file.Files
import scala.jdk.CollectionConverters.*

/**
 * Spec 021 SC-003: no role on the installation can enable a module. Every route the contract lists
 * under `/modules` (`contract/access/routes.md`) is a read, or the one request a brand operator may
 * make to the vendor; the enabling writes are the vendor's and exist on no installation route.
 */
class ReadOnlyRoutesSuite extends munit.FunSuite:

  final case class Row(service: String, method: String, path: String, routeClass: String)

  private def rows: Vector[Row] =
    Files.readAllLines(Fixtures.root.resolve("contract/access/routes.md")).asScala.toVector
      .map(_.trim)
      .filter(l => l.startsWith("|") && !l.startsWith("|---") && !l.startsWith("| Service"))
      .map(_.stripPrefix("|").stripSuffix("|").split('|').map(_.trim).toVector)
      .collect { case Vector(service, method, path, cls, _*) => Row(service, method, path, cls) }

  private def modules = rows.filter(_.path.contains("/modules"))

  test("the operator's two modules routes are in the contract, on players, of class operator") {
    assertEquals(
      modules.map(r => (r.service, r.method, r.path, r.routeClass)).toSet,
      Set(
        ("players", "GET", "/v1/casino/brands/{brand}/modules", "operator"),
        ("players", "POST", "/v1/casino/brands/{brand}/modules/requests", "operator")
      )
    )
  }

  test("every route under /modules is a read or the request to the vendor (SC-003)") {
    val writes = modules.filterNot(r => r.method == "GET" || (r.method == "POST" && r.path.endsWith("/modules/requests")))
    assertEquals(writes, Vector.empty, "a route that could enable, disable, pin or move a module")
  }

  test("spec 008's route contract carries the same two routes") {
    val text = Files.readString(Fixtures.root.resolve("specs/008-players/contracts/routes.md"))
    modules.foreach(r => assert(text.contains(s"`${r.method} ${r.path}`"), s"${r.method} ${r.path}"))
  }

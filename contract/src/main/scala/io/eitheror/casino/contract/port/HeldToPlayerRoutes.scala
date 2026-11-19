package io.eitheror.casino.contract.port

import com.fasterxml.jackson.databind.ObjectMapper
import com.thinkmorestupidless.ankka.http.HttpServer
import com.thinkmorestupidless.ankka.testkit.HeldToDocument

import java.nio.file.Path

/**
 * A service's kit held to its kept description (spec 031 FR-015, contracts/descriptions.md "Holding
 * the tests"): the server is checked by ankka's `HeldToDocument` against the document it serves,
 * and `assertHeld` fails on each violation whose route is an operation of the kept
 * `openapi.player.json`. A route the description does not keep (an operator's, a service's) is not
 * this spec's to hold. `the-brand` in a kept path is the kit's own brand.
 *
 * ankka-testkit is `Provided` here: every service has it on its test class path, and nothing in a
 * service's main code calls this.
 */
final class HeldToPlayerRoutes(description: Path, brand: String):

  val violations: HeldToDocument.Violations = HeldToDocument.Violations()

  /** The same server, checked; give the kit this one. */
  def hold(server: HttpServer): HttpServer = HeldToDocument.server(server, violations)

  /** The kept operations as `HeldToDocument` names a route: `METHOD /path`, the brand in place. */
  lazy val kept: Set[String] = HeldToPlayerRoutes.kept(description, brand)

  /** Fails, and clears, listing each violation of a kept route; any other is dropped. */
  def assertHeld(): Unit =
    val found = HeldToPlayerRoutes.relevant(violations.all, kept)
    violations.clear()
    if found.nonEmpty then
      throw AssertionError(s"the service's player routes are not held to ${description.getFileName}:\n${found.map("  " + _).mkString("\n")}")

object HeldToPlayerRoutes:

  /** The brand segment of a kept description's paths (contracts/descriptions.md, `describe.env`). */
  val Placeholder = "the-brand"

  def kept(description: Path, brand: String): Set[String] =
    DescriptionCheck.operations(ObjectMapper().readTree(description.toFile))
      .map(o => s"${o.method.toUpperCase} ${o.path.split('/').map(s => if s == Placeholder then brand else s).mkString("/")}")
      .toSet

  /** The violations naming a kept route: each begins `METHOD /path:`, as `HeldToDocument` writes it. */
  def relevant(violations: Vector[String], kept: Set[String]): Vector[String] =
    violations.filter(v => kept.contains(v.takeWhile(_ != ':')))

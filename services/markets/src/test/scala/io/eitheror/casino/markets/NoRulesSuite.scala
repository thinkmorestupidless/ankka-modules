package io.eitheror.casino.markets

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

/**
 * SC-001: no source outside the ports names a price, quantity or phase rule. A main source outside
 * `ports/` and `api/` that builds one of ankka-predictions' domain commands, or applies or replays a
 * book or a market itself, holds a rule the library owns; the components' clients are the way in.
 */
class NoRulesSuite extends munit.FunSuite:
  private val root = Paths.get("src/main/scala")
  private val rules = Vector(
    "a domain command"    -> raw"\b(ExchangeCommand|MarketCommand)\.\w+\(".r,
    "the domain's rules"  -> raw"\b(Exchange|Market)\.(apply|empty|replay|decide)\b".r,
    "the matcher"         -> raw"\bMatcher\b".r
  )

  private def sources: Vector[Path] =
    Files.walk(root).iterator().asScala.filter(_.toString.endsWith(".scala")).toVector

  test("the main sources are found") {
    assert(Files.isDirectory(root), s"no $root under ${Paths.get("").toAbsolutePath}")
    assert(sources.nonEmpty)
  }

  test("no main source outside ports/ and api/ builds a domain command or applies the domain (SC-001)") {
    val outside = sources.filterNot(p => root.relativize(p).iterator().asScala.map(_.toString).exists(Set("ports", "api")))
    val found = for
      p              <- outside
      (n, line)      <- Files.readAllLines(p).asScala.zipWithIndex
      (what, rule)   <- rules
      if rule.findFirstIn(n).nonEmpty && !n.trim.startsWith("*") && !n.trim.startsWith("//")
    yield s"${root.relativize(p)}:${line + 1}: $what: ${n.trim}"
    assertEquals(found, Vector.empty)
  }

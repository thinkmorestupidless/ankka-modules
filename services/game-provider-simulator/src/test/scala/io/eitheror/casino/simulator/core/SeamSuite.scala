package io.eitheror.casino.simulator.core


import com.thinkmorestupidless.ankka.testkit.LogCapturing

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

/**
 * The seam of FR-006 and FR-015, enforced: the core names no protocol's route, field or status
 * word, and imports nothing from an adapter. A second real provider is then a second adapter and
 * no change here.
 */
class SeamSuite extends munit.FunSuite with LogCapturing:

  private val core = Paths.get("src/main/scala/io/eitheror/casino/simulator/core")

  private val sources: Vector[Path] = Files.walk(core).iterator().asScala.filter(_.toString.endsWith(".scala")).toVector

  private val words = Vector("ok", "error", "session_expired", "player_locked", "player_not_found", "not_enough_money",
    "spending_limit", "transaction_not_found", "provider_unavailable", "auth_failed")

  private val kinds = Vector("check", "balance", "player_profile", "debit", "credit", "cancel", "buyin", "payout", "free_debit")

  private def offences(check: String => Boolean): Vector[String] =
    sources.flatMap { file =>
      Files.readAllLines(file).asScala.zipWithIndex.collect {
        case (line, i) if !line.trim.startsWith("*") && !line.trim.startsWith("//") && check(line) => s"${core.relativize(file)}:${i + 1}: ${line.trim}"
      }
    }

  test("the core imports nothing from an adapter") {
    assertEquals(offences(l => l.contains("simulator.st8") || l.contains("simulator.brands")), Vector.empty)
  }

  test("the core names no protocol route") {
    assertEquals(offences(l => l.contains("\"/st8") || l.contains("/api/operator")), Vector.empty)
  }

  test("the core names no status word and no callback kind as a literal") {
    val literal = (w: String) => (l: String) => l.contains("\"" + w + "\"")
    // A scenario document's own field names (`str("balance")`) are the scenario format's, not a protocol's.
    val readsScenarioField = (l: String) => l.matches(""".*\b(str|int)\("[a-zA-Z]+"\).*""")
    assertEquals(offences(l => !readsScenarioField(l) && (words ++ kinds).exists(w => literal(w)(l))), Vector.empty)
  }

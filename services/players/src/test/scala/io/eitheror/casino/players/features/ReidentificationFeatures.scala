package io.eitheror.casino.players.features

import com.thinkmorestupidless.ankka.testkit.{GherkinSuite, LogCapturing}
import io.eitheror.casino.contract.facts.PlayerSuspended
import io.eitheror.casino.players.reads.{ActivityLines, ActivityRow}

import java.time.Instant

/**
 * features/audit/re-identification.feature's activity-tab scenario (spec 018 US4): inside the brand the
 * activity lines name the player by the real id, as spec 008 serves them; the audit's pseudonyms never
 * reach the brand's own reads.
 */
class ReidentificationFeatures extends GherkinSuite("../../features/audit/re-identification.feature") with LogCapturing:

  override protected def ranElsewhere: Map[String, String] = ReidentificationFeatures.Elsewhere

  private var line: Option[ActivityRow] = None

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    line = None

  Given("a brand operator of brand {string}") { (_: String) => () }

  When("they read a player's activity tab") { () =>
    line = ActivityLines.player(PlayerSuspended("p-real-1", "active", "suspended", "operator", Some("op-1"), None,
      Instant.parse("2026-11-09T08:00:00Z")))
  }

  Then("the real ids are already there, with no re-identification needed inside the brand") { () =>
    assertEquals(line.map(_.playerId), Some("p-real-1"))
  }

object ReidentificationFeatures:
  // Here, not in the class: GherkinSuite reads these while it is constructed.
  private val Audit = "services/audit's ReidentificationFeatures: the shared audit's re-identification routes"
  private val Elsewhere = Map(
    "a compliance officer with the re-identification role is told who a pseudonym is, and the re-identification is recorded" -> Audit,
    "a compliance officer without the re-identification role is refused, and the refusal is recorded" -> Audit,
    "a real id asked for its pseudonym at a brand by the re-identification role is answered or issued, and the lookup is recorded" -> Audit
  )

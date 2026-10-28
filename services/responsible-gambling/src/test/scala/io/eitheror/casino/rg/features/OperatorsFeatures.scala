package io.eitheror.casino.rg.features

import io.eitheror.casino.contract.facts.{AuditRecord, CasinoPlayers, PlatformAudit}
import io.eitheror.casino.rg.Json.*

import java.time.Duration
import java.time.temporal.ChronoUnit
import scala.concurrent.duration.*

/** features/responsible-gambling/operators.feature (US6, FR-004, FR-025 to FR-029). */
class OperatorsFeatures extends RgFeatures("operators.feature"):

  private val Daily = "DEPOSIT_DAILY"
  private var caseId = ""
  private val Note   = "the player called, worried about their spending"
  private val Reason = "agreed a plan with the player"

  Given("the player has a pending change raising a limit") { () =>
    putLimit(Daily, "10000"): Unit
    assertEquals(pending(putLimit(Daily, "50000"), Daily).size, 1)
  }

  When("an operator sets that limit") { () => operatorLimit(Daily, "70000"): Unit }

  Then("it applies at once") { () => assertEquals(limitValue(last, Daily), Some(70_000L), last.text) }

  Then("the pending change is cancelled") { () => assertEquals(pending(last, Daily), Vector.empty) }

  Then("the history names the operator") { () =>
    assertEquals(history().head.text("actor"), Some("operator"))
    val audit = eventually("the audit record", 5.seconds)(audits.find(_.action == "limit-set"))
    assertEquals(audit.actorId, "op-1")
  }

  Given("a brand whose minimum session limit is {string} minutes") { (m: String) =>
    assertEquals(changeSettings("minSessionMinutes" -> m.toInt).status, 200)
  }

  When("an operator sets the player's session limit to {string}") { (v: String) => operatorLimit("SESSION_MINUTES", v, None): Unit }

  Then("it is accepted") { () =>
    assertEquals(last.status, 200, last.text)
    assertEquals(limitValue(last, "SESSION_MINUTES"), Some(30L))
  }

  Given("the player has a daily deposit limit") { () => putLimit(Daily, "10000"): Unit }

  When("an operator removes it") { () =>
    last = kit.request("DELETE", kit.operatorPath(s"/$player/limits/$Daily?currency=$currency"), None, kit.asOperator())
  }

  Then("no daily deposit limit applies at once") { () =>
    assertEquals(last.status, 200, last.text)
    assertEquals((limitValue(status(), Daily), pending(status(), Daily)), (None, Vector.empty))
  }

  Given("a case an operator opened on the player with a note") { () =>
    last = kit.json("POST", kit.operatorPath(s"/$player/cases"), json("caseType" -> "account_block", "note" -> Note), kit.asOperator())
    assertEquals(last.status, 201, last.text)
    caseId = last.str("caseId")
  }

  When("they close it with a reason") { () =>
    last = kit.json("POST", kit.operatorPath(s"/$player/cases/$caseId/close"), json("reason" -> Reason), kit.asOperator())
    assertEquals(last.status, 200, last.text)
  }

  Then("the case shows the note and the reason") { () =>
    val c = full().objects("cases").find(_.text("caseId").contains(caseId)).get
    assertEquals((c.objects("notes").flatMap(_.text("text")), c.text("closeNote"), c.text("status")), (Vector(Note), Some(Reason), Some("closed")))
  }

  Then("the note is personal data under the player") { () =>
    // Personal under the player: erasing the player blanks it, and it never leaves in a fact or an audit record.
    assertEquals(kit.request("POST", kit.servicePath(s"/erasure/players/$player"), None, kit.asService("players")).status, 200)
    val text = kit.request("GET", kit.operatorPath(s"/$player"), None, kit.asOperator()).text
    assert(!text.contains(Note) && !text.contains(Reason), text)
    val published = (kit.broker.publishedTo(CasinoPlayers.name) ++ kit.broker.publishedTo(PlatformAudit.name)).map(d => String(d.message.payload, "UTF-8"))
    assert(!published.exists(p => p.contains(Note) || p.contains(Reason)))
  }

  Given("the player's protections") { () => putLimit(Daily, "10000"): Unit }

  When("an operator changes any of them") { () =>
    operatorLimit(Daily, "5000"): Unit
    assertEquals(kit.json("PUT", kit.operatorPath(s"/$player/cooling-off"),
      json("until" -> kit.now().plus(Duration.ofDays(2)).truncatedTo(ChronoUnit.SECONDS).toString), kit.asOperator()).status, 200)
    assertEquals(operatorExclude("permanent").status, 200)
  }

  Then("an audit record names the operator, the player, the action and the before and after") { () =>
    val records = eventually("three records", 5.seconds)(Option.when(audits.size >= 3)(audits))
    assertEquals(records.map(_.action).toSet, Set("limit-set", "break-set", "exclude"))
    assert(records.forall(r => r.actorKind == "operator" && r.actorId == "op-1" && r.subjectKind == "player" && r.subjectId == player))
    val limit = records.find(_.action == "limit-set").get.changes.head
    assertEquals((limit.before, limit.after), (Some("10000"), Some("5000")))
  }

  private var sessionLimitBefore: Option[Long] = None

  Given("a brand setting of a minimum session limit") { () =>
    assertEquals(changeSettings("minSessionMinutes" -> 60).status, 200)
    sessionLimitBefore = limitValue(putLimit("SESSION_MINUTES", "60", None), "SESSION_MINUTES")
    assertEquals(sessionLimitBefore, Some(60L))
  }

  When("an operator changes it") { () => assertEquals(changeSettings("minSessionMinutes" -> 90).json.long("minSessionMinutes"), Some(90L)) }

  Then("it binds the next player change") { () =>
    val r = putLimit("SESSION_MINUTES", "70", None)
    assertEquals((r.str("error"), r.json.long("minimumMinutes")), ("below_minimum_session_minutes", Some(90L)))
  }

  Then("no existing limit is altered") { () =>
    assertEquals(limitValue(status(), "SESSION_MINUTES"), sessionLimitBefore)
    val audit = eventually("the settings audit", 5.seconds)(kit.facts(PlatformAudit).collectFirst {
      case r: AuditRecord if r.action == "settings-change" && r.changes.exists(c => c.after.contains("90")) => r
    })
    assertEquals((audit.subjectKind, audit.subjectId, audit.actorId), ("brand", kit.slug, "op-1"))
  }

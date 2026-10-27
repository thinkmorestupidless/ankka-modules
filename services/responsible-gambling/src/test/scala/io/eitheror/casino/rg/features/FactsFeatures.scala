package io.eitheror.casino.rg.features

import io.eitheror.casino.contract.facts.{CasinoPlayers, PlatformAudit, RgCaseClosed, RgCaseOpened, RgLimitApplied}
import io.eitheror.casino.rg.Json.*
import io.eitheror.casino.rg.timers.TimerNames

import java.time.{Duration, Instant}
import java.time.temporal.ChronoUnit

/**
 * features/responsible-gambling/facts.feature (US7, FR-022, FR-030 to FR-032). `player_self_excluded` is
 * players' fact (008), produced on the call this service makes; what engagement sends is 014's, which reads
 * the `rg_case_opened` asserted here.
 */
class FactsFeatures extends RgFeatures("facts.feature"):

  private val Daily = "DEPOSIT_DAILY"
  private val Note  = "the player mentioned their partner by name"

  private var expected: (String, Option[Long], String) = ("", None, "")
  private var until: Option[Instant] = None
  private var caseType = ""

  private def appliesAt: Instant = Instant.parse(pending(status(), Daily).head.text("appliesAt").get)

  Given("a limit of the player") { () => assertEquals(limitValue(putLimit(Daily, "10000"), Daily), Some(10_000L)) }

  When("it is applied") { () =>
    putLimit("DEPOSIT_WEEKLY", "20000"): Unit
    expected = ("DEPOSIT_WEEKLY", Some(20_000L), "set")
  }
  When("it is lowered") { () =>
    putLimit(Daily, "5000"): Unit
    expected = (Daily, Some(5_000L), "lowered")
  }
  When("it is raised by timer") { () =>
    putLimit(Daily, "20000"): Unit
    fireAt(pendingTimer(Daily), appliesAt)
    expected = (Daily, Some(20_000L), "applied")
  }
  When("it is removed") { () =>
    removeLimit(Daily): Unit
    fireAt(pendingTimer(Daily), appliesAt)
    expected = (Daily, None, "removed")
  }

  Then("an {string} fact names the player, the limit type, the value or none, who caused it and when") { (name: String) =>
    assertEquals(name, "rg_limit_applied")
    val (t, v, by) = expected
    val f = eventually(s"rg_limit_applied $by")(facts.collectFirst { case f: RgLimitApplied if f.limitType == t && f.causedBy == by => f })
    assertEquals((f.playerId, f.value, f.currency), (player, v, Some(currency)))
    assert(near(f.occurredAt, kit.now()), f.toString)
  }

  Given("the player is active") { () => assertEquals(mayLogIn().str("decision"), "allowed") }

  When("a break is set") { () =>
    val at = kit.now().plus(Duration.ofDays(2)).truncatedTo(ChronoUnit.SECONDS)
    assertEquals(setBreak(at).status, 200)
    until = Some(at)
    caseType = "cool_off"
  }

  When("an exclusion is applied") { () =>
    assertEquals(excludePermanently().status, 200)
    until = None
    caseType = "self_exclusion"
  }

  Then("an {string} fact names the type, the trigger and the end if any") { (name: String) =>
    assertEquals(name, "rg_case_opened")
    val f = eventually("rg_case_opened")(facts.collectFirst { case f: RgCaseOpened => f })
    assertEquals((f.caseType, f.trigger, f.closesAt), (caseType, "player", until))
  }

  private var breakUntil: Instant = Instant.EPOCH
  private var operatorCase = ""

  Given("an open case of the player") { () =>
    // A break's case ends expired or lifted; an operator's case ends closed. Both are opened, and each reason ends its own.
    breakUntil = kit.now().plus(Duration.ofDays(2)).truncatedTo(ChronoUnit.SECONDS)
    assertEquals(setBreak(breakUntil).status, 200)
    val c = kit.json("POST", kit.operatorPath(s"/$player/cases"), json("caseType" -> "account_block", "note" -> "opened"), kit.asOperator())
    assertEquals(c.status, 201, c.text)
    operatorCase = c.str("caseId")
  }

  When("it ends as expired") { () => fireAt(TimerNames.break(player), breakUntil) }
  When("it ends as lifted") { () => assertEquals(operatorLift("spoke with the player").status, 200) }
  When("it ends as closed") { () =>
    assertEquals(kit.json("POST", kit.operatorPath(s"/$player/cases/$operatorCase/close"), json("reason" -> "resolved"), kit.asOperator()).status, 200)
  }

  Then("an {string} fact names the reason {string}") { (name: String, reason: String) =>
    assertEquals(name, "rg_case_closed")
    eventually(s"rg_case_closed $reason")(facts.collectFirst { case f: RgCaseClosed if f.closure == reason => f })
    assertEquals(facts.count { case f: RgCaseClosed => true; case _ => false }, 1, "only the case that ended")
  }

  When("they exclude themselves") { () => assertEquals(excludePermanently().status, 200) }

  Then("a {string} fact is produced for the player") { (name: String) =>
    assertEquals(name, "player_self_excluded")
    // players produces it when this service tells it (008 FR-033); the call is what this service owes.
    val call = eventually("players told")(kit.players.callsFor(player).find(_.method == "PUT"))
    assert(call.body.contains("\"at\""), call.body)
  }

  Then("the brand's engagement sends them nothing but the confirmation") { () =>
    // engagement (014) suppresses on the case; the confirmation is its own transactional send.
    val f = eventually("rg_case_opened")(facts.collectFirst { case f: RgCaseOpened => f })
    assertEquals((f.caseType, f.permanent), ("self_exclusion", Some(true)))
  }

  Given("the player is refused at sign-in by the national register") { () => () }

  When("the realm reports it") { () =>
    assertEquals(kit.json("POST", kit.servicePath("/gamstop-refusal"), json("playerId" -> player, "result" -> "excluded"),
      kit.asService("players")).status, 204)
  }

  Then("a case of type self-exclusion with the trigger third party is recorded") { () =>
    val c = full().objects("cases")
    assertEquals(c.map(n => (n.text("caseType"), n.text("trigger"))), Vector((Some("self_exclusion"), Some("third_party"))))
    assertEquals(mayLogIn().str("decision"), "allowed", "the register refuses at the realm; it excludes nothing here")
  }

  Then("it is exported with the rest") { () =>
    val exported = kit.request("GET", kit.servicePath(s"/exports/players/$player"), None, kit.asService("players"))
    assertEquals(exported.status, 200)
    assert(exported.text.contains("third_party"), exported.text)
  }

  Given("the player is erased") { () =>
    putLimit(Daily, "10000"): Unit
    val c = kit.json("POST", kit.operatorPath(s"/$player/cases"), json("caseType" -> "account_block", "note" -> Note), kit.asOperator())
    assertEquals(c.status, 201, c.text)
    eventually("the facts")(Option.when(facts.size >= 2)(()))
    assertEquals(kit.request("POST", kit.servicePath(s"/erasure/players/$player"), None, kit.asService("players")).status, 200)
  }

  private var read: Vector[String] = Vector.empty

  When("their protection facts are read") { () =>
    read = (kit.broker.publishedTo(CasinoPlayers.name) ++ kit.broker.publishedTo(PlatformAudit.name)).map(d => String(d.message.payload, "UTF-8")).toVector :+
      kit.request("GET", kit.servicePath(s"/exports/players/$player"), None, kit.asService("players")).text
  }

  Then("amounts, dates, types and statuses are readable") { () =>
    val limit = facts.collectFirst { case f: RgLimitApplied => f }.get
    assertEquals((limit.value, limit.limitType), (Some(10_000L), Daily))
    val opened = facts.collectFirst { case f: RgCaseOpened => f }.get
    assertEquals(opened.caseType, "account_block")
    assert(read.last.contains("\"status\":\"open\""), read.last)
  }

  Then("any note is erased") { () => assert(!read.exists(_.contains(Note)), "a note outlives the erasure") }

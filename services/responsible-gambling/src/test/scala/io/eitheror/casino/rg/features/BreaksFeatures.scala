package io.eitheror.casino.rg.features

import io.eitheror.casino.contract.facts.{RgCaseClosed, RgCaseOpened}
import io.eitheror.casino.rg.Json.*
import io.eitheror.casino.rg.it.Reply
import io.eitheror.casino.rg.timers.TimerNames

import java.time.{Duration, Instant}
import java.time.temporal.ChronoUnit

/**
 * features/responsible-gambling/breaks.feature (US4, FR-012 to FR-021). What `players` does with an exclusion
 * (the player status, ending game sessions) is 008's; here it is the call `players` receives.
 */
class BreaksFeatures extends RgFeatures("breaks.feature"):

  private var until: Instant = Instant.EPOCH
  private var reinstatesAt: Instant = Instant.EPOCH
  private var asked: Vector[Reply] = Vector.empty

  private def cases = full().objects("cases")
  private def opened = facts.collect { case f: RgCaseOpened => f }
  private def closed = facts.collect { case f: RgCaseClosed => f }

  private def breakFor(at: Instant): Unit =
    until = at
    assertEquals(setBreak(until).status, 200, last.text)

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    lifted = false

  Given("the player is active") { () => assertEquals(mayLogIn().str("decision"), "allowed") }

  When("they set a break until {string}") { (date: String) => breakFor(dateOf(date)) }

  Then("the break applies at once") { () =>
    assertEquals((last.json.bool("isCoolingOff"), mayLogIn().str("decision")), (Some(true), "cooling_off"))
  }

  Then("a case of type break opens with that end") { () =>
    val c = cases.filter(_.text("status").contains("open"))
    assertEquals(c.map(n => (n.text("caseType"), n.text("closesAt").map(Instant.parse))), Vector((Some("cool_off"), Some(until))))
  }

  Then("one {string} fact is published for the case") { (name: String) =>
    assertEquals(name, "rg_case_opened")
    eventually("the fact")(opened.headOption)
    assertEquals(opened.size, 1)
  }

  Given("the player is on a break") { () => breakFor(kit.now().plus(Duration.ofDays(2)).truncatedTo(ChronoUnit.SECONDS)) }

  When("they ask to end or shorten it") { () => setBreak(kit.now().plus(Duration.ofHours(2))): Unit }

  Then("it is refused, because only an operator may lift a break") { () =>
    assertEquals((last.status, last.str("error")), (409, "break_active"))
    assertEquals(mayLogIn().str("decision"), "cooling_off")
  }

  Given("the player is on a break until {string}") { (date: String) => breakFor(dateOf(date)) }

  When("that moment passes") { () => fireAt(TimerNames.break(player), until) }

  Then("the break ends by itself") { () =>
    eventually("the break ended")(Option.when(state.break.isEmpty)(()))
    assertEquals(mayLogIn().str("decision"), "allowed")
  }

  Then("the case closes as expired") { () =>
    assertEquals(eventually("the case closed")(closed.headOption).closure, "expired")
  }

  Then("nothing else about the player changes") { () =>
    val s = state
    assertEquals((s.limits, s.exclusion, s.pending), (Map.empty, None, Map.empty))
    assertEquals(kit.players.callsFor(player), Vector.empty, "a break is no player status")
  }

  When("an operator lifts it with a reason") { () =>
    assertEquals(operatorLift("spoke with the player").status, 200, last.text)
    lifted = true
  }

  // A Then after an operator's lift, a When on a suspended player's break: one phrase, so one binding.
  private var lifted = false

  Then("the break ends") { () =>
    if lifted then assertEquals(last.json.bool("isCoolingOff"), Some(false))
    else fireAt(TimerNames.break(player), until)
  }

  Then("the case closes as lifted, naming the operator and the reason") { () =>
    val c = cases.head
    assertEquals((c.text("status"), c.text("closeReason"), c.text("closeNote")), (Some("closed"), Some("lifted"), Some("spoke with the player")))
    val audit = eventually("the audit record")(audits.find(_.action == "break-lift"))
    assertEquals((audit.actorKind, audit.actorId), ("operator", "op-1"))
  }

  Then("the player may sign in") { () => assertEquals(mayLogIn().str("decision"), "allowed") }

  When("they exclude themselves permanently") { () => assertEquals(excludePermanently().status, 200) }

  Then("they are excluded at once") { () => assertEquals(mayLogIn().str("decision"), "excluded") }

  Then("the brand's player records set their player status") { () =>
    val call = eventually("players told")(kit.players.callsFor(player).find(_.method == "PUT"))
    assert(!call.body.contains("until"), call.body)
  }

  Then("a case of type self-exclusion opens") { () =>
    assertEquals(cases.map(_.text("caseType")), Vector(Some("self_exclusion")))
  }

  Then("every open game session of theirs is ended") { () =>
    // players ends them on this call (008 FR-033, `PUT …/exclusion`); one call, however many sessions.
    eventually("players told once")(Option.when(kit.players.callsFor(player).count(_.method == "PUT") == 1)(()))
  }

  When("an operator excludes them for {string} days") { (days: String) => operatorExclude(s"${days}d"): Unit }

  When("an operator excludes them permanently") { () => operatorExclude("permanent"): Unit }

  Then("it is refused naming the minimum of {string} days") { (days: String) =>
    assertEquals((last.status, last.str("error"), last.json.long("minimumDays")), (400, "period_too_short", Some(days.toLong)))
  }

  Then("it is accepted") { () => assertEquals(last.status, 200, last.text) }

  Given("the player is excluded for {string} days") { (days: String) =>
    assertEquals(operatorExclude(s"${days}d").status, 200)
    until = kit.now().plus(Duration.ofDays(days.toLong))
  }

  When("the player asks to return before the period ends") { () => askToReturn(): Unit }

  Then("it is refused naming the end of the period") { () =>
    assertEquals((last.status, last.str("error")), (409, "exclusion_not_ended"))
    assert(near(Instant.parse(last.str("endsAt")), until), last.text)
  }

  Given("the player is excluded for {string} days and the period has ended") { (days: String) =>
    assertEquals(operatorExclude(s"${days}d").status, 200)
    kit.advance(Duration.ofDays(days.toLong + 1))
  }

  When("the player asks to return") { () => askToReturn(): Unit }

  Then("it is accepted and the player is still excluded") { () =>
    assertEquals(last.status, 202, last.text)
    reinstatesAt = Instant.parse(last.str("reinstatesAt"))
    assertEquals(mayLogIn().str("decision"), "excluded")
  }

  Then("{string} hours later the exclusion and the break are cleared, the case closes and the player may sign in") { (hours: String) =>
    assert(near(reinstatesAt, kit.now().plus(Duration.ofHours(hours.toLong))))
    fireAt(TimerNames.returning(player), reinstatesAt)
    eventually("reinstated")(Option.when(mayLogIn().str("decision") == "allowed")(()))
    val s = state
    assertEquals((s.exclusion, s.break), (None, None))
    eventually("the case closed")(closed.find(_.caseType == "self_exclusion"))
    eventually("players told")(Option.when(kit.players.callsFor(player).lastOption.exists(_.method == "DELETE"))(()))
  }

  When("an operator records the player's request to return") { () =>
    last = kit.request("POST", kit.operatorPath(s"/$player/return"), None, kit.asOperator())
    assertEquals(last.status, 202, last.text)
    reinstatesAt = Instant.parse(last.str("reinstatesAt"))
  }

  Then("the player is reinstated {string} hours later and not before") { (hours: String) =>
    assert(near(reinstatesAt, kit.now().plus(Duration.ofHours(hours.toLong))))
    kit.advance(Duration.ofHours(hours.toLong - 1))
    assertEquals(mayLogIn().str("decision"), "excluded")
    fireAt(TimerNames.returning(player), reinstatesAt)
    eventually("reinstated")(Option.when(mayLogIn().str("decision") == "allowed")(()))
  }

  Given("the player is permanently self-excluded") { () => assertEquals(excludePermanently().status, 200) }

  Then("it is refused") { () => assertEquals((last.status, last.str("error")), (409, "exclusion_permanent")) }

  Given("the player is both excluded and on a break") { () =>
    breakFor(kit.now().plus(Duration.ofDays(2)).truncatedTo(ChronoUnit.SECONDS))
    assertEquals(operatorExclude("permanent").status, 200, last.text)
  }

  When("anything asks about them") { () => asked = Vector(mayLogIn(), mayDeposit(100), mayBet(100), mayPlay()) }

  Then("the exclusion is the answer") { () =>
    assertEquals(asked.take(3).map(_.str("decision")), Vector.fill(3)("excluded"))
    assertEquals((asked(3).status, asked(3).str("reason")), (403, "self_excluded")) // may-play's word (contracts/routes.md)
  }

  Given("the player is suspended and on a break") { () =>
    // Suspension is players' (008); this service holds only the break and tells players nothing of it.
    breakFor(kit.now().plus(Duration.ofDays(2)).truncatedTo(ChronoUnit.SECONDS))
  }

  Then("they are still suspended, because ending a break changes no player status") { () =>
    eventually("the case closed")(closed.headOption)
    assertEquals(kit.players.callsFor(player), Vector.empty)
  }

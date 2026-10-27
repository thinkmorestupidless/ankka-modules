package io.eitheror.casino.rg.features

import io.eitheror.casino.contract.facts.RgLimitApplied
import io.eitheror.casino.rg.Json.*

import java.time.{Duration, Instant}

/** features/responsible-gambling/limits.feature (US1, FR-001 to FR-006, FR-027). */
class LimitsFeatures extends RgFeatures("limits.feature"):

  private val Daily = "DEPOSIT_DAILY"
  private var earlierAppliesAt: Option[Instant] = None

  private def appliesAt(t: String): Instant = Instant.parse(pending(status(), t).head.text("appliesAt").get)

  Given("the player has no deposit limit") { () => assert(status().json.objects("limits").isEmpty) }

  When("they set a daily deposit limit of {string}") { (v: String) => putLimit(Daily, minor(v).toString): Unit }

  Then("it applies at once") { () =>
    assertEquals(last.status, 200, last.text)
    assert(limitValue(last, Daily).isDefined, last.text)
  }

  Then("their profile shows it with the amount deposited in the last {string} hours") { (hours: String) =>
    assertEquals(hours, "24")
    val used = status().json.objects("usedAmounts").find(u => u.text("limitType").contains(Daily) && u.text("currency").contains(currency))
    assert(used.flatMap(_.text("usedAmount")).isDefined, "the day's deposits are shown beside the daily limit")
  }

  Given("a daily deposit limit of {string}") { (v: String) =>
    assertEquals(limitValue(putLimit(Daily, minor(v).toString), Daily), Some(minor(v)))
  }

  When("the player sets it to {string}") { (v: String) => putLimit(Daily, minor(v).toString): Unit }

  Then("{string} applies at once") { (v: String) => assertEquals(limitValue(last, Daily), Some(minor(v))) }

  Then("any pending change for that limit is cancelled") { () =>
    assert(pending(status(), Daily).isEmpty)
    eventually("the pending timer deleted")(Option.when(!kit.timerExists(pendingTimer(Daily)))(()))
  }

  Then("{string} still applies") { (v: String) =>
    assertEquals(last.status, 200, last.text)
    assertEquals(limitValue(status(), Daily), Some(minor(v)))
  }

  Then("the profile shows {string} as a pending change with a time {string} hours from now") { (v: String, hours: String) =>
    val p = pending(status(), Daily)
    assertEquals(p.flatMap(_.text("pendingValue")), Vector(minor(v).toString))
    assert(near(appliesAt(Daily), kit.now().plus(Duration.ofHours(hours.toLong))))
  }

  Then("at that time {string} applies and the pending change is gone") { (v: String) =>
    fireAt(pendingTimer(Daily), appliesAt(Daily))
    eventually("the raise applied") {
      val r = status()
      Option.when(limitValue(r, Daily).contains(minor(v)) && pending(r, Daily).isEmpty)(())
    }
  }

  When("the player removes it") { () => removeLimit(Daily): Unit }

  Then("the removal is a pending change {string} hours out") { (hours: String) =>
    val p = pending(status(), Daily)
    assertEquals(p.size, 1)
    assert(p.head.get("pendingValue").isNull, "a removal pends no value")
    assert(near(appliesAt(Daily), kit.now().plus(Duration.ofHours(hours.toLong))))
  }

  Then("at that time no daily deposit limit applies") { () =>
    fireAt(pendingTimer(Daily), appliesAt(Daily))
    eventually("the removal applied")(Option.when(limitValue(status(), Daily).isEmpty)(()))
  }

  Given("a pending change raising a daily deposit limit to {string}") { (v: String) =>
    putLimit(Daily, "10000"): Unit
    putLimit(Daily, minor(v).toString): Unit
    earlierAppliesAt = Some(appliesAt(Daily))
    kit.advance(Duration.ofHours(1))
  }

  Then("the pending change becomes {string} at a new time {string} hours from now") { (v: String, hours: String) =>
    assertEquals(pending(status(), Daily).flatMap(_.text("pendingValue")), Vector(minor(v).toString))
    assert(near(appliesAt(Daily), kit.now().plus(Duration.ofHours(hours.toLong))))
    assert(appliesAt(Daily).isAfter(earlierAppliesAt.get), "the day restarts")
  }

  Then("the earlier pending change is gone") { () => assertEquals(pending(status(), Daily).size, 1) }

  Given("a brand whose minimum session limit is {string} minutes") { (m: String) =>
    assertEquals(changeSettings("minSessionMinutes" -> m.toInt).status, 200)
  }

  When("the player sets a session limit of {string}") { (v: String) => putLimit("SESSION_MINUTES", v, None): Unit }

  Then("it is refused naming the minimum") { () =>
    assertEquals((last.status, last.str("error"), last.json.long("minimumMinutes")), (400, "below_minimum_session_minutes", Some(60L)))
  }

  Then("it is accepted") { () => assertEquals(last.status, 200, last.text) }

  Then("it is refused as not a positive whole number") { () => assertEquals((last.status, last.str("error")), (400, "invalid_value"), last.text) }

  Given("a weekly loss limit of {string}") { (v: String) =>
    assertEquals(limitValue(putLimit("LOSS_WEEKLY", minor(v).toString), "LOSS_WEEKLY"), Some(minor(v)))
  }

  When("the player reads their profile") { () => last = status() }

  Then("it shows the limit and the losses of the last {string} days") { (days: String) =>
    assertEquals(days, "7")
    assert(limitValue(last, "LOSS_WEEKLY").isDefined)
    assert(last.json.objects("usedAmounts").exists(u => u.text("limitType").contains("LOSS_WEEKLY") && u.text("usedAmount").isDefined))
  }

  Then("a bet that would take the week's losses past {string} is refused as {string} naming the remaining allowance") {
    (v: String, decision: String) =>
      val lost = minor(v) - 5_000
      val a    = mayBet(5_001, Some((0L, lost, lost))).json
      assertEquals((a.text("decision"), a.text("period"), a.long("remaining")), (Some(decision), Some("weekly"), Some(5_000L)))
  }

  Then("a bet within it is allowed") { () =>
    val lost = limitValue(status(), "LOSS_WEEKLY").get - 5_000
    assertEquals(mayBet(5_000, Some((0L, lost, lost))).str("decision"), "allowed")
  }

  Given("any limit of the player") { () => putLimit(Daily, "10000"): Unit }

  When("they change it") { () => putLimit(Daily, "5000"): Unit }

  Then("the history shows the change with the old and new value, who made it and when") { () =>
    val h = history().head
    assertEquals((h.text("previousValue"), h.text("newValue"), h.text("actor")), (Some("10000"), Some("5000"), Some("player")))
    assert(h.text("occurredAt").isDefined)
  }

  Then("one {string} fact names the change") { (name: String) =>
    assertEquals(name, "rg_limit_applied")
    eventually("the fact")(facts.collectFirst { case f: RgLimitApplied if f.value.contains(5_000L) => f })
    assertEquals(facts.count { case f: RgLimitApplied => f.value.contains(5_000L); case _ => false }, 1)
  }

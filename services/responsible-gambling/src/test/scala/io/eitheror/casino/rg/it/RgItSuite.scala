package io.eitheror.casino.rg.it

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.contract.facts.*
import io.eitheror.casino.contract.money.{Currency, MinorUnits, SignedMinorUnits}
import io.eitheror.casino.rg.Json
import io.eitheror.casino.rg.Json.*
import io.eitheror.casino.rg.timers.TimerNames
import io.eitheror.casino.rg.profile.LimitKey

import java.time.{Duration, Instant}
import java.util.UUID
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** The service over Postgres: the stories end to end through the three endpoints (T020, T031, T032, T037). */
class RgItSuite extends munit.FunSuite with LogCapturing:
  override val munitTimeout = 3.minutes

  private lazy val kit = SharedKit.kit
  override def beforeEach(context: BeforeEach): Unit = kit.reset()

  private def player() = s"p-${UUID.randomUUID().toString.take(8)}"

  private def putLimit(p: String, t: String, value: String, currency: Option[String] = Some("EUR")) =
    kit.json("PUT", kit.playerPath(s"/$p/limits/$t"), Json.obj("value" -> value, "currency" -> currency), kit.asPlayer(p))

  private def mayDeposit(p: String, amount: Long, last24h: Long = 0) =
    kit.json("POST", kit.servicePath("/may-deposit"),
      Json.obj("playerId" -> p, "currency" -> "EUR", "amount" -> amount, "last24h" -> last24h, "last7d" -> last24h, "last30d" -> last24h),
      kit.asService("wallet"))

  private def mayLogIn(p: String) = kit.json("POST", kit.servicePath("/may-log-in"), Json.obj("playerId" -> p), kit.asService("players"))

  private def limitValue(r: Reply, t: String): Option[Long] =
    r.json.objects("limits").find(_.text("limitType").contains(t)).flatMap(_.long("value"))

  // ── US1: limits ───────────────────────────────────────────────────────────

  test("a player sets a limit, raises it (staged 24 h with its timer), and the timer applies it"):
    val p = player()
    assertEquals(limitValue(putLimit(p, "DEPOSIT_DAILY", "10000"), "DEPOSIT_DAILY"), Some(10_000L))
    val raised = putLimit(p, "DEPOSIT_DAILY", "50000")
    assertEquals(raised.status, 200)
    assertEquals(limitValue(raised, "DEPOSIT_DAILY"), Some(10_000L))
    assertEquals(raised.json.objects("pendingChanges").flatMap(_.text("pendingValue")), Vector("50000"))
    val name = TimerNames.pending(p, LimitKey("DEPOSIT_DAILY", Some("EUR")))
    kit.eventually("the pending timer")(Option.when(kit.timerExists(name))(()))
    kit.fireTimer(name)
    kit.eventually("the raise applied", 30.seconds) {
      val r = kit.request("GET", kit.playerPath(s"/$p/limits"), None, kit.asPlayer(p))
      Option.when(limitValue(r, "DEPOSIT_DAILY").contains(50_000L) && r.json.objects("pendingChanges").isEmpty)(())
    }
    val applied = kit.eventually("rg_limit_applied applied") {
      kit.facts(CasinoPlayers).collectFirst { case f: RgLimitApplied if f.playerId == p && f.causedBy == "applied" => f }
    }
    assertEquals((applied.value, applied.currency, applied.period), (Some(50_000L), Some("EUR"), Some("daily")))

  test("lowering cancels the pending change and its timer"):
    val p = player()
    putLimit(p, "DEPOSIT_DAILY", "10000"): Unit
    putLimit(p, "DEPOSIT_DAILY", "50000"): Unit
    val name = TimerNames.pending(p, LimitKey("DEPOSIT_DAILY", Some("EUR")))
    kit.eventually("the pending timer")(Option.when(kit.timerExists(name))(()))
    val lowered = putLimit(p, "DEPOSIT_DAILY", "5000")
    assertEquals((limitValue(lowered, "DEPOSIT_DAILY"), lowered.json.objects("pendingChanges").size), (Some(5_000L), 0))
    kit.eventually("the timer deleted")(Option.when(!kit.timerExists(name))(()))

  test("refusals: another player's path, an unknown type, a session limit below the brand's minimum"):
    val p = player()
    assertEquals(kit.request("GET", kit.playerPath(s"/someone-else/limits"), None, kit.asPlayer(p)).str("error"), "player_id_mismatch")
    assertEquals(putLimit(p, "WAGER_DAILY", "5").str("error"), "unknown_limit_type")
    val below = putLimit(p, "SESSION_MINUTES", "30", None)
    assertEquals((below.status, below.str("error"), below.json.long("minimumMinutes")), (400, "below_minimum_session_minutes", Some(60L)))
    assertEquals(kit.request("GET", kit.playerPath(s"/$p/limits"), None, kit.asOperator()).status, 403)

  test("the history lists every change, newest first, with its actor"):
    val p = player()
    putLimit(p, "DEPOSIT_DAILY", "10000"): Unit
    putLimit(p, "DEPOSIT_DAILY", "20000"): Unit
    val h = kit.request("GET", kit.playerPath(s"/$p/history"), None, kit.asPlayer(p)).json
    assertEquals(h.elements.asScala.toVector.flatMap(_.text("eventType")), Vector("deposit_limit_change_requested", "deposit_limit_set"))

  // ── US2: deposits ─────────────────────────────────────────────────────────

  test("may-deposit: exactly the remainder allowed, a minor unit over refused naming the limit, in the wallet's shape"):
    val p = player()
    putLimit(p, "DEPOSIT_DAILY", "10000"): Unit
    assertEquals(mayDeposit(p, 4_000, 6_000).str("decision"), "allowed")
    val over = mayDeposit(p, 4_001, 6_000).json
    assertEquals((over.text("decision"), over.text("period"), over.long("limit"), over.long("used"), over.long("remaining")),
      (Some("over_limit"), Some("daily"), Some(10_000L), Some(6_000L), Some(4_000L)))

  test("the checks admit only their callers"):
    val p = player()
    assertEquals(kit.json("POST", kit.servicePath("/may-deposit"), Json.obj("playerId" -> p), kit.asService("rewards")).status, 403)
    assertEquals(mayLogIn(p).str("decision"), "allowed")

  // ── US3, US4: breaks and exclusions ───────────────────────────────────────

  test("a break refuses every check, names its end, cannot be shortened by the player, and ends by its timer"):
    val p     = player()
    val until = kit.now().plus(Duration.ofDays(2))
    assertEquals(kit.json("PUT", kit.playerPath(s"/$p/cooling-off"), Json.obj("until" -> until.toString), kit.asPlayer(p)).status, 200)
    assertEquals(mayLogIn(p).str("decision"), "cooling_off")
    assertEquals(Instant.parse(mayLogIn(p).str("endsAt")), until)
    assertEquals(mayDeposit(p, 1).str("decision"), "cooling_off")
    val play = kit.request("GET", kit.servicePath(s"/$p/may-play"), None, kit.asService("game-provider"))
    assertEquals((play.status, play.str("reason")), (403, "cooling_off"))
    val bet = kit.json("POST", kit.servicePath("/may-bet"), Json.obj("playerId" -> p, "currency" -> "EUR", "amount" -> 100), kit.asService("game-provider"))
    assertEquals(bet.str("decision"), "cooling_off")
    val shorter = kit.json("PUT", kit.playerPath(s"/$p/cooling-off"), Json.obj("until" -> kit.now().plus(Duration.ofHours(2)).toString), kit.asPlayer(p))
    assertEquals((shorter.status, shorter.str("error")), (409, "break_active"))
    kit.eventually("the break timer")(Option.when(kit.timerExists(TimerNames.break(p)))(()))
    kit.advance(Duration.ofDays(2).plusSeconds(1))
    kit.fireTimer(TimerNames.break(p))
    val closed = kit.eventually("the case closed expired") {
      kit.facts(CasinoPlayers).collectFirst { case f: RgCaseClosed if f.playerId == p => f }
    }
    assertEquals((closed.caseType, closed.closure), ("cool_off", "expired"))
    assertEquals(mayLogIn(p).str("decision"), "allowed")

  test("an operator lifts a break with a reason; its case closes lifted and the reason is in no fact or audit"):
    val p = player()
    kit.json("PUT", kit.playerPath(s"/$p/cooling-off"), Json.obj("until" -> kit.now().plus(Duration.ofDays(2)).toString), kit.asPlayer(p)): Unit
    val secret = s"spoke to the player's partner ${UUID.randomUUID()}"
    assertEquals(kit.json("POST", kit.operatorPath(s"/$p/cooling-off/lift"), Json.obj("reason" -> secret), kit.asOperator()).status, 200)
    assertEquals(mayLogIn(p).str("decision"), "allowed")
    kit.eventually("the case closed lifted")(kit.facts(CasinoPlayers).collectFirst { case f: RgCaseClosed if f.playerId == p && f.closure == "lifted" => f })
    val audit = kit.eventually("the audit record")(kit.facts(PlatformAudit).collectFirst { case r: AuditRecord if r.subjectId == p && r.action == "break-lift" => r })
    assertEquals(audit.actorId, "op-1")
    val published = (kit.broker.publishedTo(CasinoPlayers.name) ++ kit.broker.publishedTo(PlatformAudit.name)).map(d => String(d.message.payload, "UTF-8"))
    assert(!published.exists(_.contains(secret)))

  test("a self-exclusion refuses at once, tells players, and the sync retries until players answers"):
    val p = player()
    kit.players.failing = true
    assertEquals(kit.request("PUT", kit.playerPath(s"/$p/permanent-exclusion"), None, kit.asPlayer(p)).str("status"), "self_excluded")
    assertEquals(mayLogIn(p).str("decision"), "excluded")
    assertEquals(mayDeposit(p, 1).str("decision"), "excluded")
    kit.eventually("two attempts")(Option.when(kit.players.callsFor(p).size >= 2)(()))
    kit.players.failing = false
    kit.eventually("players told") {
      val r = kit.request("GET", kit.operatorPath(s"/$p"), None, kit.asOperator())
      Option.when(kit.players.callsFor(p).lastOption.exists(_.method == "PUT") && r.status == 200)(())
    }
    val opened = kit.eventually("the case opened")(kit.facts(CasinoPlayers).collectFirst { case f: RgCaseOpened if f.playerId == p => f })
    assertEquals((opened.caseType, opened.permanent, opened.closesAt), ("self_exclusion", Some(true), None))
    val ret = kit.request("POST", kit.playerPath(s"/$p/return"), None, kit.asPlayer(p))
    assertEquals((ret.status, ret.str("error")), (409, "exclusion_permanent"))

  test("with players silent past the client's timeout the exclusion still refuses every check, and the sync retries until it answers"):
    val p = player()
    kit.players.silent = true
    assertEquals(kit.request("PUT", kit.playerPath(s"/$p/permanent-exclusion"), None, kit.asPlayer(p)).status, 200)
    assertEquals((mayLogIn(p).str("decision"), mayDeposit(p, 1).str("decision")), ("excluded", "excluded"))
    kit.eventually("two attempts", 60.seconds)(Option.when(kit.players.callsFor(p).size >= 2)(()))
    kit.players.silent = false
    val told = kit.players.callsFor(p).size
    kit.eventually("an answered attempt", 60.seconds)(Option.when(kit.players.callsFor(p).size > told)(()))
    kit.eventually("the sync settled")(Option.when(kit.components.forEventSourcedEntity(com.thinkmorestupidless.ankka.core.EntityId(p))
      .call(io.eitheror.casino.rg.profile.ProfileEntity.get).invoke().playersTold.isEmpty)(()))

  test("a lifted break's timer, firing later, changes nothing"):
    val p     = player()
    val until = kit.now().plus(Duration.ofDays(2))
    kit.json("PUT", kit.playerPath(s"/$p/cooling-off"), Json.obj("until" -> until.toString), kit.asPlayer(p)): Unit
    kit.eventually("the break timer")(Option.when(kit.timerExists(TimerNames.break(p)))(()))
    assertEquals(kit.json("POST", kit.operatorPath(s"/$p/cooling-off/lift"), Json.obj("reason" -> "agreed"), kit.asOperator()).status, 200)
    val closedBefore = kit.eventually("the case closed lifted")(kit.facts(CasinoPlayers).collectFirst { case f: RgCaseClosed if f.playerId == p => f })
    // The lift may or may not delete the timer; if it is still there, it fires on a profile with no break.
    if kit.timerExists(TimerNames.break(p)) then
      kit.advance(Duration.ofDays(2).plusSeconds(1))
      kit.fireTimer(TimerNames.break(p))
      kit.eventually("the timer gone")(Option.when(!kit.timerExists(TimerNames.break(p)))(()))
    assertEquals(kit.facts(CasinoPlayers).collect { case f: RgCaseClosed if f.playerId == p => f }, Vector(closedBefore))
    assertEquals(mayLogIn(p).str("decision"), "allowed")

  test("an operator's period exclusion: returned from only after it ends, reinstated 24 h after the request, players told DELETE"):
    val p = player()
    assertEquals(kit.json("POST", kit.operatorPath(s"/$p/self-exclude"), Json.obj("period" -> "P30D"), kit.asOperator()).str("error"), "period_too_short")
    assertEquals(kit.json("POST", kit.operatorPath(s"/$p/self-exclude"), Json.obj("period" -> "six months"), kit.asOperator()).str("error"), "invalid_period")
    assertEquals(kit.json("POST", kit.operatorPath(s"/$p/self-exclude"), Json.obj("period" -> "183d"), kit.asOperator()).status, 200)
    val early = kit.request("POST", kit.playerPath(s"/$p/return"), None, kit.asPlayer(p))
    assertEquals((early.status, early.str("error")), (409, "exclusion_not_ended"))
    kit.advance(Duration.ofDays(184))
    val asked = kit.request("POST", kit.playerPath(s"/$p/return"), None, kit.asPlayer(p))
    assertEquals(asked.status, 202)
    assertEquals(mayLogIn(p).str("decision"), "excluded")
    kit.eventually("the return timer")(Option.when(kit.timerExists(TimerNames.returning(p)))(()))
    kit.advance(Duration.ofDays(1))
    kit.fireTimer(TimerNames.returning(p))
    kit.eventually("reinstated")(Option.when(mayLogIn(p).str("decision") == "allowed")(()))
    kit.eventually("players told DELETE")(Option.when(kit.players.callsFor(p).lastOption.exists(_.method == "DELETE"))(()))

  // ── US3: play ─────────────────────────────────────────────────────────────

  test("may-play counts today's session minutes from casino.sessions against the session limit"):
    val p = player()
    kit.json("PUT", kit.operatorPath(s"/$p/limits/SESSION_MINUTES"), Json.obj("value" -> "30"), kit.asOperator()): Unit
    val start = kit.now().minus(Duration.ofMinutes(31))
    if start.atOffset(java.time.ZoneOffset.UTC).toLocalDate == kit.now().atOffset(java.time.ZoneOffset.UTC).toLocalDate then
      kit.publish(CasinoSessions, p, GameSessionStarted(p, "gs-1", "game-1", "ps-1", start))
      val play = kit.eventually("the minutes counted") {
        val r = kit.request("GET", kit.servicePath(s"/$p/may-play"), None, kit.asService("game-provider"))
        Option.when(r.status == 403)(r)
      }
      assertEquals((play.str("reason"), play.json.long("limitMinutes"), play.json.long("usedMinutes")), ("session_limit_reached", Some(30L), Some(31L)))

  // ── usage for display ─────────────────────────────────────────────────────

  test("the status shows the deposits money.transactions carried, exactly"):
    val p = player()
    putLimit(p, "DEPOSIT_DAILY", "100000"): Unit
    (1 to 3).foreach { i =>
      kit.publish(MoneyTransactions, s"$p/EUR", DepositConfirmed(p, Currency.from("EUR").toOption.get, s"t-$p-$i",
        MinorUnits.from(1_234L).toOption.get, s"ref-$i", i, SignedMinorUnits(0), MinorUnits.from(0L).toOption.get, kit.now()))
    }
    kit.eventually("the deposits shown") {
      val r = kit.request("GET", kit.playerPath(s"/$p/limits"), None, kit.asPlayer(p))
      r.json.objects("usedAmounts").find(_.text("limitType").contains("DEPOSIT_DAILY")).flatMap(_.text("usedAmount")).filter(_ == "37.02")
    }

  // ── US6: the operator ─────────────────────────────────────────────────────

  test("an operator's limit applies at once and cancels the pending change; another brand's operator is refused"):
    val p = player()
    putLimit(p, "DEPOSIT_DAILY", "10000"): Unit
    putLimit(p, "DEPOSIT_DAILY", "50000"): Unit
    val r = kit.json("PUT", kit.operatorPath(s"/$p/limits/DEPOSIT_DAILY"), Json.obj("value" -> "70000", "currency" -> "EUR"), kit.asOperator())
    assertEquals((limitValue(r, "DEPOSIT_DAILY"), r.json.objects("pendingChanges").size), (Some(70_000L), 0))
    assertEquals(kit.request("GET", kit.operatorPath(s"/$p"), None, kit.asOperator(roles = "brand_silvervibe")).status, 403)
    val audit = kit.eventually("the audit record", 5.seconds)(kit.facts(PlatformAudit).collectFirst { case a: AuditRecord if a.subjectId == p => a })
    assertEquals((audit.action, audit.changes.head.before, audit.changes.head.after), ("limit-set", Some("10000"), Some("70000")))

  test("cases: opened with a note, noted, closed; the full state carries them"):
    val p  = player()
    val c  = kit.json("POST", kit.operatorPath(s"/$p/cases"), Json.obj("caseType" -> "account_block", "note" -> "worried"), kit.asOperator())
    assertEquals(c.status, 201)
    val id = c.str("caseId")
    assertEquals(kit.json("POST", kit.operatorPath(s"/$p/cases/$id/notes"), Json.obj("note" -> "called"), kit.asOperator()).status, 200)
    assertEquals(kit.json("POST", kit.operatorPath(s"/$p/cases/$id/close"), Json.obj("reason" -> "resolved"), kit.asOperator()).str("status"), "closed")
    assertEquals(kit.json("POST", kit.operatorPath(s"/$p/cases/$id/close"), Json.obj("reason" -> "again"), kit.asOperator()).status, 409)
    val full = kit.request("GET", kit.operatorPath(s"/$p"), None, kit.asOperator()).json
    assertEquals(full.objects("cases").map(_.text("status")), Vector(Some("closed")))

  test("the brand's settings: an operator changes the minimum session limit and it binds the next change"):
    assertEquals(kit.json("PUT", kit.operatorPath("/settings"), Json.obj("minSessionMinutes" -> 45), kit.asOperator()).json.long("minSessionMinutes"), Some(45L))
    val p = player()
    assertEquals(putLimit(p, "SESSION_MINUTES", "45", None).status, 200)
    kit.json("PUT", kit.operatorPath("/settings"), Json.obj("minSessionMinutes" -> 60), kit.asOperator()): Unit

  // ── GAMSTOP and erasure ───────────────────────────────────────────────────

  test("a GAMSTOP refusal by player id is a third-party case and no exclusion; erasure blanks its notes"):
    val p = player()
    assertEquals(kit.json("POST", kit.servicePath("/gamstop-refusal"), Json.obj("playerId" -> p, "result" -> "excluded"), kit.asService("players")).status, 204)
    assertEquals(mayLogIn(p).str("decision"), "allowed")
    val opened = kit.eventually("the case")(kit.facts(CasinoPlayers).collectFirst { case f: RgCaseOpened if f.playerId == p => f })
    assertEquals(opened.trigger, "third_party")
    val id = opened.rgCaseId
    kit.json("POST", kit.operatorPath(s"/$p/cases/$id/notes"), Json.obj("note" -> "the player's address is 1 Main St"), kit.asOperator()): Unit
    assertEquals(kit.request("POST", kit.servicePath(s"/erasure/players/$p"), None, kit.asService("players")).status, 200)
    val exported = kit.request("GET", kit.servicePath(s"/exports/players/$p"), None, kit.asService("players"))
    assert(!exported.text.contains("1 Main St"), exported.text)
    assert(exported.text.contains("third_party"), exported.text)

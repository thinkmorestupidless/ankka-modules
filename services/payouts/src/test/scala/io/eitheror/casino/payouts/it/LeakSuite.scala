package io.eitheror.casino.payouts.it

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.contract.facts.*
import io.eitheror.casino.contract.personal.Personal
import io.eitheror.casino.payouts.Json
import io.eitheror.casino.payouts.Json.*
import io.eitheror.casino.payouts.player.*
import io.eitheror.casino.payouts.workflow.Flow

import java.util.UUID
import scala.concurrent.duration.*

/**
 * R12, spec 001 FR-023: a player's personal values (the holder's names, the IBAN, the email, every operator
 * reason and note) are in no log line, fact, audit record, view row or timer name, at any time. Every marker
 * is unique to the run, so finding one is a leak. The journals and the workflows' stored state hold them until
 * ankka 042 (the named gap, as in `players`); every other table is searched. Spans are not scanned: the
 * integration kit exposes none, and the log lines carry their ids only.
 */
class LeakSuite extends munit.FunSuite with LogCapturing:
  private lazy val kit   = SharedKit.kit
  private lazy val scene = Scene(kit)
  override def munitTimeout = 300.seconds
  override def beforeEach(context: BeforeEach): Unit =
    kit.reset()
    scene.terminals()

  /** One run's markers. The IBAN is a valid one (its check digits are computed), so it passes the destination's check. */
  final case class Markers(run: String = UUID.randomUUID().toString.filter(_.isDigit).padTo(8, '7').take(8)):
    val givenName  = s"Quilliam$run"
    val familyName = s"Vandersnoot$run"
    val address    = s"leak-$run@example.test"
    val iban: String =
      val bban  = s"3704004405$run"
      val check = 98 - (BigInt((bban + "DE00").map(c => if c.isLetter then (c - 'A' + 10).toString else c.toString).mkString) % 97).toInt
      f"DE$check%02d$bban"
    val reason = s"a reason that must not leak $run"
    val note   = s"a payout note that must not leak $run"
    def all: Vector[String] = Vector(givenName, familyName, address, iban, reason, note)

  type Places = Vector[(String, Vector[String])]

  def assertNowhere(markers: Seq[String], places: Places): Unit =
    val found = for
      (place, texts) <- places
      text           <- texts
      marker         <- markers
      if text.contains(marker)
    yield s"$marker in $place: ${text.take(200)}"
    assert(found.isEmpty, found.distinct.mkString("\n"))

  /** Every topic payouts reads or writes. */
  private val topics: Vector[Topic[?]] = Vector(CasinoPlayers, MoneyTransactions, CasinoWithdrawals, PlatformAudit)

  /** The tables of the named gap until ankka 042: the journal and its snapshots (the entities' and the workflows' events and state). */
  private val Gap = Set("event_journal", "snapshot", "workflow_state", "durable_state")

  private def hex(v: String) = java.util.HexFormat.of().formatHex(v.getBytes("UTF-8"))

  private def player(m: Markers): String =
    val p      = s"lk-${m.run}"
    val record = kit.components.forEventSourcedEntity(EntityId(p))
    record.call(PlayerPayouts.seeNames).invoke(SeeNames(Personal(m.givenName), Personal(m.familyName), kit.now())): Unit
    record.call(PlayerPayouts.seeEmail).invoke(SeeEmail(Personal(m.address), kit.now())): Unit
    record.call(PlayerPayouts.seeKyc).invoke(SeeKyc(Kyc.Verified, kit.now())): Unit
    val d = kit.json("POST", kit.playerPath(s"/$p/payout-destinations"), Json.obj("firstName" -> m.givenName, "lastName" -> m.familyName,
      "iban" -> m.iban, "label" -> "main", "setAsDefault" -> true), kit.asPlayer(p))
    assertEquals(d.status, 201, d.text)
    kit.wallet.balanceFor(p, 100000L)
    p

  private def ask(p: String, terminalId: String): String =
    val destinationId = kit.request("GET", kit.playerPath(s"/$p/payout-destinations"), None, kit.asPlayer(p)).json
      .objects("destinations").head.text("id").get
    val r = kit.json("POST", kit.playerPath(s"/$p/withdrawal-requests"), Json.obj("amount" -> 5000L, "currency" -> "EUR",
      "terminalId" -> terminalId, "destinationId" -> destinationId, "key" -> UUID.randomUUID().toString), kit.asPlayer(p))
    assertEquals(r.status, 201, r.text)
    r.str("requestId")

  private def operator(path: String, body: JsonNode) =
    kit.json("POST", s"/v1/casino/brands/${kit.slug}/withdrawal-requests$path", body, kit.asOperator())

  test("the frame finds a marker where it is, and the IBAN is a valid one"):
    val m = Markers()
    val e = intercept[AssertionError](assertNowhere(m.all, Vector("somewhere" -> Vector(s"x ${m.iban} y"))))
    assert(e.getMessage.contains("somewhere"), e.getMessage)
    assert(Iban.isValid(m.iban), m.iban)

  test("a request rejected with a reason, one paid, one failed by hand with a note: no marker in the log, a fact, the audit, a view row or a timer"):
    val m = Markers()
    val p = player(m)
    val rejected = ask(p, "t-polled")
    assertEquals(operator(s"/$rejected/reject", Json.obj("reason" -> m.reason)).status, 200)
    val paid = ask(p, "t-polled")
    kit.gateway.initiateFor(paid, FakeGateway.answer(paid, "completed", Some(s"ref-$paid")))
    assertEquals(scene.approve(paid).status, 200)
    scene.at(paid, Flow.Completed)
    val manual = ask(p, "t-manual")
    assertEquals(scene.approve(manual).status, 200)
    val failed = operator(s"/$manual/mark-payout-failed", Json.obj("reason" -> m.note))
    assertEquals(failed.status, 200, failed.text)
    // The gateway was sent the payee, as it must be; nothing else was.
    assert(kit.gateway.payouts.exists(_.body.contains(m.iban)))
    kit.eventually("the facts")(Option.when(kit.facts(CasinoWithdrawals).count(_.playerId == p) >= 5)(()))
    Thread.sleep(1000)

    val (gap, rest) = kit.tables.toVector.partition((t, _) => Gap(t))
    assertNowhere(m.all, Vector("the log" -> kit.logText))
    assertNowhere(m.all, topics.map(t => s"facts on ${t.name}" -> kit.factTexts(t)))
    assertNowhere(m.all ++ m.all.map(hex), rest.map((t, rows) => s"table $t" -> rows))
    assertNowhere(m.all, Vector("timer names" -> rest.collect { case ("ankka_timers", rows) => rows }.flatten))
    // the named gap: the journal holds the earlier events; this flips when ankka 042 lands
    assert(gap.exists(_._2.exists(r => r.contains(hex(m.givenName)) || r.contains(m.givenName))),
      s"the gap tables ${gap.map(_._1).mkString(", ")} no longer hold the names: ankka 042 has landed; flip this")

  test("the provider's own reason text, on a rejection, a failed poll and a reversal: kept only as the request's note, a code everywhere else"):
    val m      = Markers()
    val said   = s"Payout to ${m.givenName} ${m.familyName} declined by the bank"
    val p      = player(m)
    val reject = ask(p, "t-polled")
    kit.gateway.initiateFor(reject, FakeGateway.answer(reject, "rejected", None, Some(said)))
    assertEquals(scene.approve(reject).status, 200)
    scene.at(reject, Flow.Released)
    val failed = ask(p, "t-polled")
    kit.gateway.initiateFor(failed, FakeGateway.answer(failed, "initiated", Some(s"ref-$failed")))
    assertEquals(scene.approve(failed).status, 200)
    kit.gateway.statusFor(failed, FakeGateway.answer(failed, "failed", Some(s"ref-$failed"), Some(said)))
    scene.at(failed, Flow.Released)
    val reversed = ask(p, "t-polled")
    kit.gateway.initiateFor(reversed, FakeGateway.answer(reversed, "completed", Some(s"ref-$reversed")))
    assertEquals(scene.approve(reversed).status, 200)
    scene.at(reversed, Flow.Completed)
    val r = kit.json("POST", s"/v1/casino/brands/${kit.slug}/withdrawals/$reversed/outcome",
      Json.obj("outcome" -> "reversed", "providerReference" -> s"ref-$reversed", "reason" -> said), kit.asGateway)
    assertEquals(r.status, 200, r.text)
    scene.at(reversed, Flow.Reversed)
    kit.eventually("the facts")(Option.when(kit.facts(CasinoWithdrawals).count(_.playerId == p) >= 9)(()))
    Thread.sleep(1000)

    // The text is the request's note, which erasure blanks; the codes are the fixed ones.
    Vector(reject, failed, reversed).foreach(id =>
      assert(scene.state(id).outcomes.exists(_.note.exists(_.reveal == said)), s"$id: ${scene.state(id).outcomes}"))
    val codes = kit.facts(CasinoWithdrawals).collect {
      case f: WithdrawalFailed if f.playerId == p   => f.failureReason
      case f: WithdrawalReversed if f.playerId == p => f.reason
    }
    assertEquals(codes.toSet, Set("provider_rejected", "provider_failed", "chargeback"))
    val (_, rest) = kit.tables.toVector.partition((t, _) => Gap(t))
    val markers   = Vector(said, m.givenName, m.familyName)
    assertNowhere(markers, Vector("the log" -> kit.logText))
    assertNowhere(markers, topics.map(t => s"facts on ${t.name}" -> kit.factTexts(t)))
    assertNowhere(markers ++ markers.map(hex), rest.map((t, rows) => s"table $t" -> rows))

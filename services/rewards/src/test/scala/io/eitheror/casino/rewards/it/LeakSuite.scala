package io.eitheror.casino.rewards.it

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.contract.facts.*
import io.eitheror.casino.contract.personal.Personal
import io.eitheror.casino.rewards.Json
import io.eitheror.casino.rewards.reward.RewardStatus

import java.util.UUID
import scala.concurrent.duration.*

/**
 * Research R12, spec FR-033: a player's email and address, which `players` publishes on `player_registered`, and an
 * operator's revoke reason are in no log line, fact, audit record, table row or timer name, and are sent to no
 * neighbour. The reason is the one personal value the service keeps, in the `Revoked` event alone ("Found during
 * implementation", T026), so the journal is the one table it may be in, and it is there. Every marker is unique to
 * the run, so finding one is a leak.
 *
 * Spans are not scanned: the integration kit exposes none. Their names are a component's and a handler's, both
 * declared in code, and their attributes the caller's subject and client key, so no request value can become one.
 */
class LeakSuite extends munit.FunSuite with LogCapturing:
  private lazy val kit   = SharedKit.kit
  private lazy val paths = ClaimPaths(kit)
  override def munitTimeout = 300.seconds
  override def beforeEach(context: BeforeEach): Unit = kit.reset()

  /** One run's markers. */
  final case class Markers(run: String = UUID.randomUUID().toString.filter(_.isDigit).padTo(8, '7').take(8)):
    val email    = s"leak-$run@example.test"
    val line     = s"$run Quilliam Crescent"
    val city     = s"Vandersnoot$run"
    val postCode = s"QX$run"
    val reason   = s"a revoke reason that must not leak $run"
    val personal: Vector[String] = Vector(email, line, city, postCode)
    def all: Vector[String]      = personal :+ reason

  type Places = Vector[(String, Vector[String])]

  def assertNowhere(markers: Seq[String], places: Places): Unit =
    val found = for
      (place, texts) <- places
      text           <- texts
      marker         <- markers
      if text.contains(marker)
    yield s"$marker in $place: ${text.take(200)}"
    assert(found.isEmpty, found.distinct.mkString("\n"))

  /** What rewards publishes: `casino.players`, `money.transactions` and `casino.rounds` are its inputs. */
  private val published: Vector[Topic[?]] = Vector(CasinoRewards, PlatformAudit)

  /** The one table the revoke reason may be in: the journal, where the `Revoked` event keeps it. */
  private val Journal = "event_journal"

  private def hex(v: String) = java.util.HexFormat.of().formatHex(v.getBytes("UTF-8"))

  private def revoke(id: String, mode: String, reason: String): Reply =
    kit.json("POST", kit.grantsPath(s"/$id/revoke"), Json.obj("mode" -> mode, "reason" -> reason), kit.asOperator(subject = "op-leak"))

  test("the frame finds a marker where it is"):
    val m = Markers()
    val e = intercept[AssertionError](assertNowhere(m.all, Vector("somewhere" -> Vector(s"x ${m.reason} y"))))
    assert(e.getMessage.contains("somewhere"), e.getMessage)

  test("a registration with an email and an address, rewards claimed and revoked with a reason: no marker anywhere but the reason in the journal"):
    val m = Markers()
    val p = paths.newPlayer("lk-p")
    val at = kit.now()
    paths.players(p, PlayerRegistered(p, at, None, None, None, Some("GB"), Some("EUR"), None, at, addressLine = Some(Personal(m.line)),
      city = Some(Personal(m.city)), postCode = Some(Personal(m.postCode)), email = Some(Personal(m.email))))
    paths.awaitRecord(p, "registered")(_.registeredAt.contains(at)): Unit
    paths.checked(p)

    // funds: claimed by the player, revoked with the reason, the wallet's record clawed back
    val funds = paths.holdingActive(p)
    val fundsId = paths.active(p, funds).rewardId
    val r1 = revoke(fundsId, "reclaim", m.reason)
    assertEquals((r1.status, r1.str("status")), (200, "revoked"), r1.text)

    // free rounds: awarded by an operator, created at the provider, cancelled there by the revoke
    val game = paths.unique("g")
    kit.provider.game(game, "free_spins")
    kit.provider.spinValues("EUR", "0.10", "0.20")
    val award = kit.json("POST", kit.grantsPath(s"/$p"), Json.obj("type" -> "FREE_SPINS", "gameId" -> game, "provider" -> "st8",
      "spinCount" -> 10, "spinValue" -> 20L), kit.asOperator(subject = "op-leak"))
    assertEquals(award.status, 200, award.text)
    val roundsId = award.str("grantId")
    val r2 = revoke(roundsId, "forfeit", m.reason)
    assertEquals((r2.status, r2.str("status")), (200, "revoked"), r2.text)

    for id <- Vector(fundsId, roundsId) do
      paths.until(id, "revoked")(_.status == RewardStatus.Revoked): Unit
      kit.eventually(s"$id audited")(kit.facts(PlatformAudit).collectFirst {
        case r: AuditRecord if r.subjectId == id && r.action == "rewards.revoked" => r
      }): Unit
      kit.eventually(s"$id cancelled")(kit.facts(CasinoRewards).collectFirst { case f: BonusCancelled if f.bonusId == id => f }): Unit
    Thread.sleep(1000)

    val tables         = kit.tables.toVector
    val (journal, rest) = tables.partition((t, _) => t == Journal)
    val neighbours     = Vector("wallet" -> kit.wallet, "game-provider" -> kit.provider, "players" -> kit.players)
      .map((n, f) => s"calls to $n" -> f.received.map(r => s"${r.path} ${r.rawQuery} ${r.body} ${r.headers.values.mkString(" ")}"))
    assertNowhere(m.all, Vector("the log" -> kit.logText))
    assertNowhere(m.all, published.map(t => s"facts on ${t.name}" -> kit.factTexts(t)))
    assertNowhere(m.all, neighbours)
    assertNowhere(m.all, Vector("the revoke's answers" -> Vector(r1.text, r2.text)))
    assertNowhere(m.all ++ m.all.map(hex), rest.map((t, rows) => s"table $t" -> rows))
    assertNowhere(m.all, Vector("timer names" -> rest.collect { case ("ankka_timers", rows) => rows }.flatten))
    assertNowhere(m.personal ++ m.personal.map(hex), journal.map((t, rows) => s"table $t" -> rows))
    // the reason is kept, in the journal alone (research R12): this proves the scan reads where it is
    assert(journal.exists(_._2.exists(r => r.contains(m.reason) || r.contains(hex(m.reason)))),
      s"the revoke reason is not in ${tables.map(_._1).mkString(", ")}")

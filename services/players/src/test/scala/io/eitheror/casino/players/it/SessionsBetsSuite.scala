package io.eitheror.casino.players.it

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.contract.facts.*
import io.eitheror.casino.contract.money.{Currency, MinorUnits, SignedMinorUnits}
import io.eitheror.casino.players.Json

import java.util.UUID
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * Spec 030 P5, P6, P10: a player's sessions with the gap between the wallet's bets and the rounds, the brand's
 * sessions, a session closed by force naming the operator, a session's bets, and the bets' filters.
 */
class SessionsBetsSuite extends munit.FunSuite with LogCapturing:
  override val munitTimeout = 3.minutes
  private lazy val kit = SharedKit.kit
  private val eur = Currency.unsafe("EUR")

  override def beforeEach(context: BeforeEach): Unit = kit.reset()

  private def op(method: String, path: String, subject: String = "op-9") =
    kit.request(method, kit.operatorPath(path), if method == "GET" then None else Some("{}"), kit.asOperator(subject = subject))

  /** A signed-in player with `n` game sessions: the player, the player session, the game sessions. */
  private def playing(n: Int): (String, String, Vector[String]) =
    val (email, id) = kit.activePlayer()
    val session     = kit.sessionOf(kit.signIn(email).str("token"))
    val games = (1 to n).toVector.map(g => kit.json("POST", kit.servicePath(s"/$id/game-sessions"),
      Json.obj("gameId" -> s"g-$g", "currency" -> "EUR", "playerSessionId" -> session), kit.asService("game-provider")).str("sessionId"))
    (id, session, games)

  private def settled(id: String, game: String, stake: Long, win: Long, outcome: String = "") =
    val p = UUID.randomUUID().toString.take(12)
    kit.publish(CasinoRounds, s"$id/EUR", RoundSettled(id, eur, s"st8/$p", "st8", p, "g-1", game, MinorUnits.unsafe(stake),
      MinorUnits.unsafe(win), SignedMinorUnits(win - stake), if outcome.nonEmpty then outcome else if win > 0 then "win" else "loss",
      Vector(s"tx-$p"), Vector(s"ptx-$p"), Vector(s"wtx-$p"), None, kit.now()))
    s"st8/$p"

  private def bet(id: String, game: String, amount: Long) =
    val tx = s"bet-${UUID.randomUUID().toString.take(8)}"
    kit.publish(MoneyTransactions, s"$id/EUR", BetPlaced(id, eur, tx, "r", "g-1", MinorUnits.unsafe(amount), "real",
      SignedMinorUnits(0), MinorUnits.unsafe(0), kit.now(), sessionId = Some(game)))

  test("a game session's gap: the wallet's bets beside the rounds' stakes, none when they agree") {
    val (id, _, Vector(even, short)) = playing(2): @unchecked
    settled(id, even, 100, 0); bet(id, even, 100)
    settled(id, short, 100, 0); bet(id, short, 100); bet(id, short, 50)
    val games = kit.eventually("the rounds and the bets", 30.seconds) {
      val r  = op("GET", s"/players/$id/player-sessions")
      val gs = r.json.get("sessions").get(0).get("gameSessions").elements().asScala.map(g => g.get("sessionId").asText -> g).toMap
      Option.when(gs.size == 2 && gs.values.forall(_.get("rounds").asInt == 1) && gs(short).get("wageredMinorUnits").asLong == 150)(gs)
    }
    assert(games(even).get("gap").isNull, games(even).toString)
    val gap = games(short).get("gap")
    assertEquals((gap.get("wageredMinorUnits").asLong, gap.get("roundStakeMinorUnits").asLong, gap.get("currency").asText), (150L, 100L, "EUR"))
    assertEquals((games(short).get("stakedMinorUnits").asLong, games(short).get("currency").asText), (100L, "EUR"))
  }

  test("force-close ends the session and its games forced, naming the operator; again, it ends nothing; another's is 404") {
    val (id, session, Vector(game)) = playing(1): @unchecked
    val (other, _, _) = playing(0)
    assertEquals(op("POST", s"/players/$other/player-sessions/$session/force-close").status, 404)
    val r = op("POST", s"/players/$id/player-sessions/$session/force-close")
    assertEquals((r.status, r.str("status"), r.json.get("ended").asBoolean), (200, "forced", true), r.text)
    val audit = kit.eventually("the audit")(kit.facts(PlatformAudit).collectFirst {
      case f: AuditRecord if f.action == "force-end" && f.changes.exists(_.before.contains(game)) => f })
    assertEquals(audit.actorId, "op-9")
    assert(kit.realm.receivedOf("revoke").nonEmpty)
    val again = op("POST", s"/players/$id/player-sessions/$session/force-close")
    assertEquals((again.status, again.str("status"), again.json.get("ended").asBoolean), (200, "forced", false))
    val forced = kit.eventually("the session listed as forced", 30.seconds) {
      val l = op("GET", s"/sessions?status=forced&pageSize=200")
      Option.when(l.json.get("sessions").elements().asScala.exists(_.get("sessionId").asText == session))(l)
    }
    assert(forced.json.get("sessions").elements().asScala.forall(_.get("status").asText == "forced"), forced.text)
  }

  test("the brand's sessions: active ones by start, paged with their total; a bad range is refused") {
    val start = kit.now().minusSeconds(1)
    val (_, session, _) = playing(0)
    val l = kit.eventually("the session listed", 30.seconds) {
      val r = op("GET", s"/sessions?status=active&from=$start&pageSize=200")
      Option.when(r.json.get("sessions").elements().asScala.exists(_.get("sessionId").asText == session))(r)
    }
    assertEquals((l.json.get("page").asInt, l.json.get("pageSize").asInt), (1, 200))
    assert(l.json.get("total").asInt >= 1)
    assert(l.json.get("sessions").elements().asScala.forall(_.get("status").asText == "active"))
    assertEquals(op("GET", s"/sessions?from=2026-05-02&to=2026-05-01").status, 400)
  }

  test("bets: settled by default, cancelled too; type, date and status filters; a session's bets; 404 for another's session") {
    val (id, _, Vector(game)) = playing(1): @unchecked
    val loss   = settled(id, game, 100, 0)
    val win    = settled(id, game, 100, 300)
    val cancel = settled(id, game, 40, 40, outcome = "cancelled")
    val p      = UUID.randomUUID().toString.take(12)
    kit.publish(CasinoRounds, s"$id/EUR", RoundOpened(id, eur, s"st8/$p", "st8", p, "g-1", game, None, kit.now()))
    def ids(q: String) = op("GET", s"/players/$id/bets?$q").json.get("bets").elements().asScala.map(_.get("roundId").asText).toSet
    kit.eventually("the rounds", 30.seconds)(Option.when(ids("status=all").size == 4)(()))
    assertEquals(ids(""), Set(loss, win, cancel))
    assertEquals(ids("status=cancelled"), Set(cancel))
    assertEquals(ids("status=nonsense"), Set(loss, win, cancel))
    assertEquals(ids("type=win&status=all"), Set(win, cancel))
    assertEquals(ids(s"transactionId=wtx-${win.stripPrefix("st8/")}"), Set(win))
    val today = java.time.LocalDate.now(java.time.ZoneOffset.UTC)
    assertEquals(ids(s"fromDate=${today.plusDays(1)}"), Set.empty[String])
    assertEquals(ids(s"fromDate=$today&toDate=$today"), Set(loss, win, cancel))
    val capped = op("GET", s"/players/$id/bets?status=all&limit=100000").json
    assertEquals((capped.get("limit").asInt, capped.get("total").asInt, capped.get("summary").get("truncated").asBoolean), (200, 4, false))
    val one = op("GET", s"/players/$id/bets?roundId=$win").json
    assertEquals((one.get("bets").get(0).get("amountMinorUnits").asLong, one.get("summary").get("totalBets").asInt), (200L, 1))
    val session = op("GET", s"/players/$id/sessions/$game/bets?pageSize=2")
    assertEquals((session.json.get("total").asInt, session.json.get("bets").size, session.json.get("summary").get("staked").asLong),
      (4, 2, 240L))
    val (other, _, _) = playing(0)
    assertEquals(op("GET", s"/players/$other/sessions/$game/bets").status, 404)
    assertEquals(op("GET", s"/players/$id/sessions/nope/bets").status, 404)
  }

package io.eitheror.casino.attribution.it

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.attribution.money.{DayCloser, OpenDays}
import io.eitheror.casino.attribution.tag.Pseudonyms
import io.eitheror.casino.contract.facts.*

import java.time.{LocalDate, LocalTime}
import scala.concurrent.duration.*

/**
 * GGR from the wallet's real movements (US4; FR-016, research R10), in `Africa/Johannesburg` with the clock
 * moved: per reporting day at the close, adjusted when a round of a closed day settles late, per round only
 * for a network that asks. A kit of its own: each test plays an evening and closes its day.
 */
class GgrSuite extends munit.FunSuite with LogCapturing:
  override val munitTimeout: Duration = 300.seconds

  private lazy val kit: Kit =
    val k = Kit()
    k.addNetwork(): Unit
    k.makePseudonymKey()
    k

  override def afterAll(): Unit = kit.stop()
  override def beforeEach(context: BeforeEach): Unit = kit.reset()

  private lazy val days = DayClock(kit)
  private def evening(): LocalDate = days.evening()
  private def at(day: LocalDate, time: LocalTime): Unit = days.at(day, time)
  private def close(day: LocalDate, wallets: WalletScript*): Unit = days.close(day, wallets*)

  // ── players and facts ───────────────────────────────────────────────────────────────────────────

  private def pseudonym(player: String, network: String = "programme") =
    Pseudonyms.derive(Pseudonyms.keyBytes(kit.testKit.secrets.get(Pseudonyms.secretName(network)).get), player)

  private def bound(fake: FakeNetwork = kit.network): WalletScript =
    val player = kit.bindPlayer(Some(fake.mint("aff-1", kit.fresh("lnk"), iat = kit.now)))
    kit.eventually("bound")(kit.record(player).binding): Unit
    WalletScript(kit, player)

  private def factsOf(p: String): Vector[PlayerAttributionFact] =
    kit.attributionFacts.collect { case f: PlayerAttributionFact if f.pseudonym == p => f }

  private def daily(p: String, day: LocalDate): GgrDaily =
    kit.eventually(s"$p's ggr-daily for $day")(factsOf(p).collectFirst { case d: GgrDaily if d.reportingDay == day.toString => d })

  private def ggrOf(p: String): Vector[PlayerAttributionFact] =
    factsOf(p).filter(f => f.factId.startsWith("ggr-"))

  private def settled(): Unit = Thread.sleep(1500)

  // ── the cases ───────────────────────────────────────────────────────────────────────────────────

  test("a day's real stake and win, a round across midnight on its first stake's day; restricted money never counts") {
    val day    = evening()
    val split  = bound()
    split.deposit(5000)
    split.award("a-split", 2000)
    split.bet("r-1", 1000, fromReal = Some(600))
    val bonus  = bound()
    bonus.award("a-only", 1000)
    bonus.bet("r-2", 500, fromReal = Some(0))
    bonus.win("r-2", 300, toRestricted = true)
    val conv   = bound()
    conv.award("a-conv", 1000)
    conv.convert("a-conv", 300)
    conv.bet("r-3", 300)
    conv.loss("r-3")
    val back   = bound()
    back.deposit(2000)
    val bet = back.bet("r-4", 1000)
    back.again(bet)
    back.rollback("r-4", 400)

    at(day.plusDays(1), LocalTime.of(0, 10))
    split.win("r-1", 400)
    close(day, split, bonus, conv, back)

    val p = pseudonym(split.player)
    val d = daily(p, day)
    assertEquals((d.stake.value, d.win.value, d.ggr.value, d.currency.code), (600L, 400L, 200L, "EUR"))
    assertEquals((d.reference, d.factId), (s"EUR/$day", s"ggr-daily/$p/EUR/$day"))
    assert(kit.attributionMessages.exists(_.message.metadata.get("ce-id").contains(s"ggr-daily/$p/EUR/$day")))

    val c = daily(pseudonym(conv.player), day)
    assertEquals((c.stake.value, c.win.value, c.ggr.value), (300L, 0L, 300L))
    val r = daily(pseudonym(back.player), day)
    assertEquals((r.stake.value, r.win.value), (600L, 0L))

    settled()
    assertEquals(ggrOf(pseudonym(bonus.player)), Vector.empty)
    assertEquals(ggrOf(pseudonym(split.player, "programme")).size, 1)
    assertEquals(kit.views.forView(OpenDays).ask(OpenDays.openBefore, "day" -> day.plusDays(1).toString).filter(_.day == day.toString),
      Vector.empty)
    DayCloser.closing(kit.components, kit.clock).run(): Unit
    settled()
    assertEquals(factsOf(p).count(_.isInstanceOf[GgrDaily]), 1)
  }

  test("a round of a closed day that settles after the close is a ggr-adjustment for that day; the daily is not emitted again") {
    val day  = evening()
    val late = bound()
    late.deposit(3000)
    late.bet("r-5", 1000)
    val idle = bound()
    idle.deposit(1000)
    close(day, late, idle)
    val p = pseudonym(late.player)
    assertEquals(daily(p, day).ggr.value, 1000L)

    late.win("r-5", 400)
    val adj = kit.eventually("the adjustment")(factsOf(p).collectFirst { case a: GgrAdjustment => a })
    assertEquals((adj.reportingDay, adj.ggr.value, adj.currency.code), (day.toString, -400L, "EUR"))
    settled()
    assertEquals(factsOf(p).count(_.isInstanceOf[GgrDaily]), 1)
    assertEquals(ggrOf(pseudonym(idle.player)), Vector.empty, "a day with no real play emits nothing")
  }

  test("a player unbound at the close is told of the day when a code binds them, marked before the binding") {
    val day    = evening()
    val player = kit.bindPlayer(None)
    kit.eventually("registered")(kit.record(player).registeredAt): Unit
    val wallet = WalletScript(kit, player)
    wallet.deposit(2000)
    wallet.bet("r-6", 800)
    close(day, wallet)
    settled()
    val p = pseudonym(player)
    assertEquals(factsOf(p), Vector.empty)

    val code = kit.fresh("CODE").toUpperCase
    kit.network.codes = Map(code -> ("offer" -> kit.network.mint("aff-9", kit.fresh("lnk"), iat = kit.now)))
    kit.network.codesVersion += 1
    kit.moveClock(kit.now.plusSeconds(kit.network.cacheSeconds + 1))
    kit.refreshDocuments(): Unit
    kit.publish(CasinoRewards, BonusCodeEntered(player, code, kit.now))
    val d = daily(p, day)
    assertEquals((d.stake.value, d.beforeBinding, d.affiliate), (800L, Some(true), "aff-9"))
  }

  test("a network with the round stream gets a ggr-round at the settlement and the daily; one without, the daily only") {
    val streamer = FakeNetwork("streamer")
    try
      kit.addNetwork(streamer, legacy = false, codes = false, roundStream = true): Unit
      kit.makePseudonymKey("streamer")
      val day     = evening()
      val streams = bound(streamer)
      streams.deposit(2000)
      streams.award("a-s", 1000)
      streams.bet("r-7", 1000, fromReal = Some(600))
      streams.win("r-7", 400)
      val plain = bound()
      plain.deposit(2000)
      plain.bet("r-8", 1000)
      plain.win("r-8", 400)

      val p = pseudonym(streams.player, "streamer")
      val round = kit.eventually("the round")(factsOf(p).collectFirst { case g: GgrRound => g })
      assertEquals((round.stake.value, round.win.value, round.ggr.value, round.reference), (600L, 400L, 200L, "r-7"))

      close(day, streams, plain)
      assertEquals(daily(p, day).ggr.value, 200L)
      val q = pseudonym(plain.player)
      assertEquals(daily(q, day).ggr.value, 600L)
      assert(!factsOf(q).exists(_.isInstanceOf[GgrRound]))
    finally streamer.stop()
  }

  test("SC-002: a player bound by a code at registration has the day attributed") {
    val day  = evening()
    val code = kit.fresh("CODE").toUpperCase
    kit.network.codes = Map(code -> ("offer" -> kit.network.mint("aff-3", kit.fresh("lnk"), iat = kit.now)))
    kit.network.codesVersion += 1
    kit.moveClock(kit.now.plusSeconds(kit.network.cacheSeconds + 1))
    kit.refreshDocuments(): Unit
    val player = kit.bindPlayer(None, bonusCode = Some(code))
    assertEquals(kit.eventually("bound")(kit.record(player).binding).affiliate, "aff-3")
    val wallet = WalletScript(kit, player)
    wallet.deposit(1000)
    wallet.bet("r-9", 250)
    close(day, wallet)
    val d = daily(pseudonym(player), day)
    assertEquals((d.affiliate, d.ggr.value, d.beforeBinding), ("aff-3", 250L, None))
  }

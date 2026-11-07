package io.eitheror.casino.provider.edge

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.provider.core.Callback

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * The provider over Postgres (Docker) against `FakeBrand`, for what only a running service shows: a
 * brand silent for fifteen minutes, then answering, and a restart while a callback waits on its
 * schedule (SC-004). The brand applies each callback once under its transaction id, so a balance
 * that moved exactly once per callback is a balance that was not moved by a retry.
 */
class ProviderIntegrationSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 3.minutes

  private def kit  = ProviderTestKit.kit
  private def game = kit.game

  override def afterEach(context: AfterEach): Unit =
    kit.brand.slow("debit", 0, 0)
    kit.brand.failing("credit", 0)
    kit.brand.failing("debit", 0)

  private def ticket() = s"L-it-${System.nanoTime()}"
  private def round()  = s"r-it-${System.nanoTime()}"

  private def acknowledged(id: String, within: FiniteDuration = 60.seconds): Callback =
    kit.eventually(within)(Option(kit.callback(id)).filter(_.outcome == Callback.Acknowledged))

  private def attempted(id: String, n: Int): Unit =
    kit.eventually()(Option(kit.callback(id)).filter(_.attempts.size >= n)): Unit

  test("a brand silent for fifteen minutes and then answering has every debit and credit acknowledged, its balance moved once per callback") {
    val t      = ticket()
    val player = s"p-silent-${System.nanoTime()}"
    kit.open(t, player)
    val won = round()
    assertEquals(game.stake(t, won, 150).text("status"), Some("ok"))
    val before = kit.brand.balanceOf(player)

    // The debit is processed and answered after the wait: the brand moved the balance and the
    // provider never heard. The credit is answered with error: the brand moved nothing.
    kit.brand.slow("debit", 10000, 1500)
    kit.brand.failing("credit", 10000)
    val staked = game.stake(t, round(), 200)
    assertEquals(staked.status, 202, staked.body.toString)
    val debit = staked.text("callbackId").get
    val settled = game.settle(t, won, 300)
    assertEquals(settled.status, 202, settled.body.toString)
    val credit = settled.text("callbackId").get
    attempted(debit, 3)
    attempted(credit, 3)

    kit.offset += 15.minutes.toMillis
    attempted(debit, 5)
    attempted(credit, 5)
    assertNotEquals(kit.callback(debit).outcome, Callback.Exhausted)
    assertNotEquals(kit.callback(credit).outcome, Callback.Exhausted)

    kit.brand.slow("debit", 0, 0)
    kit.brand.failing("credit", 0)
    acknowledged(debit)
    acknowledged(credit)

    val sent = kit.brand.requestsTo("debit").map(_.text).filter(_.contains(debit))
    assert(sent.size >= 5, s"the debit was sent ${sent.size} times")
    assertEquals(sent.distinct.size, 1, "every retry carries the same body")
    assertEquals(kit.brand.balanceOf(player), before - BigDecimal("2.00") + BigDecimal("3.00"))
  }

  test("two brands playing 1,000 rounds each share no session, round or callback (SC-006)") {
    kit.putEntry(kit.entry("spinvibe", kit.spinvibe.address))
    try
      val perBrand = 1000
      val sessions = 10
      def playAt(site: String): Set[String] =
        val fake = if site == "goldvibe" then kit.brand else kit.spinvibe
        val tickets = (1 to sessions).map { i =>
          val t = s"L-sc006-$site-$i-${System.nanoTime()}"
          fake.issue(t, s"p-sc006-$site-$i")
          assert(kit.launch(t, s"p-sc006-$site-$i", "test-slot", site = site, issue = false).json.has("game_url"))
          assertEquals(game.check(t).status, 200)
          t
        }
        val pool = java.util.concurrent.Executors.newFixedThreadPool(10)
        try
          val calls = tickets.flatMap(t => (1 to perBrand / sessions).map(r => (() => game.stake(t, s"r-$r", 1).text("status")): java.util.concurrent.Callable[Option[String]]))
          val answered = pool.invokeAll(calls.asJava)
          assertEquals(answered.asScala.count(_.get().contains("ok")), perBrand)
        finally pool.shutdown()
        tickets.toSet

      kit.open(ticket(), "p-sc006-warm")
      val gold = playAt("goldvibe")
      val spin = playAt("spinvibe")

      def listedRounds(slug: String): Vector[(String, String)] =
        var after: Option[String] = None
        var all = Vector.empty[(String, String)]
        var more = true
        while more do
          val a = kit.asStaff("GET", s"/v1/provider/brands/$slug/rounds${after.fold("")(c => s"?after=$c")}", ProviderTestKit.staff("games"))
          assertEquals(a.status, 200, a.body)
          val page = a.json.get("rounds").elements().asScala
            .map(r => (r.get("launchToken").asText(), r.get("roundId").asText())).toVector
          all ++= page
          after = Option(a.json.get("next")).filter(!_.isNull).map(_.asText())
          more = after.isDefined
        all

      val goldRounds = kit.eventually(60.seconds)(Option(listedRounds("goldvibe").filter((t, _) => gold.contains(t))).filter(_.size == perBrand))
      val spinRounds = kit.eventually(60.seconds)(Option(listedRounds("spinvibe").filter((t, _) => spin.contains(t))).filter(_.size == perBrand))
      assertEquals(listedRounds("goldvibe").map(_._1).toSet.intersect(spin), Set.empty)
      assertEquals(listedRounds("spinvibe").map(_._1).toSet.intersect(gold), Set.empty)
      assertEquals(goldRounds.distinct.size, perBrand)
      assertEquals(spinRounds.distinct.size, perBrand)
      // Each brand was sent only its own players' debits.
      assertEquals(kit.spinvibe.requestsTo("debit").count(r => gold.exists(t => r.text.contains(s"s-$t"))), 0)
      assertEquals(kit.brand.requestsTo("debit").count(r => spin.exists(t => r.text.contains(s"s-$t"))), 0)
    finally kit.removeEntry("spinvibe")
  }

  test("a restart while a credit waits on its schedule resumes the schedule, and the credit is acknowledged once") {
    val t      = ticket()
    val player = s"p-restart-${System.nanoTime()}"
    kit.open(t, player)
    val r = round()
    assertEquals(game.stake(t, r, 100).text("status"), Some("ok"))
    val before = kit.brand.balanceOf(player)

    kit.brand.failing("credit", 10000)
    val credit = game.settle(t, r, 250).text("callbackId").get
    attempted(credit, 2)

    kit.restart()
    val sentBefore = kit.brand.requestsTo("credit").count(_.text.contains(credit))
    // Still retried after the restart, with nothing in memory to carry it.
    kit.eventually()(Option(kit.brand.requestsTo("credit").count(_.text.contains(credit))).filter(_ > sentBefore + 1))
    kit.brand.failing("credit", 0)
    acknowledged(credit)
    assertEquals(kit.brand.balanceOf(player), before + BigDecimal("2.50"))
  }

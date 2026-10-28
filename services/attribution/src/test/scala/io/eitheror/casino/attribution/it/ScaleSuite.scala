package io.eitheror.casino.attribution.it

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.attribution.money.{DayCloser, DayTally, OpenDays}
import io.eitheror.casino.attribution.networks.Network
import io.eitheror.casino.attribution.tag.Pseudonyms
import io.eitheror.casino.contract.facts.GgrDaily

import java.time.LocalTime
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * SC-004, FR-016 (T070): 2,000 bound players play a day. Once the clock passes the close, one `DayCloser`
 * run closes the 2,000 tallies well inside the timer's 5-minute period, and every `ggr-daily` is on the
 * topic and in the network's feed within 60 seconds of the close. Times are the wall clock's: the kit's
 * clock stands still while the close runs. A kit of its own.
 */
class ScaleSuite extends munit.FunSuite with LogCapturing:
  override val munitTimeout: Duration = 30.minutes

  private val Players = 2000

  private lazy val kit: Kit =
    val k = Kit()
    k.addNetwork(): Unit
    k.makePseudonymKey()
    k.components.forKeyValueEntity(EntityId("programme")).call(Network.setMachine).invoke("acme/reader"): Unit
    assertEquals(k.request("POST", s"/v1/casino/backoffice/${k.brand}/attribution/signing-keys",
      headers = k.asOperator()).status, 201)
    k

  override def afterAll(): Unit = kit.stop()

  private def ggrOnTopic(day: String): Int =
    kit.attributionFacts.count { case d: GgrDaily => d.reportingDay == day; case _ => false }

  /** The `ggr-daily` facts for `day` in the programme's feed, read page by page from the start. */
  private def ggrInFeed(day: String): Int =
    val base  = s"/v1/casino/brands/${kit.brand}/attribution/networks/programme/feed?limit=500"
    var path  = Option(base)
    var count = 0
    while path.isDefined do
      val r = kit.request("GET", path.get, headers = kit.asMachine("acme", "reader"))
      assertEquals(r.status, 200, r.text)
      val facts = r.json.get("facts").elements.asScala.toVector
      count += facts.count(f => f.get("type").asText == "ggr-daily" && f.get("reporting_day").asText == day)
      path = Option.when(facts.nonEmpty)(s"$base&cursor=${r.json.get("cursor").asText}")
    count

  test(s"$Players bound players' day closes in one run under the 5-minute period; every ggr-daily is out within 60 s") {
    val days = DayClock(kit)
    val day  = days.evening()
    val wallets = (1 to Players).map { _ =>
      val player = kit.bindPlayer(Some(kit.network.mint("aff-1", kit.fresh("lnk"))))
      val w      = WalletScript(kit, player)
      w.deposit(10_000)
      w.bet("r-1", 500)
      w.win("r-1", 200)
      w
    }
    val players = wallets.map(_.player).toSet
    kit.eventually(s"$Players days open", 15.minutes) {
      val open = kit.views.forView(OpenDays).ask(OpenDays.openBefore, 10_000, "day" -> day.plusDays(1).toString)
        .count(r => players.contains(r.player) && r.day == day.toString)
      Option.when(open == Players)(())
    }
    // Every leg in: each tally holds its stake and its win before the close.
    // (DayClock.played reads one page of the view, too few for 2,000 rows: the tallies are read instead.)
    kit.eventually("every leg in its tally", 5.minutes) {
      Option.when(wallets.forall { w =>
        val seen = kit.components.forEventSourcedEntity(EntityId(DayTally.id(w.player, "EUR", day.toString))).call(DayTally.get).invoke().seen
        w.legs.forall(seen.contains)
      })(())
    }

    days.at(day.plusDays(1), LocalTime.of(0, 21))
    val started = System.nanoTime()
    val closing = DayCloser.closing(kit.components, kit.clock).run()
    val ran     = (System.nanoTime() - started).nanos
    assert(closing.closed.count(id => players.exists(p => id.startsWith(p))) == Players, s"closed ${closing.closed.size}")
    assert(ran < 5.minutes, s"the run took ${ran.toSeconds} s")

    // Waited for up to 5 minutes so a miss is measured, then held to SC-004's 60 seconds.
    def waited(what: String)(count: => Int): FiniteDuration =
      val deadline = System.nanoTime() + 2.minutes.toNanos
      var n = count
      while n < Players && System.nanoTime() < deadline do { Thread.sleep(250); n = count }
      val at = (System.nanoTime() - started).nanos
      println(s"ScaleSuite: $n of $Players ggr-daily $what after ${at.toMillis} ms")
      if n < Players then
        val emitted = kit.attributionFacts.collect { case d: GgrDaily if d.reportingDay == day.toString => d.pseudonym }.toSet
        val key     = Pseudonyms.keyBytes(kit.testKit.secrets.get(Pseudonyms.secretName("programme")).get)
        wallets.filterNot(w => emitted.contains(Pseudonyms.derive(key, w.player))).take(5).foreach { w =>
          val tally = kit.components.forEventSourcedEntity(EntityId(DayTally.id(w.player, "EUR", day.toString))).call(DayTally.get).invoke()
          println(s"ScaleSuite: missing ${w.player}: record ${kit.record(w.player)}; tally $tally; on the topic ${kit.attributionMessages
            .count(_.message.key.contains(Pseudonyms.derive(key, w.player)))} facts")
        }
      assertEquals(n, Players, s"ggr-daily $what")
      at
    val onTopic = waited("on the topic")(ggrOnTopic(day.toString))
    val inFeed  = waited("in the feed")(ggrInFeed(day.toString))
    println(s"ScaleSuite: closing run ${ran.toMillis} ms; all on the topic ${onTopic.toMillis} ms; all in the feed ${inFeed.toMillis} ms")
    assert(onTopic < 60.seconds, s"the topic held them after ${onTopic.toSeconds} s")
    assert(inFeed < 60.seconds, s"the feed held them after ${inFeed.toSeconds} s")
  }

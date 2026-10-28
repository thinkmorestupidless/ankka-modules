package io.eitheror.casino.attribution.features

import io.eitheror.casino.attribution.Json
import io.eitheror.casino.attribution.it.{DayClock, FakeNetwork, Kit, WalletScript}
import io.eitheror.casino.attribution.tag.Pseudonyms
import io.eitheror.casino.contract.facts.*
import io.eitheror.casino.contract.money.{Currency, MinorUnits}

import java.nio.charset.StandardCharsets.UTF_8
import java.time.LocalDate
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

/**
 * features/attribution/facts.feature (US4): a bound player's money and play as facts. A kit of its own
 * (build.sbt's `own`): it closes reporting days. "No fact carries a personal field" reads every fact the
 * scenarios before it emitted.
 */
class FactsFeatures extends AttributionFeatures("facts.feature"):

  private lazy val own: Kit =
    val k = Kit()
    k.addNetwork(): Unit
    k.makePseudonymKey()
    k
  override protected def kit: Kit = own

  private lazy val streamer: FakeNetwork =
    val n = FakeNetwork("streamer")
    own.addNetwork(n, legacy = false, codes = false, roundStream = true): Unit
    own.makePseudonymKey("streamer")
    n
  private var streamerStarted = false

  override def afterAll(): Unit =
    own.stop()
    if streamerStarted then streamer.stop()

  private lazy val days = DayClock(own)

  // ── a scenario's state ──────────────────────────────────────────────────────────────────────────

  private val players                = mutable.Set.empty[String]
  private var wallet: WalletScript   = null
  private var network: String        = "programme"
  private var payment: String        = null
  private var day: LocalDate         = null
  private var expected: (String, Option[Long]) = null
  private var inspected: Vector[com.fasterxml.jackson.databind.JsonNode] = Vector.empty

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    wallet = null; network = "programme"; payment = null; day = null; expected = null; inspected = Vector.empty

  // ── helpers ─────────────────────────────────────────────────────────────────────────────────────

  private val EUR = Currency.unsafe("EUR")
  private def eur(v: Long) = MinorUnits.unsafe(v)

  private def player = wallet.player
  private def p: String =
    Pseudonyms.derive(Pseudonyms.keyBytes(kit.testKit.secrets.get(Pseudonyms.secretName(network)).get), player)

  private def attributed(on: String = "programme"): Unit =
    network = on
    val fake = if on == "programme" then kit.network else { streamerStarted = true; streamer }
    val id   = kit.bindPlayer(Some(fake.mint("aff-1", kit.fresh("lnk"), iat = kit.now)))
    kit.eventually("bound")(kit.record(id).binding): Unit
    players += id
    wallet = WalletScript(kit, id)

  private def facts: Vector[PlayerAttributionFact] =
    kit.attributionFacts.collect { case f: PlayerAttributionFact if f.pseudonym == p => f }

  private def kindOf(f: AttributionFact): String = f.factId.takeWhile(_ != '/')

  private def json(f: AttributionFact) = Json.parse(String(AffiliatesAttribution.serializer.toBytes(f), UTF_8)).get

  private def capture(amount: Long = 5000): String =
    val pay = kit.fresh("pay")
    kit.publish(PaymentsPayments, PaymentCaptured(pay, player, "psp", eur(amount), EUR, kit.now))
    pay

  private def settled(): Unit = Thread.sleep(1500)

  private def dailyFor(d: LocalDate): GgrDaily =
    kit.eventually(s"the ggr-daily for $d")(facts.collectFirst { case g: GgrDaily if g.reportingDay == d.toString => g })

  // ── deposits ────────────────────────────────────────────────────────────────────────────────────

  Given("an attributed player with no deposit") { () => attributed() }
  Given("an attributed player who has deposited before") { () =>
    attributed()
    val first = capture(1000)
    kit.eventually("the earlier deposit")(facts.collectFirst { case d: Deposit if d.reference == first => d }): Unit
  }
  When("a deposit is captured") { () => payment = capture() }
  Then("a {string} fact and a {string} fact are emitted, each with the amount, the currency and the payment reference") {
    (a: String, b: String) =>
      for kind <- Vector(a, b) do
        val f = kit.eventually(s"the $kind fact")(facts.find(f => kindOf(f) == kind))
        val j = json(f)
        assertEquals((j.get("amount").asLong, j.get("currency").asText, j.get("reference").asText), (5000L, "EUR", payment))
  }
  Then("the {string} fact is emitted once in the player's life") { (kind: String) =>
    val later = capture(700)
    kit.eventually("the later deposit")(facts.collectFirst { case d: Deposit if d.reference == later => d }): Unit
    settled()
    assertEquals(facts.count(f => kindOf(f) == kind), 1)
  }
  Then("only a {string} fact is emitted") { (kind: String) =>
    kit.eventually("the deposit")(facts.collectFirst { case d: Deposit if d.reference == payment => d }): Unit
    settled()
    assertEquals(facts.filter(f => json(f).path("reference").asText == payment).map(kindOf), Vector(kind))
    assertEquals(facts.count(f => kindOf(f) == "first-deposit"), 1)
  }

  // ── the day ─────────────────────────────────────────────────────────────────────────────────────

  Given("an attributed player who played during a day") { () =>
    day = days.evening()
    attributed()
    wallet.deposit(5000)
    wallet.bet("r-1", 1000)
    wallet.win("r-1", 300)
  }
  When("the brand's reporting day closes") { () => days.close(day, wallet) }
  Then("one {string} fact is emitted for the player and the day") { (kind: String) =>
    assertEquals(kindOf(dailyFor(day)), kind)
    settled()
    assertEquals(facts.count(f => kindOf(f) == kind), 1)
  }
  Then("it holds total stake, total win and their difference as signed minor units") { () =>
    val d = dailyFor(day)
    assertEquals((d.stake.value, d.win.value, d.ggr.value), (1000L, 300L, 700L))
    assertEquals(json(d).get("ggr").isIntegralNumber, true)
  }

  Given("a round of a closed day that settles after the close") { () =>
    day = days.evening()
    attributed()
    wallet.deposit(5000)
    wallet.bet("r-late", 1000)
    days.close(day, wallet)
    dailyFor(day): Unit
  }
  When("it settles") { () => wallet.win("r-late", 400) }
  Then("a {string} fact is emitted for that day with the difference") { (kind: String) =>
    val a = kit.eventually("the adjustment")(facts.collectFirst { case a: GgrAdjustment => a })
    assertEquals((kindOf(a), a.reportingDay, a.ggr.value), (kind, day.toString, -400L))
  }
  Then("the day's earlier fact is not emitted again") { () =>
    settled()
    assertEquals(facts.count(_.isInstanceOf[GgrDaily]), 1)
  }

  // ── the round stream ────────────────────────────────────────────────────────────────────────────

  Given("a network whose configuration at the brand opts into the round stream") { () => attributed("streamer") }
  Given("a network with the default configuration at the brand") { () => attributed() }
  When("a round settles") { () =>
    day = days.evening()
    wallet.deposit(5000)
    wallet.bet("r-s", 1000)
    wallet.win("r-s", 400)
  }
  Then("a {string} fact is emitted with the round's stake, win and GGR") { (kind: String) =>
    val r = kit.eventually("the round")(facts.collectFirst { case r: GgrRound => r })
    assertEquals((kindOf(r), r.stake.value, r.win.value, r.ggr.value), (kind, 1000L, 400L, 600L))
  }
  Then("no {string} fact is emitted for it") { (kind: String) =>
    days.played(wallet, day)
    settled()
    assert(!facts.exists(f => kindOf(f) == kind), facts.map(_.factId).toString)
  }
  private def dailyAtClose(): Unit =
    days.close(day, wallet)
    assertEquals(dailyFor(day).ggr.value, 600L)
  Then("the daily fact is still emitted at the close") { () => dailyAtClose() }
  Then("the daily fact is emitted at the close") { () => dailyAtClose() }

  // ── the outline ─────────────────────────────────────────────────────────────────────────────────

  Given("an attributed player") { () => attributed() }
  When("restricted funds are awarded") { () =>
    val award = kit.fresh("award")
    wallet.award(award, 2000)
    expected = award -> Some(2000L)
  }
  When("a deposit is charged back") { () =>
    val pay = capture()
    kit.publish(PaymentsPayments, PaymentDisputeOpened(pay, player, "psp", eur(5000), EUR, "fraud", kit.now))
    expected = pay -> Some(5000L)
  }
  private def withdrawal(f: (String, String) => CasinoWithdrawalsFact): Unit =
    val payout = kit.fresh("po")
    kit.publish(CasinoWithdrawals, f(player, payout))
    expected = payout -> Some(1500L)
  When("a withdrawal is requested") { () => withdrawal((pl, po) => WithdrawalRequested(pl, po, eur(1500), EUR, "web", kit.now)) }
  When("a withdrawal is completed") { () => withdrawal((pl, po) => WithdrawalCompleted(pl, po, eur(1500), EUR, kit.now)) }
  When("a withdrawal fails") { () => withdrawal((pl, po) => WithdrawalFailed(pl, po, eur(1500), EUR, "bank", kit.now)) }
  When("a withdrawal is reversed") { () => withdrawal((pl, po) => WithdrawalReversed(pl, po, eur(1500), EUR, "returned", kit.now)) }
  Then("a {string} fact is emitted with its amount where there is one and the reference of the thing it is about") { (kind: String) =>
    val (reference, amount) = expected
    val f = kit.eventually(s"the $kind fact")(facts.find(f => kindOf(f) == kind && json(f).path("reference").asText == reference))
    assertEquals(amount.map(_ => json(f).get("amount").asLong), amount)
  }

  // ── nothing personal ────────────────────────────────────────────────────────────────────────────

  private val personal = Set("name", "first_name", "last_name", "full_name", "username", "email", "address", "ip", "ip_address",
    "user_agent", "date_of_birth", "dob", "birth_date", "document", "document_number", "phone", "player_id", "fingerprint")

  Given("a fact of any kind") { () =>
    if kit.attributionFacts.isEmpty then
      attributed()
      capture(): Unit
      kit.eventually("a deposit")(facts.collectFirst { case d: Deposit => d }): Unit
  }
  When("a reader inspects it") { () =>
    inspected = kit.attributionMessages.map(m => Json.parse(String(m.message.payload, UTF_8)).get)
  }
  Then("it holds no name, address, email, date of birth, document or other personal field") { () =>
    assert(inspected.nonEmpty)
    for j <- inspected do
      val keys = j.fieldNames.asScala.toSet
      assertEquals(keys.intersect(personal), Set.empty[String], j.toString)
      val text = j.toString
      assert(!players.exists(text.contains), s"a player id in $text")
  }

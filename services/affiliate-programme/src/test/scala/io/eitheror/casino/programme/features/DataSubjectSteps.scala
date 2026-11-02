package io.eitheror.casino.programme.features

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.programme.accounts.{Affiliate, ErasureRules, Secrets}
import io.eitheror.casino.programme.brands.Plan
import io.eitheror.casino.programme.commission.FactType
import io.eitheror.casino.programme.it.{Kit, Reply, Scene}
import io.eitheror.casino.programme.players.{PlayerAttribution, PlayerState}
import io.eitheror.casino.programme.sealing.{Sealed, Sealing}

import java.sql.{DriverManager, Types}
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration
import scala.jdk.CollectionConverters.*

/**
 * features/programme/data-subjects.feature (US8). A service of its own (build.sbt): an erasure's hold is
 * five years, and the scenario moves this service's clock past it without moving any other suite's.
 */
class DataSubjectSteps extends ProgrammeFeatures("data-subjects.feature"):
  override protected def kit: Kit = DataSubjectSteps.kit
  override def afterAll(): Unit = { super.afterAll(); DataSubjectSteps.kit.stop() }

  private var aff: String      = null
  private var scene: Scene     = null
  private var player: String   = null
  private var filed: Reply     = null
  private var reads: Vector[Reply] = Vector.empty
  private var before: (Int, Long, Int) = (0, 0L, 0)

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    aff = null; scene = null; player = null; filed = null; reads = Vector.empty

  private val name  = "Odalys Brightwater"
  private val iban  = "GB33BUKB20201555555555"
  private def email = s"${id("odalys")}@subject.example"
  private def mgr   = kit.asManager()

  private def tableText(): Vector[String] =
    val c = DriverManager.getConnection(kit.testKit.jdbcUrl, "ankka", "ankka")
    try
      val tables = Iterator.unfold(c.getMetaData.getTables(null, null, "%", Array("TABLE"))) { rs =>
        Option.when(rs.next())((rs.getString("TABLE_SCHEM") + "." + rs.getString("TABLE_NAME"), rs))
      }.toVector.filterNot(t => t.startsWith("pg_catalog.") || t.startsWith("information_schema."))
      tables.flatMap { t =>
        val rs = c.createStatement().executeQuery(s"""SELECT * FROM ${t.split('.').map(p => s""""$p"""").mkString(".")}""")
        val m  = rs.getMetaData
        Iterator.continually(rs).takeWhile(_.next()).map(r => (1 to m.getColumnCount).flatMap(i => m.getColumnType(i) match
          case Types.BINARY | Types.VARBINARY | Types.LONGVARBINARY => Option(r.getBytes(i)).map(String(_, UTF_8))
          case _                                                     => Option(r.getString(i))
        ).mkString(" | ")).toVector
      }
    finally c.close()

  private def account: io.eitheror.casino.programme.accounts.AffiliateState =
    kit.components.forEventSourcedEntity(EntityId(aff)).call(Affiliate.get).invoke()

  // ── the identity ────────────────────────────────────────────────────────────────────────────────

  Given("an affiliate account") { () =>
    val r = kit.request("POST", "/v1/affiliates/goldvibe/apply", Some(s"""{"email":"$email","name":"$name"}"""), kit.fromInternet)
    assertEquals(r.status, 201, r.text)
    aff = r.json.get("affiliateId").asText
    assertEquals(kit.request("POST", s"/v1/affiliates/goldvibe/accounts/$aff/approve",
      Some(s"""{"planId":"${kit.plan("goldvibe", kit.fresh("p"))}"}"""), mgr).status, 200)
  }
  When("its identity and payment fields are written") { () =>
    val r = kit.request("PATCH", s"/v1/my/$aff/account", Some(s"""{"companyName":"Brightwater Media","paymentMethod":"IBAN $iban"}"""),
      kit.asAffiliate(aff))
    assertEquals(r.status, 200, r.text)
  }
  Then("they are personal data under the affiliate") { () =>
    val key = Secrets(kit.secrets).existingDataKey(aff).toOption.flatten.getOrElse(fail("no data key of the affiliate's"))
    val identity = Sealing.open(account.identity, key, aff).fold(e => fail(e), identity => identity)
    assertEquals((identity.name, identity.companyName, identity.paymentMethod), (name, Some("Brightwater Media"), Some(s"IBAN $iban")))
    // Sealed under this affiliate: another's id as the associated data does not open it.
    assert(Sealing.open(account.identity, key, "someone-else").isLeft)
  }
  Then("nothing the programme stores holds a readable name, email or payment details") { () =>
    Thread.sleep(1500)
    val rows  = tableText()
    val leaks = Seq(name, "Brightwater", ids("odalys"), iban).flatMap(v => rows.filter(_.toLowerCase.contains(v.toLowerCase)).map(r => s"$v: ${r.take(200)}"))
    assert(leaks.isEmpty, leaks.mkString("\n"))
  }

  // ── erasure ─────────────────────────────────────────────────────────────────────────────────────

  Given("a closed affiliate account") { () =>
    scene = Scene(kit, planType = Plan.Cpa, cpa = 2_500)
    aff = scene.affiliate
    val p = scene.player()
    scene.send(scene.facts.firstDeposit(p, 5_000, kit.now)): Unit
    scene.rowsWhen("the CPA")(rs => scene.sum(rs, FactType.Cpa) == 2_500): Unit
    kit.owed(aff, "goldvibe", "EUR")
    val period = kit.now.toString.take(7)
    ids("period") = period
    val closed = kit.request("POST", s"/v1/affiliates/goldvibe/payouts/close?period=$period", headers = mgr)
    assert(closed.text.contains(aff), closed.text)
    kit.eventually("the payout listed")(Option(payouts()).filter(_.nonEmpty)): Unit
    assertEquals(kit.request("POST", s"/v1/affiliates/goldvibe/accounts/$aff/close", headers = mgr).status, 200)
    before = (scene.rows.size, payoutTotal(), auditCount())
  }
  private def payoutTotal(): Long =
    Option(kit.request("GET", s"/v1/my/$aff/payouts", headers = kit.asAffiliate(aff)).json).toVector.flatMap(_.elements().asScala)
      .map(_.get("totalPayable").asLong).sum
  private def payouts(): Vector[JsonNode] =
    Option(kit.request("GET", s"/v1/affiliates/goldvibe/payouts?period=${ids("period")}", headers = mgr).json).toVector
      .flatMap(_.elements().asScala).filter(_.get("affiliateId").asText == aff)
  private def auditCount(): Int =
    Option(kit.request("GET", s"/v1/affiliates/goldvibe/accounts/$aff/audit", headers = mgr).json).toVector.flatMap(_.elements().asScala).size

  When("a manager files its erasure") { () =>
    filed = kit.request("POST", s"/v1/affiliates/goldvibe/accounts/$aff/erasure", headers = mgr)
    assertEquals(filed.status, 202, filed.text)
  }
  Then("the request carries a retention hold until the end of the retention period finance names") { () =>
    val filedAt = java.time.Instant.parse(filed.json.get("filedAt").asText)
    assertEquals(filed.json.get("holdUntil").asText, ErasureRules.holdUntil(filedAt, 1825).toString)
    assertEquals(filed.json.get("status").asText, ErasureRules.Held)
    // Held: the identity still opens until the hold ends.
    assert(Secrets(kit.secrets).existingDataKey(aff).toOption.flatten.isDefined)
  }
  Then("when the retention hold lapses the account's identity fields read as erased") { () =>
    kit.moveClock(java.time.Instant.parse(filed.json.get("holdUntil").asText).plus(Duration.ofMinutes(1)))
    kit.eventually("erased", scala.concurrent.duration.Duration(60, "s"))(account.erased): Unit
    assertEquals(account.identity, Sealed.erased)
    val read = kit.request("GET", s"/v1/affiliates/goldvibe/accounts/$aff", headers = mgr)
    assertEquals((read.json.get("email").asText, read.json.get("name").asText), ("erased", "erased"))
  }
  Then("every fact, payout amount and audit record remains") { () =>
    val (rows, _, audits) = before
    assertEquals(scene.rows.size, rows)
    val listed = kit.request("GET", s"/v1/affiliates/goldvibe/payouts?period=${ids("period")}", headers = mgr)
    assertEquals(payouts().map(_.get("totalPayable").asLong).sum, 2_500L, s"${ids("period")} ${listed.status} ${listed.text.take(600)}")
    kit.eventually("the erasure audited beside what was there")(Option(auditCount()).filter(_ > audits)): Unit
  }

  // ── players ─────────────────────────────────────────────────────────────────────────────────────

  Given("facts about an attributed player") { () =>
    scene = Scene(kit, planType = Plan.Cpa, cpa = 1_000)
    player = scene.player()
    scene.send(scene.facts.firstDeposit(player, 3_000, kit.now)): Unit
    scene.send(scene.facts.deposit(player, 3_000, kit.now)): Unit
  }
  When("a manager or an affiliate reads the player") { () =>
    def read(r: => Reply) = kit.eventually("the player listed")(Option(r).filter(x =>
      Option(x.json).toVector.flatMap(_.elements().asScala).exists(p => p.get("playerRef").asText == player && p.get("hasFtd").asBoolean)))
    reads = Vector(read(kit.request("GET", s"/v1/affiliates/goldvibe/accounts/${scene.affiliate}/players", headers = mgr)),
      read(kit.request("GET", s"/v1/my/${scene.affiliate}/players", headers = kit.asAffiliate(scene.affiliate))))
  }
  Then("they see the pseudonym the brand gave, the country and the figures") { () =>
    reads.foreach { r =>
      val p = r.json.elements().asScala.find(_.get("playerRef").asText == player).get
      assertEquals((p.get("country").asText, p.get("deposits").get("EUR").get("sum").asLong), ("ZA", 3_000L), r.text)
    }
  }
  Then("nothing that names the player") { () =>
    reads.foreach { r =>
      val fields = r.json.elements().asScala.flatMap(_.fieldNames().asScala).toSet
      assert(!fields.exists(f => Set("playerId", "name", "email", "username", "firstName", "lastName").contains(f)), fields.toString)
    }
  }

  // ── a player closed ─────────────────────────────────────────────────────────────────────────────

  private def state(p: String): PlayerState =
    kit.components.forEventSourcedEntity(PlayerAttribution.entityId("goldvibe", p)).call(PlayerAttribution.get).invoke()

  Given("a brand that closes or erases a player") { () =>
    scene = Scene(kit, planType = Plan.Cpa, cpa = 1_000)
    val clicked = scene.facts.copy(clickId = Some(id("click")))
    Seq("closed", "erased").foreach { kind =>
      val p = kit.fresh("ps")
      ids(kind) = p
      scene.send(clicked.registration(p, kit.now)): Unit
      scene.send(clicked.firstDeposit(p, 4_000, kit.now)): Unit
      scene.send(clicked.deposit(p, 4_000, kit.now)): Unit
      assertEquals((state(p).country, state(p).clickId), (Some("ZA"), Some(ids("click"))))
    }
  }
  When("the {string} fact arrives") { (_: String) =>
    scene.send(scene.facts.playerClosed(ids("closed"), kit.now, category = "self-exclusion")): Unit
    scene.send(scene.facts.playerClosed(ids("erased"), kit.now, category = "erasure")): Unit
  }
  Then("the programme forgets the pseudonym's country and click details") { () =>
    Seq("closed", "erased").foreach { k =>
      val s = state(ids(k))
      assertEquals((s.country, s.clickId), (None, None), k)
    }
  }
  Then("the programme keeps the facts' amounts") { () =>
    Seq("closed", "erased").foreach(k => assertEquals(state(ids(k)).deposits.get("EUR").map(_.sum), Some(4_000L), k))
    scene.rowsWhen("both CPAs")(rs => scene.sum(rs, FactType.Cpa) == 2_000L): Unit
  }
  Then("the programme cannot tell an erasure from a closure and does not need to") { () =>
    val (a, b) = (state(ids("closed")), state(ids("erased")))
    def kept(s: PlayerState) = s.copy(pseudonym = "", closed = None, guard = None, seen = Vector.empty)
    assertEquals(kept(a), kept(b))
    assert(a.closed.isDefined && b.closed.isDefined)
  }

object DataSubjectSteps:
  lazy val kit: Kit = Kit()

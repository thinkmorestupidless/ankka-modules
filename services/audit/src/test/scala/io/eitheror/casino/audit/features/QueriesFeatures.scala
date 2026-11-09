package io.eitheror.casino.audit.features

import io.eitheror.casino.audit.exports.{Csv, Export}
import io.eitheror.casino.audit.it.{Kit, Reply}
import io.eitheror.casino.audit.stored.Source
import io.eitheror.casino.contract.facts.{AuditChange, AuditRecord}

import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant
import scala.concurrent.duration.*

/** features/audit/queries.feature (US3), through `/v1/audit-records` and `/v1/audit-exports`. */
class QueriesFeatures extends AuditFeatures("queries.feature"):
  private var officer: Seq[(String, String)] = Nil
  private var answer: Reply                  = null
  private var published: Vector[(Source, AuditRecord)] = Vector.empty
  private var ownBefore: Set[String]          = Set.empty

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    officer = Nil; answer = null; published = Vector.empty; ownBefore = Set.empty

  private val from = Instant.parse("2026-10-01T00:00:00Z")
  private val to   = Instant.parse("2026-11-01T00:00:00Z")

  private def publish(source: Source, r: AuditRecord): Unit =
    kit.publish(source match { case Source.Brand(s) => s; case _ => "payments" }, r)
    published :+= source -> r

  private def allStored(): Unit = published.foreach((s, r) => stored(s, r))

  private val gold = Source.Brand("goldvibe")
  private val spin = Source.Brand("spinvibe")

  Given("a compliance officer with the role for brand {string}") { (b: String) => officer = kit.asBrandCompliance(b, id("officer")) }

  Given("a compliance officer with the installation's role") { () => officer = kit.asInstallationCompliance(id("officer")) }

  When("they query the shared audit by a player's pseudonym and a period") { () =>
    (1 to 3).foreach(i => publish(gold, Kit.record(id = id(s"g$i"), subjectId = id("player"), occurredAt = from.plusSeconds(3600L * (4 - i)))))
    publish(spin, Kit.record(brand = "spinvibe", id = id("s1"), subjectId = id("player"), occurredAt = from.plusSeconds(60)))
    allStored()
    val pseudonym = stored(gold, published.head._2).subject
    answer = kit.query(officer, "brand" -> "goldvibe", "subject" -> pseudonym, "from" -> from.toString, "to" -> to.toString, "limit" -> "2")
  }

  Then("every matching record of brand {string} is listed in order of occurrence, paged") { (b: String) =>
    assertEquals(answer.status, 200, answer.text)
    val next  = kit.query(officer, "brand" -> b, "subject" -> answer.records.head.get("subject").asText, "from" -> from.toString,
      "to" -> to.toString, "after" -> answer.json.get("next").asText)
    val times = (answer.records ++ next.records).map(r => Instant.parse(r.get("occurredAt").asText))
    assertEquals(times.size, 3)
    assertEquals(times, times.sorted)
  }

  Then("no record of another brand is listed") { () => assert(answer.records.forall(_.get("brand").asText == "goldvibe")) }

  When("they query the shared audit across brands for action {string} above {string} minor units in a period") { (action: String, min: String) =>
    def adjust(brand: String, name: String, amount: Long) = Kit.record(brand = brand, id = id(name), action = action, category = "money",
      correlationId = id("corr"), occurredAt = from.plusSeconds(600),
      changes = Vector(AuditChange("amount", None, Some(amount.toString)), AuditChange("currency", None, Some("EUR"))))
    publish(gold, adjust("goldvibe", "g", min.toLong + 1))
    publish(spin, adjust("spinvibe", "s", min.toLong * 2))
    publish(gold, adjust("goldvibe", "small", min.toLong - 1))
    allStored()
    answer = kit.query(officer, "action" -> action, "amountMin" -> (min.toLong + 1).toString, "correlation" -> id("corr"),
      "from" -> from.toString, "to" -> to.toString)
  }

  Then("records from every brand are listed") { () =>
    assertEquals(answer.status, 200, answer.text)
    assertEquals(answer.records.map(_.get("brand").asText).toSet, Set("goldvibe", "spinvibe"))
    assertEquals(answer.records.size, 2)
  }

  Then("each names its brand") { () => assert(answer.records.forall(r => Option(r.get("brand")).exists(_.asText.nonEmpty))) }

  When("they query the shared audit naming brand {string}") { (b: String) => answer = kit.query(officer, "brand" -> b) }

  Then("it is refused") { () => assertEquals((answer.status, answer.code), (403, "brand_not_in_roles")) }

  private var exportKey = ""

  When("they export the brand's records for a period") { () =>
    (1 to 2).foreach(i => publish(gold, Kit.record(id = id(s"e$i"), subjectId = id("player"), occurredAt = from.plusSeconds(60L * i))))
    allStored()
    ownBefore = kit.own(Export.Action).map(_.key).toSet
    exportKey = id("export")
    answer = kit.request("POST", "/v1/audit-exports", Some(s"""{"key":"$exportKey","brand":"goldvibe","from":"$from","to":"$to"}"""), officer)
    assertEquals(answer.status, 202, answer.text)
    kit.eventually(60.seconds) {
      val r = kit.request("GET", s"/v1/audit-exports/$exportKey", headers = officer)
      Option.when(r.json.get("status").asText == Export.Done)(r)
    }: Unit
  }

  Then("a file in the regulator's agreed format is produced with every record in that period, in pseudonyms") { () =>
    val text = kit.testKit.storage.bucket(Export.Bucket).getBytes(s"goldvibe/$exportKey.csv").map(b => String(b._2, UTF_8)).get
    val rows = text.linesIterator.toVector
    assertEquals(rows.head, Csv.header)
    val keys = published.map((s, r) => keyOf(s, r)).toSet
    assert(keys.forall(k => rows.exists(_.endsWith(k))), rows.mkString("\n"))
    assert(!text.contains(id("player")), "a real id in the export")
  }

  Then("the export is a record naming who exported what range") { () =>
    val made = kit.eventually()(kit.own(Export.Action).find(r => !ownBefore(r.key)))
    assert(made.actor.nonEmpty && made.actor != id("officer"))
    assert(made.changes.exists(c => c.field == "range" && c.after.contains(s"$from..$to")), made.changes.toString)
  }

  Given("any query or export of the shared audit") { () =>
    officer = kit.asBrandCompliance("goldvibe", id("officer"))
    publish(gold, Kit.record(id = id("r"), correlationId = id("corr")))
    allStored()
    ownBefore = kit.own("audit.query").map(_.key).toSet
  }

  When("it is run") { () =>
    answer =
      if officer.nonEmpty then kit.query(officer, "brand" -> "goldvibe", "correlation" -> id("corr"), "from" -> from.toString)
      else kit.query(kit.asBrandCompliance("goldvibe", id("officer")), "brand" -> "goldvibe", "from" -> "2001-01-01T00:00:00Z")
    assertEquals(answer.status, 200, answer.text)
  }

  Then("the shared audit writes a record of it naming the actor, the filters, the range and the count") { () =>
    val made = kit.eventually()(kit.own("audit.query").find(r => !ownBefore(r.key)))
    assertEquals(made.changes.map(_.field).toSet, Set("filters", "range", "count"))
    assertEquals(made.changes.find(_.field == "count").flatMap(_.after), Some("1"))
    assert(made.actor.nonEmpty && made.actor != id("officer"))
  }

  Then("that record is not itself queried about recursively") { () =>
    val recorded = kit.own("audit.query").size
    assertEquals(kit.query(kit.asInstallationCompliance(), "service" -> "audit").status, 200)
    Thread.sleep(500)
    assertEquals(kit.own("audit.query").size, recorded)
  }

  Given("a query for a period older than the brand's retention") { () => officer = Nil }

  Then("it answers what is retained") { () => assert(answer.records.forall(r => Instant.parse(r.get("occurredAt").asText).isAfter(Instant.parse("2019-11-09T08:00:00Z")))) }

  Then("it says from when") { () =>
    assertEquals(Instant.parse(answer.json.get("retainedFrom").asText), Instant.parse("2019-11-09T08:00:00Z"))
  }

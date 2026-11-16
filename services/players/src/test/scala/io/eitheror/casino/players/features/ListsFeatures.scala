package io.eitheror.casino.players.features

import com.fasterxml.jackson.databind.JsonNode
import io.eitheror.casino.contract.personal.Personal
import io.eitheror.casino.players.it.{Reply, Rows}
import io.eitheror.casino.players.player.{PlayerRow, Status}

import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import java.time.{LocalDate, ZoneOffset}
import java.util.UUID
import scala.jdk.CollectionConverters.*

/**
 * features/players/lists.feature (spec 030 T066 to T068). The players are rows written into the view's
 * table under a tag of the scenario's own in their display name, and every read searches by it, so the rows
 * earlier suites left in the shared kit are never counted.
 */
class ListsFeatures extends PlayersFeatures("lists.feature"):
  protected def pending = Map(
    "a list's status says how fresh its views are" ->
      ("T051 answers each view's asOf (FreshnessSuite); no answer can say the views are catching up while " +
        "rebuilding is always false (ankka 0.16.3 reports no progress, research V2): open for the lead"),
    "a player's activity is read for a period, page after page, with its total" -> "T067 (pending)",
    "an operator reads a player's notes" -> "T067 (pending)",
    "an operator reads a player session with its game sessions" -> "T068 (pending)",
    "an operator reads a game session's bets" -> "T068 (pending)",
    "an operator finds a player's bets by round, by transaction or by status, with what was staked and won" -> "T068 (pending)"
  )

  private var tag     = ""
  private var seeded  = Vector.empty[PlayerRow]
  private var pages   = Vector.empty[JsonNode]
  private var reply: Reply = null
  private var wanted: PlayerRow => Boolean = _ => true

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    tag = s"List${UUID.randomUUID().toString.take(8)}"; seeded = Vector.empty; pages = Vector.empty; reply = null; wanted = _ => true

  private def read(params: (String, String)*): Reply =
    val q = (("search" -> tag) +: params).map((k, v) => s"$k=${URLEncoder.encode(v, UTF_8)}").mkString("&")
    kit.request("GET", kit.operatorPath(s"/players?$q"), None, kit.asOperator())

  private def ids(node: JsonNode): Vector[String] = node.get("items").elements().asScala.map(_.get("playerId").asText).toVector

  private def today: LocalDate = LocalDate.ofInstant(kit.now(), ZoneOffset.UTC)
  private def md(d: LocalDate) = f"${d.getMonthValue}%02d-${d.getDayOfMonth}%02d"

  private def row(i: Int, country: String = "BR", currency: String = "BRL", potVip: Boolean = false, online: Boolean = false,
      verified: Boolean = false, birthday: String = ""): PlayerRow =
    PlayerRow(UUID.randomUUID().toString, Status.Active, displayName = Personal(s"$tag $i"), primaryEmail = Personal(s"$tag-$i@example.test"),
      country = Some(country), currency = currency, potVip = potVip, registeredAt = 1_800_000_000_000L - i * 1000L,
      kycVerified = verified, birthday = Personal(birthday), openSessions = if online then Vector(s"ps-$i") else Vector.empty)

  Given("a brand with {string} players") { (n: String) =>
    seeded = (0 until n.toInt).map(row(_)).toVector
    Rows.players(kit, seeded)
  }

  When("an operator reads the player list page by page") { () =>
    val first = read("page" -> "1", "pageSize" -> "200")
    assertEquals(first.status, 200, first.text)
    val count = (first.json.get("total").asLong + 199) / 200
    pages = first.json +: (2 to count.toInt).map(p => read("page" -> p.toString, "pageSize" -> "200").json).toVector
  }

  Then("every page carries the total {string}") { (total: String) =>
    assert(pages.nonEmpty)
    pages.foreach(p => assertEquals(p.get("total").asLong, total.toLong))
    assertEquals(pages.map(_.get("pageSize").asInt).distinct, Vector(200))
  }

  Then("every player appears on exactly one page, newest first") { () =>
    val listed = pages.flatMap(ids)
    assertEquals(listed.size, seeded.size)
    assertEquals(listed, seeded.sortBy(r => (-r.registeredAt, r.playerId)).map(_.playerId))
  }

  Given("a brand with players in several countries and currencies") { () =>
    seeded = Vector(
      row(0, "BR", "BRL", potVip = true, online = true, verified = true, birthday = md(today.plusDays(2))),
      row(1, "BR", "BRL", birthday = md(today.plusDays(40))),
      row(2, "PT", "EUR", potVip = true, verified = true),
      row(3, "AR", "EUR", online = true, birthday = md(today)),
      row(4, "BR", "JPY", verified = true, birthday = md(today.plusDays(7))),
      row(5, "PT", "BRL", online = true, birthday = md(today.plusDays(8)))
    )
    Rows.players(kit, seeded)
  }

  private val filters: Map[String, ((String, String), PlayerRow => Boolean)] = Map(
    "country"              -> (("country" -> "br"), _.country.contains("BR")),
    "currency"             -> (("currency" -> "EUR"), _.currency == "EUR"),
    "potential VIP"        -> (("potVip" -> "true"), _.potVip),
    "online"               -> (("online" -> "true"), _.online),
    "identity status"      -> (("identity" -> "verified"), _.kycVerified),
    "birthday within days" -> (("birthdayWithinDays" -> "7"), r => (0 to 7).map(d => md(today.plusDays(d))).contains(r.birthday.reveal))
  )

  When("an operator reads the player list filtered by {string}") { (filter: String) =>
    val (param, matches) = filters(filter)
    wanted = matches
    reply = read(param)
  }

  Then("only the matching players are listed, with their total") { () =>
    assertEquals(reply.status, 200, reply.text)
    val expected = seeded.filter(wanted).sortBy(r => -r.registeredAt).map(_.playerId)
    assert(expected.nonEmpty && expected.size < seeded.size, "the filter must tell the players apart")
    assertEquals(ids(reply.json), expected)
    assertEquals(reply.json.get("total").asLong, expected.size.toLong)
  }

  When("an operator reads the player list asking for its counts") { () => reply = read("facets" -> "true") }

  Then("each filter's values are listed with the number of players matching") { () =>
    assertEquals(reply.status, 200, reply.text)
    val f = reply.json.get("facets")
    def counts(name: String): Map[String, Long] = f.get(name).properties().asScala.map(e => e.getKey -> e.getValue.asLong).toMap
    def by[K](key: PlayerRow => K): Map[K, Long] = seeded.groupBy(key).view.mapValues(_.size.toLong).toMap
    assertEquals(counts("country"), by(_.country.get))
    assertEquals(counts("currency"), by(_.currency))
    assertEquals(counts("status"), Map(Status.Active -> 6L))
    assertEquals(counts("potVip"), by(_.potVip.toString))
    assertEquals(counts("online"), by(_.online.toString))
    assertEquals(counts("identity"), by(r => if r.kycVerified then "verified" else "unverified"))
    assertEquals(counts("birthdayWithinDays"), Map("0" -> 1L, "7" -> 3L, "30" -> 4L))
    assertEquals(counts("vipOnly"), Map("true" -> 0L))
    // a filter set counts the other filters' values among its players, and its own as if unset
    val narrowed = read("facets" -> "true", "country" -> "BR").json.get("facets")
    assertEquals(narrowed.get("country").get("PT").asLong, 2L)
    assertEquals(narrowed.get("currency").properties().asScala.map(e => e.getKey -> e.getValue.asLong).toMap, Map("BRL" -> 2L, "JPY" -> 1L))
  }

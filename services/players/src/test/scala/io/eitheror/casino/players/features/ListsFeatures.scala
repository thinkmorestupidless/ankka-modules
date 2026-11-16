package io.eitheror.casino.players.features

import com.fasterxml.jackson.databind.JsonNode
import io.eitheror.casino.contract.personal.Personal
import io.eitheror.casino.players.it.{Reply, Rows}
import io.eitheror.casino.players.player.{PlayerRow, Status}

import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import java.time.{LocalDate, ZoneOffset}
import java.util.UUID
import scala.concurrent.duration.*
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
  )

  private var tag     = ""
  private var seeded  = Vector.empty[PlayerRow]
  private var pages   = Vector.empty[JsonNode]
  private var reply: Reply = null
  private var wanted: PlayerRow => Boolean = _ => true
  private var playerId = ""
  private var noteIds  = Vector.empty[String]
  private var playerSession = ""
  private var games    = Vector.empty[String]
  private var rounds   = Vector.empty[(String, Long, Long)]

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    tag = s"List${UUID.randomUUID().toString.take(8)}"; seeded = Vector.empty; pages = Vector.empty; reply = null; wanted = _ => true
    playerId = ""; noteIds = Vector.empty; playerSession = ""; games = Vector.empty; rounds = Vector.empty

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

  // ── activity and notes (T067) ───────────────────────────────────────────────────────────────────

  private def op(method: String, path: String, body: Option[String] = None): Reply =
    kit.request(method, kit.operatorPath(path), body, kit.asOperator(subject = "op-5"))

  private val march = java.time.Instant.parse("2026-03-01T00:00:00Z")

  Given("a player with activity over a year") { () =>
    playerId = kit.activePlayer()._2
    import io.eitheror.casino.players.reads.{ActivityEntry, ActivityRow}
    // two lines in every month of 2026 but March, and sixty in March: more than a page of fifty
    val times = (1 to 12).filter(_ != 3).flatMap(m => Vector(5, 20).map(d => java.time.Instant.parse(f"2026-$m%02d-$d%02dT12:00:00Z"))) ++
      (0 until 60).map(i => march.plusSeconds(3600L * 11 * i + 60))
    times.zipWithIndex.foreach { (at, i) =>
      val id = s"test/$playerId/$i"
      kit.components.forKeyValueEntity(com.thinkmorestupidless.ankka.core.EntityId(id)).call(ActivityEntry.record)
        .invoke(ActivityRow(playerId, at.toEpochMilli, "account", "profile_updated", "player", description = s"line $i", factId = id)): Unit
    }
    kit.eventually("the activity", 30.seconds)(Option.when(
      op("GET", s"/players/$playerId/activity").json.get("total").asInt == times.size)(()))
  }

  When("an operator reads the player's activity for one month") { () =>
    reply = op("GET", s"/players/$playerId/activity?from=2026-03-01&to=2026-03-31")
  }

  private def times(r: Reply) = r.json.get("events").elements().asScala.map(e => java.time.Instant.parse(e.get("timestamp").asText)).toVector

  Then("only that month's activity is listed, newest first, with its total") { () =>
    assertEquals(reply.status, 200, reply.text)
    val ts = times(reply)
    assertEquals((ts.size, reply.json.get("total").asInt, reply.json.get("hasMore").asBoolean), (50, 60, true))
    assert(ts.forall(t => !t.isBefore(march) && t.isBefore(java.time.Instant.parse("2026-04-01T00:00:00Z"))), ts.toString)
    assertEquals(ts, ts.sortBy(_.toEpochMilli).reverse)
  }

  Then("the next page continues where the first ended") { () =>
    val next = op("GET", s"/players/$playerId/activity?from=2026-03-01&to=2026-03-31&after=${reply.json.get("next").asText}")
    val (first, second) = (times(reply), times(next))
    assertEquals((second.size, next.json.get("hasMore").asBoolean, next.json.get("total").asInt), (10, false, 60))
    assert(next.json.get("next").isNull, next.text)
    assert(second.head.isBefore(first.last), s"${first.last} then ${second.head}")
    val ids = (reply.json.get("events").elements().asScala ++ next.json.get("events").elements().asScala).map(_.get("eventId").asText).toVector
    assertEquals(ids.distinct.size, 60)
  }

  Given("a player with two notes, one pinned") { () =>
    playerId = kit.activePlayer()._2
    noteIds = Vector("the first", "the pinned").map(t => op("POST", s"/players/$playerId/notes", Some(s"""{"content":"$t"}""")).str("noteId"))
    assertEquals(op("PATCH", s"/players/$playerId/notes/${noteIds(1)}/pin").status, 200)
  }

  When("an operator reads the player's notes") { () =>
    reply = kit.eventually("both notes, one pinned", 30.seconds) {
      val r = op("GET", s"/players/$playerId/notes")
      Option.when(r.status == 200 && r.json.get("total").asInt == 2 && r.json.get("notes").get(0).get("pinned").asBoolean)(r)
    }
  }

  Then("both are listed with who added each, the pinned one first") { () =>
    val notes = reply.json.get("notes").elements().asScala.toVector
    assertEquals(notes.map(_.get("noteId").asText), Vector(noteIds(1), noteIds(0)))
    assertEquals(notes.map(n => (n.get("text").asText, n.get("author").asText, n.get("pinned").asBoolean)),
      Vector(("the pinned", "op-5", true), ("the first", "op-5", false)))
  }

  // ── sessions and bets (T068) ────────────────────────────────────────────────────────────────────

  private def signedInPlaying(gameCount: Int): Unit =
    val (email, id) = kit.activePlayer()
    playerId = id
    playerSession = kit.sessionOf(kit.signIn(email).str("token"))
    games = (1 to gameCount).toVector.map(g => kit.json("POST", kit.servicePath(s"/$id/game-sessions"), io.eitheror.casino.players.Json.obj(
      "gameId" -> s"g-$g", "currency" -> "EUR", "playerSessionId" -> playerSession), kit.asService("game-provider")).str("sessionId"))

  /** A round of `casino.rounds`, settled with `stake` and `win`, or only opened. */
  private def round(session: String, stake: Long, win: Long, settled: Boolean = true): String =
    import io.eitheror.casino.contract.facts.{CasinoRounds, RoundOpened, RoundSettled}
    import io.eitheror.casino.contract.money.{Currency, MinorUnits, SignedMinorUnits}
    val provider = UUID.randomUUID().toString.take(12)
    val roundId  = s"st8/$provider"
    val at       = kit.now()
    if settled then
      kit.publish(CasinoRounds, s"$playerId/EUR", RoundSettled(playerId, Currency.unsafe("EUR"), roundId, "st8", provider, "g-1", session,
        MinorUnits.unsafe(stake), MinorUnits.unsafe(win), SignedMinorUnits(win - stake), if win > 0 then "win" else "loss",
        Vector(s"tx-$provider"), Vector(s"ptx-$provider"), Vector.empty, None, at))
    else
      kit.publish(CasinoRounds, s"$playerId/EUR", RoundOpened(playerId, Currency.unsafe("EUR"), roundId, "st8", provider, "g-1", session, None, at))
    rounds :+= ((roundId, stake, win))
    roundId

  Given("a player session during which the player opened two game sessions") { () => signedInPlaying(2) }

  When("an operator reads the player's player sessions") { () =>
    reply = kit.eventually("the session with its games", 30.seconds) {
      val r = op("GET", s"/players/$playerId/player-sessions")
      Option.when(r.status == 200 && r.json.get("total").asInt == 1 && r.json.get("sessions").get(0).get("gameSessions").size == 2)(r)
    }
  }

  Then("the player session is listed with both game sessions") { () =>
    val s = reply.json.get("sessions").get(0)
    assertEquals((s.get("sessionId").asText, s.get("status").asText), (playerSession, "active"))
    assertEquals(s.get("gameSessions").elements().asScala.map(_.get("sessionId").asText).toSet, games.toSet)
  }

  Given("a game session with three rounds") { () =>
    signedInPlaying(1)
    Vector((100L, 0L), (200L, 500L), (50L, 50L)).foreach((stake, win) => round(games.head, stake, win))
  }

  When("an operator reads the game session's bets") { () =>
    reply = kit.eventually("the three rounds", 30.seconds) {
      val r = op("GET", s"/players/$playerId/sessions/${games.head}/bets")
      Option.when(r.status == 200 && r.json.get("total").asInt == 3)(r)
    }
  }

  Then("each round's bet and win are listed") { () =>
    val listed = reply.json.get("bets").elements().asScala.map(b =>
      (b.get("roundId").asText, b.get("stakeMinorUnits").asLong, b.get("winMinorUnits").asLong)).toSet
    assertEquals(listed, rounds.toSet)
    val summary = reply.json.get("summary")
    assertEquals((summary.get("staked").asLong, summary.get("won").asLong, summary.get("net").asLong), (350L, 550L, 200L))
  }

  Given("a player with settled and unsettled rounds") { () =>
    signedInPlaying(1)
    round(games.head, 300, 0); round(games.head, 100, 250); round(games.head, 80, 0, settled = false)
  }

  When("an operator reads the player's bets filtered by status") { () =>
    kit.eventually("every round", 30.seconds)(Option.when(op("GET", s"/players/$playerId/bets?status=all").json.get("total").asInt == 3)(()))
    reply = op("GET", s"/players/$playerId/bets?status=open")
  }

  Then("only the matching bets are listed") { () =>
    assertEquals(reply.status, 200, reply.text)
    assertEquals(reply.json.get("bets").elements().asScala.map(b => (b.get("roundId").asText, b.get("status").asText)).toVector,
      Vector((rounds(2)._1, "open")))
    val settled = op("GET", s"/players/$playerId/bets?status=settled")
    assertEquals(settled.json.get("bets").elements().asScala.map(_.get("roundId").asText).toSet, Set(rounds(0)._1, rounds(1)._1))
    assertEquals(op("GET", s"/players/$playerId/bets?roundId=${rounds(1)._1.stripPrefix("st8/")}").json.get("total").asInt, 1)
    assertEquals(op("GET", s"/players/$playerId/bets?transactionId=ptx-${rounds(0)._1.stripPrefix("st8/")}").json.get("total").asInt, 1)
    reply = settled
  }

  Then("the answer carries the total staked, the total won and the net") { () =>
    val summary = reply.json.get("summary")
    assertEquals((summary.get("staked").asLong, summary.get("won").asLong, summary.get("net").asLong, summary.get("currency").asText),
      (400L, 250L, -150L, "EUR"))
  }

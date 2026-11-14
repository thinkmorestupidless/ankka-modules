package io.eitheror.casino.publicapi.features

import io.eitheror.casino.publicapi.it.{Games, Kit, Ranking, Reply, SharedKit}
import Ranking.*


/** features/public-api/player-stats.feature (T081), on `SharedKit.ranked` with real runs. */
class PlayerStatsFeatures extends PublicApiFeatures("player-stats.feature"):
  override protected def kit: Kit = SharedKit.ranked

  private var expected: Vector[String] = Vector.empty
  private var pages: Vector[Reply]     = Vector.empty
  private var snap: String             = ""
  private var ccy: String              = ""
  private var players: Vector[String]  = Vector.empty
  private var answers: Vector[Reply]   = Vector.empty

  private def ranking(c: String, offset: Int, limit: Int = 100, s: Option[String] = None, more: String = ""): Reply =
    get(s"/player-stats?period=7d&sort=ggr&currency=$c&limit=$limit&offset=$offset" + s.fold("")(x => s"&snapshot=$x") + more)

  Given("{int} players of brand {string} active in the last 7 days with known GGR in {string}") { (n: Int, _: String, c: String) =>
    ccy = c; expected = many(n, c); snap = snapshot(compute())
  }
  When("a caller pages the 7-day ranking by GGR in {string} 100 at a time") { (c: String) =>
    pages = Iterator.from(0).map(i => ranking(c, i * 100)).takeWhile(r => rows(r).nonEmpty).toVector
  }
  Then("it receives three pages naming one snapshot") { () =>
    assertEquals(pages.size, 3)
    assertEquals(pages.map(_.json.path("snapshot").path("id").asText()).distinct, Vector(snap))
  }
  Then("every player exactly once, in descending order of GGR") { () =>
    assertEquals(pages.flatMap(ids), expected)
  }

  Given("a caller has read the first page of a snapshot") { () =>
    ccy = "AUD"; expected = many(20, ccy); snap = snapshot(compute())
    pages = Vector(ranking(ccy, 0, 10, Some(snap)))
  }
  When("another snapshot is published") { () =>
    Games.seen(kit, Vector(play(expected.last, 500000, ccy)))
    assertNotEquals(snapshot(compute()), snap)
  }
  When("the caller asks for the second page naming the first snapshot") { () => pages :+= ranking(ccy, 10, 10, Some(snap)) }
  Then("the second page continues the first snapshot with no player repeated or missing") { () =>
    assertEquals(pages.map(_.json.path("snapshot").path("id").asText()).distinct, Vector(snap))
    assertEquals(pages.flatMap(ids), expected)
  }

  Given("a snapshot replaced more than its retention ago") { () =>
    ccy = "NZD"; many(3, ccy): Unit
    snap = snapshot(compute())
    compute(): Unit; compute(): Unit
  }
  When("a caller asks for a page naming it") { () => last = ranking(ccy, 0, s = Some(snap)) }
  Then("it is refused as an expired snapshot") { () => assertEquals((last.status, last.error), (410, "snapshot_expired")) }
  Then("a request naming no snapshot is served the current one") { () =>
    val r = ranking(ccy, 0)
    assertEquals((r.status, rows(r).size), (200, 3))
    assertNotEquals(r.json.path("snapshot").path("id").asText(), snap)
  }

  Given("a snapshot ranking active, closed and self-excluded players") { () =>
    ccy = "CAD"
    val st = Vector("closed", "active", "self_excluded", "active")
    players = st.map(player(_))
    registered(players.zip(st).toMap)
    Games.seen(kit, players.zipWithIndex.map((pl, i) => play(pl, 9000L - i * 1000, ccy)))
    snap = snapshot(compute())
  }
  When("a caller asks for the ranking of active players only") { () => last = ranking(ccy, 0, more = "&status=ACTIVE") }
  Then("only active players are listed, each with its rank in the whole ranking") { () =>
    assertEquals(rows(last).map(r => r.path("playerId").asText() -> r.path("rank").asInt()), Vector(players(1) -> 2, players(3) -> 4))
    assert(rows(last).forall(_.path("status").asText() == "ACTIVE"))
  }
  Then("the total counts only active players") { () => assertEquals(last.json.path("page").path("total").asInt(), 2) }

  Given("a player with known activity in the last 30 days and a player with none") { () =>
    players = Vector(player(), player())
    registered(players.map(_ -> "active").toMap)
    Games.seen(kit, Vector(Games.round(kit, "g-x", players(0), ago(3), 1200, 200, "MXN"), Games.round(kit, "g-y", players(0), ago(24 * 4), 800, 0, "MXN"),
      Games.round(kit, "g-x", players(0), ago(24 * 12), 500, 1000, "MXN")))
    compute(): Unit
  }
  When("a caller reads each one's figures") { () => answers = players.map(p => get(s"/players/$p/stats")) }
  Then("the first's figures for 1, 7 and 30 days match a hand count") { () =>
    val w = answers(0).json.path("windows")
    assertEquals(Vector("1d", "7d", "30d").map(p => w.path(p).path("rounds").asInt() -> w.path(p).path("games").asInt()), Vector(1 -> 1, 2 -> 2, 3 -> 2))
    assertEquals(Vector("1d", "7d", "30d").map(p => w.path(p).path("amounts").path(0).path("ggr").asText()), Vector("10.00", "18.00", "13.00"))
  }
  Then("the second's are zeros with no last-active time") { () =>
    val n = answers(1).json
    assert(n.path("lastActiveAt").isNull)
    Vector("1d", "7d", "30d").foreach(p => assertEquals(n.path("windows").path(p).toString, """{"rounds":0,"sessions":0,"games":0,"amounts":[]}"""))
  }

  Given("an access key allowed every identifying field") { () =>
    players = Vector(player()); registered(Map(players(0) -> "active"))
    Games.seen(kit, Vector(play(players(0), 4321, "HKD"))); compute(): Unit
  }
  When("a caller reads a player ranking or a player's figures with it") { () =>
    answers = Vector(get("/player-stats?period=1d&sort=ggr&currency=HKD", all), get(s"/players/${players(0)}/stats", all))
  }
  Then("no identifying field is served") { () =>
    val names = Set("email", "firstName", "lastName", "dateOfBirth", "phone", "addressLine1", "city", "stateProv", "postalCode")
    answers.foreach(r => assert(r.status == 200 && names.forall(n => !r.text.contains(s"\"$n\"")), r.text))
    assertEquals(rows(answers(0)).map(_.path("playerId").asText()), players)
    assert(!answers.exists(_.text.contains("Silva")))
  }

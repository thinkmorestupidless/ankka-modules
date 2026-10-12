package io.eitheror.casino.gameprovider.features

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.gameprovider.Json
import io.eitheror.casino.gameprovider.Json.*
import io.eitheror.casino.gameprovider.catalogue.*
import io.eitheror.casino.gameprovider.it.{Reply, St8Stub}

/** features/game-provider/catalogue.feature (US7, FR-021 to FR-024). */
class CatalogueFeatures extends ProviderFeatures("catalogue.feature"):

  /** A game as ST8's list carries it. */
  private final case class Listed(code: String, enabled: Boolean = true, developer: String = "studio-a", category: String = "slots",
      restricted: Vector[String] = Vector.empty, prohibited: Vector[String] = Vector.empty)

  private var codes = Map.empty[String, String] // a scenario's name for a game -> its code

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    codes = Map.empty
    val r = operator("PUT", s"/v1/casino/brands/${kit.brand}/game-catalog-config",
      Some("""{"disabledDevelopers":[],"disabledCategories":[],"gameOverrides":{}}"""))
    assertEquals(r.status, 200, r.text)

  private def code(name: String): String = codes.getOrElse(name, { val c = id(name); codes += name -> c; c })

  private def operator(method: String, path: String, body: Option[String] = None): Reply = kit.request(method, path, body, kit.asOperator())

  private def listJson(games: Seq[Listed]): String =
    Json.render(Json.obj(
      "games" -> Json.arr(games.map(g => Json.obj("code" -> code(g.code), "name" -> s"Game ${g.code}", "category" -> g.category,
        "developer" -> s"$prefix-${g.developer}", "enabled" -> g.enabled, "fun_mode" -> true, "bonus_types" -> Vector("free_bets"),
        "features" -> Vector("gamble"), "themes" -> Vector("egypt"), "restricted_territories" -> g.restricted,
        "prohibited_territories" -> g.prohibited, "deprecation_date" -> "2099-01-01", "rtp" -> "96.1"))*),
      "developers" -> Json.arr(games.map(_.developer).distinct.map(d => Json.obj("developer_id" -> s"$prefix-$d", "name" -> s"Studio $d"))*)
    ))

  private def stub(provider: String): St8Stub = if provider == "acme" then kit.acme else kit.st8

  /** The nightly refresh of one provider over the list given, then the catalogue as the view has it. */
  private def refresh(provider: String, games: Seq[Listed]): RefreshState =
    stub(provider).gamesBody = listJson(games)
    val configs = kit.settings.configurations(kit.testKit.secrets)
    val state = CatalogueRefresher(kit.testKit.componentClient, kit.testKit.service.viewClient, kit.settings.adapter, configs, _ => (), () => kit.clock)
      .run(provider)
    games.foreach(g => waitFor(provider, g.code)(_.exists))
    state

  private def game(provider: String, name: String): GameState =
    kit.testKit.componentClient.forKeyValueEntity(EntityId(Game.entityId(provider, code(name)))).call(Game.get).invoke()

  /** Waits until the catalogue view shows the game as `ok` wants it, then forgets the read snapshot. */
  private def waitFor(provider: String, name: String)(ok: GameState => Boolean): GameState =
    val g = kit.eventually(s"$provider/${code(name)} in the catalogue view") {
      kit.testKit.service.viewClient.forView(Catalogue).get(Game.entityId(provider, code(name))).map(_.game).filter(ok)
    }
    CatalogueReads.shared(kit.testKit.service.viewClient, kit.brand, kit.testKit.componentClient).invalidate()
    g

  private def browse(query: String = ""): Vector[JsonNode] =
    last = kit.request("GET", s"/v1/casino/catalog/games?brand=${kit.brand}$query", None, kit.asPlayer(player))
    assertEquals(last.status, 200, last.text)
    last.json.objects("games")

  private def listedCodes(query: String = ""): Set[String] = browse(query).flatMap(_.text("gameId")).toSet

  private def configure(fields: (String, Any)*): Reply =
    last = operator("PUT", s"/v1/casino/brands/${kit.brand}/game-catalog-config", Some(Json.render(Json.obj(fields*))))
    last

  // ── the refresh ───────────────────────────────────────────────────────────────────────────────

  private var feed = Seq.empty[Listed]

  Given("the game provider's list of games") { () =>
    feed = Seq(Listed("one", restricted = Vector("DE"), prohibited = Vector("US")), Listed("two", enabled = false, developer = "studio-b", category = "table"))
  }

  When("the nightly refresh runs") { () =>
    assertEquals(refresh("st8", feed).status, "succeeded")
  }

  Then("every game is in the catalogue with its code, name, developer, category, labels, themes, game features, bonus types, restricted and prohibited territories, deprecation date and the game provider's enabled flag") { () =>
    val one = game("st8", "one").fields.get
    assertEquals(game("st8", "one").code, code("one"))
    assertEquals((one.name, one.developerId, one.developerName, one.category), ("Game one", s"$prefix-studio-a", "Studio studio-a", "slots"))
    assertEquals((one.themes, one.features, one.bonusTypes), (Vector("egypt"), Vector("gamble"), Vector("free_bets")))
    assertEquals(one.tags.toSet, Set("egypt", "gamble", "free_bets"))
    assertEquals((one.restrictedTerritories, one.prohibitedTerritories, one.deprecationDate), (Vector("DE"), Vector("US"), Some("2099-01-01")))
    assertEquals((one.enabled, game("st8", "two").fields.get.enabled), (true, false))
  }

  Given("a game in the catalogue the game provider stops listing") { () =>
    refresh("st8", Seq(Listed("gone", enabled = false), Listed("stays")))
    feed = Seq(Listed("stays"))
  }

  Then("the game is marked removed") { () => waitFor("st8", "gone")(_.removedFromFeed) }

  Then("its enabled flag is unchanged") { () => assertEquals(game("st8", "gone").fields.map(_.enabled), Some(false)) }

  Given("a catalogue") { () => refresh("st8", Seq(Listed("kept"))) }

  When("the nightly refresh fetches nothing") { () =>
    val s = refresh("st8", Seq.empty)
    assertEquals((s.status, s.count, s.removed), ("succeeded", Some(0), Some(0)))
  }

  Then("nothing is marked removed") { () => assert(!game("st8", "kept").removedFromFeed) }

  // ── two providers ─────────────────────────────────────────────────────────────────────────────

  Given("a brand that holds credentials for two game providers") { () =>
    assertEquals(kit.configureAcme().status, 204)
    refresh("st8", Seq(Listed("first-only")))
    refresh("acme", Seq(Listed("second-a"), Listed("second-b")))
  }

  When("the nightly refresh fetches nothing from the first game provider and the second game provider's list") { () =>
    refresh("st8", Seq.empty)
    refresh("acme", Seq(Listed("second-a")))
  }

  Then("no game of the first game provider is marked removed") { () => assert(!game("st8", "first-only").removedFromFeed) }

  Then("a game of the second game provider absent from its list is marked removed") { () =>
    waitFor("acme", "second-b")(_.removedFromFeed)
    assert(!game("acme", "second-a").removedFromFeed)
  }

  When("a player browses the brand's catalogue") { () => browse(): Unit }

  Then("every game listed carries its game provider and its code") { () =>
    val games = last.json.objects("games")
    assert(games.forall(g => g.text("provider").isDefined && g.text("gameId").isDefined))
    val ours = games.filter(_.text("gameId").exists(_.startsWith(prefix))).map(g => g.text("provider").get -> g.text("gameId").get).toSet
    assertEquals(ours, Set("st8" -> code("first-only"), "acme" -> code("second-a"), "acme" -> code("second-b")))
  }

  Given("a brand that holds credentials for two game providers, whose lists carry code {string} at the first game provider only") { (c: String) =>
    assertEquals(kit.configureAcme().status, 204)
    refresh("st8", Seq(Listed(c)))
  }

  Given("a brand that holds credentials for two game providers, whose lists carry code {string} at both game providers") { (c: String) =>
    assertEquals(kit.configureAcme().status, 204)
    refresh("st8", Seq(Listed(c)))
    refresh("acme", Seq(Listed(c)))
  }

  When("a player reads game {string} naming no game provider") { (c: String) =>
    last = kit.request("GET", s"/v1/casino/catalog/games/${code(c)}", None, kit.asPlayer(player))
  }

  Then("the game is answered with its game provider") { () =>
    assertEquals(last.status, 200, last.text)
    assertEquals((last.json.text("gameId"), last.json.text("provider")), (Some(code("book-x")), Some("st8")))
  }

  Then("it is refused as a bad request naming both game providers") { () =>
    assertEquals(last.status, 400, last.text)
    assertEquals(last.json.text("error"), Some("ambiguous_game_code"))
    assertEquals(last.json.strings("providers"), Vector("acme", "st8"))
  }

  // ── the brand's curation ──────────────────────────────────────────────────────────────────────

  Given("a brand that disabled a developer") { () =>
    refresh("st8", Seq(Listed("x-on", developer = "x"), Listed("x-off", enabled = false, developer = "x"), Listed("y-on", developer = "y")))
    val r = configure("disabledDevelopers" -> Vector(s"$prefix-x"), "gameOverrides" -> Json.obj(Game.entityId("st8", code("x-off")) -> true))
    assertEquals(r.status, 200, r.text)
  }

  Then("no game of that developer is listed, whatever the game's own flag or the brand's own setting for it") { () =>
    val listed = listedCodes()
    assert(!listed(code("x-on")) && !listed(code("x-off")), listed.filter(_.startsWith(prefix)).toString)
    assert(listed(code("y-on")), "another developer's game is still listed")
  }

  Given("a game the game provider disabled") { () =>
    refresh("st8", Seq(Listed("dark", enabled = false)))
    assert(!listedCodes()(code("dark")))
  }

  When("the operator enables it for the brand") { () =>
    configure("gameOverrides" -> Json.obj(Game.entityId("st8", code(codes.keys.find(k => k == "dark" || k == "removed").get)) -> true))
  }

  Then("the game is listed") { () =>
    assertEquals(last.status, 200, last.text)
    assert(listedCodes()(code("dark")))
  }

  Given("a game marked removed") { () =>
    refresh("st8", Seq(Listed("removed"), Listed("other")))
    refresh("st8", Seq(Listed("other")))
    waitFor("st8", "removed")(_.removedFromFeed)
  }

  Then("it is refused") { () =>
    assertEquals(last.status, 400, last.text)
    assertEquals(last.json.text("error"), Some("game_removed"))
    assert(!listedCodes()(code("removed")))
  }

  Given("a player in a country a game prohibits or restricts") { () =>
    refresh("st8", Seq(Listed("not-in-de", restricted = Vector("DE")), Listed("not-in-us", prohibited = Vector("US")), Listed("anywhere")))
  }

  When("the player browses the catalogue with their country") { () => () }

  Then("the game is not listed") { () =>
    val inDe = listedCodes("&country=DE")
    assert(!inDe(code("not-in-de")) && inDe(code("not-in-us")) && inDe(code("anywhere")))
    val inUs = listedCodes("&country=us")
    assert(!inUs(code("not-in-us")) && inUs(code("not-in-de")))
  }

  // ── popular games and the operator's catalogue ────────────────────────────────────────────────

  private def rounds(name: String, n: Int): Unit =
    val t = kit.sessionTicket(player, game = code(name))
    kit.wallet.set(player, currency, 100000)
    (1 to n).foreach { i =>
      val r = kit.callback("debit", json("token" -> t, "transaction_id" -> id(s"$name-$i"), "round" -> id(s"round-$name-$i"),
        "amount" -> "0.10", "currency" -> currency, "game_code" -> code(name)))
      assertEquals(r.word, "ok", r.text)
    }

  Given("a brand") { () =>
    refresh("st8", Seq(Listed("p1"), Listed("p2"), Listed("p3"), Listed("p4", enabled = false), Listed("p5")))
    refresh("st8", Seq(Listed("p1"), Listed("p2"), Listed("p3"), Listed("p4", enabled = false)))
    waitFor("st8", "p5")(_.removedFromFeed)
    rounds("p2", 3); rounds("p1", 2); rounds("p3", 1)
    kit.eventually("the rounds counted") {
      val counts = kit.testKit.service.viewClient.forView(GamePopularity).all(10_000).map(r => r.code -> r.rounds).toMap
      Option.when(counts.get(code("p2")).contains(3L) && counts.get(code("p1")).contains(2L) && counts.get(code("p3")).contains(1L))(())
    }
  }

  When("its popular games are asked") { () =>
    // p2 is disabled by the brand after the ranking was built.
    assertEquals(configure("gameOverrides" -> Json.obj(Game.entityId("st8", code("p2")) -> false)).status, 200)
    last = kit.request("GET", s"/v1/casino/catalog/${kit.brand}/games/popular?limit=100", None, kit.asPlayer(player))
  }

  Then("games are ranked by the number of rounds played at that brand over all time, counted from the brand's own rounds") { () =>
    assertEquals(last.status, 200, last.text)
    val ours = last.json.strings("gameIds").filter(_.startsWith(prefix))
    assertEquals(ours, Vector(code("p1"), code("p3")))
  }

  Then("a game disabled since the ranking was built is not among them") { () =>
    assert(!last.json.strings("gameIds").contains(code("p2")))
  }

  When("an operator reads the brand's catalogue") { () =>
    assertEquals(configure("disabledCategories" -> Vector("none"), "gameOverrides" -> Json.obj(Game.entityId("st8", code("p3")) -> false)).status, 200)
    last = operator("GET", s"/v1/casino/brands/${kit.brand}/game-catalog?provider=st8")
  }

  Then("every game is listed, removed and disabled included") { () =>
    assertEquals(last.status, 200, last.text)
    val ours = last.json.objects("games").flatMap(_.text("code")).filter(_.startsWith(prefix)).toSet
    assertEquals(ours, Set("p1", "p2", "p3", "p4", "p5").map(code))
  }

  Then("each layer of the enablement decision is shown") { () =>
    val byCode = last.json.objects("games").map(g => g.text("code").get -> g).toMap
    val p3 = byCode(code("p3")); val p4 = byCode(code("p4")); val p5 = byCode(code("p5")); val p1 = byCode(code("p1"))
    assertEquals((p3.bool("individualOverride"), p3.bool("effectiveEnabled")), (Some(false), Some(false)))
    assertEquals((p4.bool("st8Enabled"), p4.bool("effectiveEnabled")), (Some(false), Some(false)))
    assertEquals((p5.bool("removedFromFeed"), p5.bool("effectiveEnabled")), (Some(true), Some(false)))
    assertEquals((p1.bool("categoryDisabled"), p1.bool("developerDisabled"), p1.bool("effectiveEnabled")), (Some(false), Some(false), Some(true)))
  }

package io.eitheror.casino.gameprovider.features

import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.contract.facts.{PlayerSelfExcluded, PlayerSuspended}
import io.eitheror.casino.gameprovider.Json
import io.eitheror.casino.gameprovider.it.Reply
import io.eitheror.casino.gameprovider.Json.*
import io.eitheror.casino.gameprovider.players.{LaunchWindow, PlayerStatus}
import io.eitheror.casino.gameprovider.tickets.{IssueTicket, Ticket, TicketState}

import java.time.Instant

/** features/game-provider/launch.feature (US1, FR-007 to FR-010). */
class LaunchFeatures extends ProviderFeatures("launch.feature"):

  private var game     = ""
  private var provider = Option.empty[String]
  private var demo     = false
  private var twoProviders = false

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    game = id("slot")
    provider = None
    demo = false
    twoProviders = false

  private def launch(body: (String, Any)*): Reply =
    last = kit.request("POST", s"/v1/casino/sessions/${kit.brand}/launch",
      Some(Json.render(Json.obj((Seq("playerId" -> player, "gameId" -> game, "provider" -> provider, "demo" -> demo) ++ body)*))),
      kit.asPlayer(player))
    last

  private def issue(gameCode: String, at: Instant = kit.clock): String =
    val t = id(s"ticket-${gameCode}")
    kit.testKit.componentClient.forKeyValueEntity(EntityId(t)).call(Ticket.issue)
      .invoke(IssueTicket("st8", player, gameCode, None, id("gs"), currency, None, false, at)): Unit
    t

  private def check(ticket: String, gameCode: Option[String]): Reply =
    last = kit.callback("check", json("token" -> ticket, "game_code" -> gameCode))
    last

  private def ticketState(t: String): TicketState = kit.ticket(t)

  private def locked(state: String): Unit =
    val now = kit.clock
    state match
      case "suspended"     => kit.publishPlayers(PlayerSuspended(player, "active", "suspended", "operator", None, None, now))
      case "self-excluded" => kit.publishPlayers(PlayerSelfExcluded(player, "active", "self_excluded", "player", None, None, now, None, now))
    kit.eventually(s"$player $state in the view")(kit.testKit.service.viewClient.forView(PlayerStatus).get(player).filter(_.locked))

  // ── eligible launch ───────────────────────────────────────────────────────────────────────────

  Given("an eligible player at a brand that holds credentials for the game provider") { () => () }

  When("the player launches a game") { () => launch() }

  Then("they receive the game's address from the game provider, a session id and a launch ticket") { () =>
    assertEquals(last.status, 200, last.text)
    assertEquals(last.json.text("gameUrl"), kit.st8.gameUrl)
    assert(last.json.text("sessionId").isDefined && last.json.text("launchToken").isDefined, last.text)
    val t = ticketState(last.json.text("launchToken").get)
    assertEquals((t.status, t.playerId, t.gameCode), (TicketState.Issued, player, game))
    val sent = kit.st8.launches.last.json.get
    assertEquals(sent.text("token"), last.json.text("launchToken"))
    assert(kit.st8.badSignatures.isEmpty, "the launch is signed with the brand's operator key")
  }

  Then("a game session is open for them") { () =>
    val opened = kit.neighbours.sessionsOpened.find(_.path.contains(player))
    assert(opened.isDefined, "players was asked to open a game session")
    assertEquals(opened.get.json.flatMap(_.text("gameId")), Some(game))
    assertEquals(ticketState(last.json.text("launchToken").get).sessionId, last.json.text("sessionId").get)
  }

  // ── check ─────────────────────────────────────────────────────────────────────────────────────

  Given("a launch ticket issued for game {string} and not yet used") { (g: String) => token = issue(id(g)) }

  Given("a launch ticket issued for game {string} long ago and not yet used") { (g: String) =>
    token = issue(id(g), kit.clock.minusSeconds(400L * 24 * 3600))
  }

  Given("a launch ticket issued for game {string}") { (g: String) => token = issue(id(g)) }

  Given("a launch ticket already used") { () =>
    token = issue(id("slot-x"))
    assertEquals(check(token, None).word, "ok")
  }

  Given("a launch ticket of a player who is {word}") { (state: String) =>
    token = issue(id("slot-x"))
    locked(state)
  }

  When("the game provider checks the ticket naming game {string}") { (g: String) => check(token, Some(id(g))) }
  When("the game provider checks it naming game {string}") { (g: String) => check(token, Some(id(g))) }
  When("the game provider checks it again") { () => check(token, None) }
  When("the game provider checks the ticket") { () => check(token, None) }

  Then("it is answered {string}") { (word: String) => answered(word) }

  Then("it is answered {string} with the same ticket as the session ticket, the player's currency and country, the player id, and the site's lobby and deposit addresses") {
    (word: String) =>
      answered(word)
      val a = last.json
      assertEquals(a.text("token"), Some(token))
      assertEquals(a.text("currency"), Some(currency))
      assertEquals(a.text("country"), Some("MT"))
      assertEquals(a.text("player_id"), Some(player))
      val site = a.get("site")
      assertEquals(site.text("lobby"), Some("https://goldvibe.example/casino"))
      assertEquals(site.text("deposit"), Some("https://goldvibe.example/cashier"))
  }

  Then("the launch ticket is marked used") { () => assertEquals(ticketState(token).status, TicketState.Checked) }

  // ── refusals at launch ────────────────────────────────────────────────────────────────────────

  Given("a player at a brand whose play gate requires KYC before play, whose KYC is not verified") { () =>
    kit.neighbours.kycBlocked.update(player, "pending")
  }

  Then("the launch is refused as blocked by the play gate, naming their KYC status") { () =>
    assertEquals(last.status, 403, last.text)
    assertEquals(last.json.text("error"), Some("PLAY_BLOCKED_KYC"))
    assertEquals(last.json.text("kycStatus"), Some("pending"))
  }

  Given("a player who is cooling off") { () => kit.neighbours.rgRefused.update(player, ("cooling_off", None, None)) }
  Given("a player who is at their session limit for the day") { () =>
    kit.neighbours.rgRefused.update(player, ("session_limit_reached", Some(60L), Some(60L)))
  }

  Then("the launch is refused naming the reason") { () =>
    assertEquals(last.status, 403, last.text)
    val reason = last.json.text("error").get
    assert(Set("cooling_off_active", "session_limit_reached")(reason), last.text)
    if reason == "session_limit_reached" then assertEquals((last.json.long("limitMinutes"), last.json.long("usedMinutes")), (Some(60L), Some(60L)))
    else assert(last.json.text("coolingOffUntil").isDefined, last.text)
  }

  Then("no launch ticket is issued") { () =>
    assert(last.json.text("launchToken").isEmpty, last.text)
    assert(kit.st8.launches.isEmpty && kit.acme.launches.isEmpty, "the provider was not asked to launch")
  }

  Given("a player at a brand that holds credentials for one game provider and none for another") { () => () }

  When("the player launches a game of the second game provider") { () =>
    // `acme` is the second provider the brand holds credentials for; `nova` one it holds none for.
    provider = Some(if twoProviders then "acme" else "nova")
    launch()
  }

  Then("the launch is refused as not configured") { () =>
    assertEquals(last.status, 503, last.text)
    assertEquals(last.json.text("error"), Some("st8_not_configured"))
  }

  Given("a player at a brand that holds credentials for two game providers") { () =>
    assertEquals(kit.configureAcme().status, 204)
    twoProviders = true
  }

  Then("the second game provider is asked to launch it under the brand's credentials for it") { () =>
    assertEquals(last.status, 200, last.text)
    assertEquals(kit.acme.launches.size, 1)
    assert(kit.st8.launches.isEmpty)
    assert(kit.acme.badSignatures.isEmpty)
    assertEquals(kit.acme.launches.head.json.flatMap(_.get("site").text("wallet_url")).map(_.endsWith(s"/v1/casino/brands/${kit.brand}/acme")), Some(true))
  }

  Then("the launch ticket names that game provider and the game's code") { () =>
    val t = ticketState(last.json.text("launchToken").get)
    assertEquals((t.provider, t.gameCode), ("acme", game))
  }

  // ── a session ticket whose game session ended ─────────────────────────────────────────────────

  Given("a session ticket whose game session the frontend has ended") { () =>
    token = kit.sessionTicket(player)
    kit.wallet.set(player, currency, 1000)
  }

  When("the game provider debits {string} under the session ticket") { (amount: String) => debit("t1", amount, "r1") }

  Then("it is served as any debit is") { () =>
    answeredWithBalance("ok", "9.00")
    assertEquals(kit.wallet.openBets(player, currency).map(_.key), Vector(id("t1")))
  }

  // ── rate limit ────────────────────────────────────────────────────────────────────────────────

  Given("a player who launched {string} games within the last minute") { (n: String) =>
    (1 to n.toInt).foreach(_ => kit.testKit.componentClient.forKeyValueEntity(EntityId(player)).call(LaunchWindow.launch).invoke(kit.clock))
  }

  When("the player launches another game") { () => launch() }

  Then("the launch is served") { () => assertEquals(last.status, 200, last.text) }

  Then("the launch is refused as too many requests, naming the seconds until they may launch again") { () =>
    assertEquals(last.status, 429, last.text)
    assertEquals(last.json.text("error"), Some("RATE_LIMITED"))
    assert(last.json.long("retryAfterSeconds").exists(s => s > 0 && s <= 60), last.text)
  }

  // ── fun mode and free rounds ──────────────────────────────────────────────────────────────────

  Given("a player launching a game in fun mode") { () =>
    demo = true
    kit.neighbours.grants.update(player -> game, "grant-would-attach")
  }

  When("the game provider is asked to launch it") { () => launch() }

  Then("the launch is marked fun mode") { () =>
    assertEquals(last.status, 200, last.text)
    assertEquals(kit.st8.launches.last.json.flatMap(_.bool("fun_mode")), Some(true))
  }

  Then("no free rounds are attached to it") { () =>
    assertEquals(last.json.text("freeSpinsGrantRef"), None)
    assertEquals(ticketState(last.json.text("launchToken").get).freeRoundsGrant, None)
  }

  Given("a player with eligible free rounds for a game") { () => kit.neighbours.grants.update(player -> game, "grant-oldest") }

  When("the player launches that game naming no free rounds") { () => launch() }

  Then("the oldest eligible free rounds are attached to the launch ticket and echoed back") { () =>
    assertEquals(last.json.text("freeSpinsGrantRef"), Some("grant-oldest"), last.text)
    assertEquals(ticketState(last.json.text("launchToken").get).freeRoundsGrant, Some("grant-oldest"))
  }

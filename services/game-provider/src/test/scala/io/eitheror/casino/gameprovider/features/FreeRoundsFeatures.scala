package io.eitheror.casino.gameprovider.features

import io.eitheror.casino.gameprovider.Json
import io.eitheror.casino.gameprovider.Json.*
import io.eitheror.casino.gameprovider.it.Reply
import io.eitheror.casino.gameprovider.players.{KnownPlayer, MakeKnown}
import io.eitheror.casino.gameprovider.provider.BonusStatus

/** features/game-provider/free-rounds.feature (US8, FR-025). Called as `rewards` is: a service of the project, no token. */
class FreeRoundsFeatures extends ProviderFeatures("free-rounds.feature"):

  private var provider = "st8"
  private var grant    = ""

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    provider = "st8"
    grant = id("grant")

  private def rewards(method: String, path: String, body: Option[Seq[(String, Any)]] = None): Reply =
    last = kit.request(method, s"/v1/casino/brands/${kit.brand}/game-provider/$provider$path", body.map(fs => Json.render(Json.obj(fs*))))
    last

  private def create(count: Long = 10): Reply =
    rewards("POST", "/bonuses", Some(Seq("grantId" -> grant, "playerId" -> player, "gameCodes" -> Vector("book-of-ra", "fake-slot-002"),
      "count" -> count, "spinValue" -> "0.20", "currency" -> "EUR", "durationSeconds" -> 86400L)))

  Given("a player known to the game provider") { () =>
    kit.testKit.componentClient.forKeyValueEntity(com.thinkmorestupidless.ankka.core.EntityId(player)).call(KnownPlayer.makeKnown)
      .invoke(MakeKnown("st8", Some("EUR"), kit.clock)): Unit
  }

  When("the brand's rewards ask for free rounds of {string} rounds on two games") { (n: String) => create(n.toLong) }

  Then("the free rounds are created at the game provider under the reward's id") { () =>
    val sent = kit.st8.bonusCreates.last.json.get
    assertEquals(sent.text("bonus_id"), Some(grant))
    assertEquals(sent.long("count"), Some(10L))
    assertEquals(sent.strings("game_codes").size, 2)
    assertEquals(sent.strings("players"), Vector(player))
    assert(kit.st8.badSignatures.isEmpty, "signed with the brand's operator key")
  }

  Then("their status is reported back as active, processing, rejected, failed, cancelled or not configured") { () =>
    assertEquals(last.status, 200, last.text)
    assertEquals(last.json.text("status"), Some(BonusStatus.Active))
    kit.st8.bonusStatus = "rejected"
    assertEquals(rewards("GET", s"/bonuses/$grant").json.text("status"), Some(BonusStatus.Rejected))
    provider = "nova"
    assertEquals(create().json.text("status"), Some(BonusStatus.NotConfigured))
  }

  Given("a player who has never launched a game") { () => () }

  When("the brand's rewards ask for free rounds") { () => create() }

  Then("the player is made known to the game provider first, so that the player profile read is answered {string}") { (word: String) =>
    assertEquals(kit.callback("player_profile", json("player" -> player)).word, word)
  }

  Given("a brand that holds credentials for two game providers") { () => assertEquals(kit.configureAcme().status, 204) }

  When("the brand's rewards ask for free rounds on games of the second game provider") { () =>
    provider = "acme"
    create()
  }

  Then("the free rounds are created at the second game provider under the brand's credentials for it") { () =>
    assertEquals(last.json.text("status"), Some(BonusStatus.Active), last.text)
    assertEquals(kit.acme.bonusCreates.flatMap(_.json).flatMap(_.text("bonus_id")), Vector(grant))
    assertEquals(kit.st8.bonusCreates, Vector.empty)
    assert(kit.acme.badSignatures.isEmpty)
  }

  Given("active free rounds") { () => assertEquals(create().json.text("status"), Some(BonusStatus.Active)) }

  When("the brand's rewards cancel them") { () => rewards("POST", s"/bonuses/$grant/cancel", Some(Seq("playerId" -> player))) }

  Then("the free rounds are cancelled at the game provider") { () =>
    assertEquals(last.json.text("status"), Some(BonusStatus.Cancelled), last.text)
    assertEquals(kit.st8.bonusCancels.flatMap(_.json).flatMap(_.text("bonus_id")), Vector(grant))
  }

  Given("game codes and a currency") { () => () }

  When("the brand's rewards ask their spin values") { () => rewards("GET", s"/spin-values?gameCodes=${id("g1")},${id("g2")}&currency=EUR") }

  Then("the game provider's spin values are answered") { () =>
    assertEquals(last.status, 200, last.text)
    assertEquals(last.json.strings("values"), Vector("0.10", "0.20", "0.50"))
  }

  Then("they are cached for the day") { () =>
    rewards("GET", s"/spin-values?gameCodes=${id("g1")},${id("g2")}&currency=EUR")
    assertEquals(last.json.strings("values"), Vector("0.10", "0.20", "0.50"))
    assertEquals(kit.st8.offerRequests.size, 1, "the second ask was answered from the day's cache")
  }

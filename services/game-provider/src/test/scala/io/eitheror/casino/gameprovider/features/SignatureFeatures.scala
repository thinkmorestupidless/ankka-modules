package io.eitheror.casino.gameprovider.features

import io.eitheror.casino.gameprovider.{Json, TestKeys}
import io.eitheror.casino.gameprovider.Json.*
import io.eitheror.casino.gameprovider.provider.st8.St8Signature

/** features/game-provider/signatures.feature (US5, FR-003, FR-004, FR-026). */
class SignatureFeatures extends ProviderFeatures("signatures.feature"):

  private def body = Json.render(json("token" -> token, "transaction_id" -> id("t1"), "round" -> id("r1"), "amount" -> "1.00", "currency" -> "EUR"))

  private def nothingTouched(): Unit =
    assertEquals(kit.wallet.received, Vector.empty, "the wallet was not asked")
    assertEquals(kit.neighbours.received, Vector.empty, "no neighbour was asked")
    assert(kit.round(id("r1")).isNew, "no round was written")

  Given("a brand holding the game provider's signing key") { () =>
    token = kit.sessionTicket(player)
    kit.wallet.set(player, currency, 1000)
    kit.wallet.clearReceived(); kit.neighbours.clearReceived()
  }

  When("a provider call arrives signed under the matching key") { () => last = kit.callback("debit", body) }

  Then("it is served") { () =>
    answered("ok")
    assertEquals(kit.wallet.openBets(player, currency).map(_.key), Vector(id("t1")))
  }

  When("a provider call arrives with no signature") { () => last = kit.callback("debit", body, signature = Some("")) }
  When("a provider call arrives with a signature that is not readable") { () => last = kit.callback("debit", body, signature = Some("%%% not base64 %%%")) }
  When("a provider call arrives with a signature over different content") { () =>
    last = kit.callback("debit", body, signature = Some(St8Signature.sign(body.replace("1.00", "100.00"), TestKeys.testPrivate)))
  }

  Then("it is answered {string}") { (word: String) =>
    assertEquals(last.status, 200, last.text)
    answered(word)
  }

  Then("nothing is read or written") { () => nothingTouched() }

  When("a debit arrives with a body that is not readable") { () => last = kit.callback("debit", "this is not json") }
  When("a player profile read arrives with a body that is not readable") { () => last = kit.callback("player_profile", "[1, 2, 3]") }

  Then("it is refused as a bad request naming {string}") { (word: String) =>
    assertEquals(last.status, 400, last.text)
    assertEquals(last.word, word)
  }

  Given("a brand whose game provider signing key is rotated") { () =>
    token = kit.sessionTicket(player)
    kit.wallet.set(player, currency, 1000)
    assertEquals(kit.configureSt8(keyDocument = true).status, 204)
    assertEquals(kit.callback("balance", json("token" -> token, "player" -> player)).word, "ok", "the current key verifies")
    kit.st8.documentKeys = Vector(TestKeys.otherPublicDer) // the provider rotates: the next key replaces the current one
  }

  When("the next provider call arrives") { () =>
    kit.clock = kit.clock.plusSeconds(31) // within FR-026's 60 seconds of the rotation
    last = kit.callback("balance", json("token" -> token, "player" -> player), key = TestKeys.otherPrivate)
  }

  Then("it is verified against the new key without a redeploy") { () =>
    answered("ok")
    assertEquals(kit.callback("balance", json("token" -> token, "player" -> player), key = TestKeys.testPrivate).word, "auth_failed",
      "the withdrawn key no longer verifies")
  }

package io.eitheror.casino.publicapi.features

import com.fasterxml.jackson.databind.JsonNode
import io.eitheror.casino.publicapi.it.{Identifying, Keys, MadeKey, Seeded}

import java.time.Instant

/** features/public-api/identifying-fields.feature (T058). */
class IdentifyingFieldsFeatures extends PublicApiFeatures("identifying-fields.feature")
    with com.thinkmorestupidless.ankka.testkit.LogCapturing:
  private val at                = Instant.parse("2026-11-04T10:00:00Z")
  private var key: MadeKey      = null
  private var answer: JsonNode  = null
  private var player: String    = ""
  private def others(but: Set[String]) = Identifying.Names.filterNot(but)

  private def read(k: MadeKey): JsonNode =
    if player.isEmpty then player = Identifying.player(kit, at, "feature@example.com")
    kit.v1(s"/players/$player", Some(k.secret)).json

  Given("an access key allowed no identifying field") { () => key = Keys.noFields(kit) }
  Given("an access key allowed only email") { () => key = Keys.emailOnly(kit) }
  Given("an access key allowed email") { () => key = Keys.emailOnly(kit); assert(read(key).has("email")) }
  When("a caller reads a player with it") { () => answer = read(key) }
  Then("every identifying field is absent from the answer") { () => Identifying.Names.foreach(n => assert(!answer.has(n), n)) }
  Then("the answer carries the player's email") { () => assertEquals(answer.path("email").asText(), "feature@example.com") }
  Then("every other identifying field is absent") { () => others(Set("email")).foreach(n => assert(!answer.has(n), n)) }
  When("an operator withdraws email from it") { () =>
    assertEquals(Keys.change(kit, key, """{"identifyingFields":[]}""").status, 200)
  }
  Then("the next player read with it has no email") { () => assert(!read(key).has("email")) }

  Given("an erased player") { () =>
    player = Identifying.player(kit, at, "erased-feature@example.com")
    assertEquals(kit.request("POST", s"/v1/casino/brands/${kit.brand}/public-api/erasure/players/$player", None, kit.asService("players")).status, 200)
  }
  When("a caller reads them with an access key allowed every identifying field") { () =>
    val all = Keys.allFields(kit)
    answer = kit.eventually("erased")(Option(read(all)).filter(j => !j.has("email")))
  }
  Then("every identifying field is absent") { () => Identifying.Names.foreach(n => assert(!answer.has(n), n)) }
  Then("their id, status and amounts are served") { () =>
    assertEquals((answer.path("id").asText(), answer.path("status").asText()), (player, "ACTIVE"))
    assertEquals(kit.v1(s"/players/$player/transactions", Some(Seeded.none.secret)).status, 200)
  }

  Given("callers reading players with access keys allowed every identifying field") { () => key = Keys.allFields(kit); val _ = Seeded.set }
  When("their requests succeed, fail or are slow") { () =>
    val s = Seeded.set
    Vector(s"/players/${s.players.head}", "/players", "/players/nope", "/players?limit=500").foreach(p => kit.v1(p, Some(key.secret)): Unit)
  }
  Then("no identifying value appears in any log, trace or error") { () =>
    val logged = capturedLog
    (Identifying.values("p0@example.com") :+ key.secret).foreach(v => assert(!logged.contains(v), v))
  }

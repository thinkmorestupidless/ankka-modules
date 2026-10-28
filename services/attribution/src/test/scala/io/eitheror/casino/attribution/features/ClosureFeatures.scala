package io.eitheror.casino.attribution.features

import io.eitheror.casino.attribution.tag.Pseudonyms
import io.eitheror.casino.contract.facts.*

import java.nio.charset.StandardCharsets.UTF_8

/** features/attribution/closure.feature (US7): closure once with its category; erasure keeps the binding and every fact. */
class ClosureFeatures extends AttributionFeatures("closure.feature"):

  private var player: String = null
  private var before: Vector[(Option[String], String)] = Vector.empty
  private var read: com.fasterxml.jackson.databind.JsonNode = null

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    player = null; before = Vector.empty; read = null

  private def p = Pseudonyms.derive(Pseudonyms.keyBytes(kit.testKit.secrets.get(Pseudonyms.secretName("programme")).get), player)

  private def attributed(): Unit =
    player = kit.bindPlayer(Some(kit.network.mint("aff-1", id("lnk"), Some("autumn"), Vector("s1"))), clickId = Some(id("click")))
    kit.eventually("bound")(kit.record(player).binding): Unit

  private def erase(): Unit =
    val r = kit.request("POST", s"/v1/casino/brands/${kit.brand}/attribution/players/$player/erasure", headers = kit.asService())
    assertEquals(r.status, 200, r.text)

  /** What was emitted about the player, as the reader holds it: the id and the bytes. */
  private def aboutPlayer: Vector[(Option[String], String)] =
    kit.attributionMessages.filter(_.message.key.contains(p)).map(m => m.message.metadata.get("ce-id") -> String(m.message.payload, UTF_8))

  Given("an attributed player") { () => attributed() }
  When("their account is closed") { () =>
    kit.publish(CasinoPlayers, PlayerClosed(player, "active", "closed", "operator", Some("op-1"), Some("chargebacks"), kit.now))
  }
  When("their account is permanently self-excluded") { () =>
    kit.publish(CasinoPlayers, PlayerSelfExcluded(player, "active", "excluded", "player", None, Some("gambling harm"), kit.now, None, kit.now))
  }
  When("their account is erased") { () => erase() }
  Then("one {string} fact is emitted with the category {string} and never a reason text") { (kind: String, category: String) =>
    val closed = kit.eventually("the fact")(Some(aboutPlayer.filter(_._1.exists(_.startsWith(s"$kind/")))).filter(_.nonEmpty))
    Thread.sleep(1000)
    assertEquals(aboutPlayer.count(_._1.exists(_.startsWith(s"$kind/"))), 1)
    val body = closed.head._2
    assert(body.contains(s"\"category\":\"$category\""), body)
    assert(!body.contains("reason") && !body.contains("chargebacks") && !body.contains("gambling harm"), body)
  }

  Given("an erased player") { () =>
    attributed()
    erase()
  }
  When("the brand's attribution record for the player is read") { () =>
    val r = kit.request("GET", s"/v1/casino/backoffice/${kit.brand}/attribution/records/$player", headers = kit.asOperator())
    assertEquals(r.status, 200, r.text)
    read = r.json
  }
  Then("the binding remains: network, affiliate, link, sub-ids, click, pseudonym and dates") { () =>
    val b = read.get("binding")
    assertEquals((b.get("network").asText, b.get("affiliate").asText, b.get("link").asText, b.get("subIds").get(0).asText,
      b.get("clickId").asText), ("programme", "aff-1", id("lnk"), "s1", id("click")))
    assert(b.hasNonNull("boundAt") && read.hasNonNull("registeredAt") && read.hasNonNull("erased"), read.toString)
    assertEquals(kit.record(player).binding.map(_.pseudonym), Some(p))
  }
  Then("every personal field of the player reads as erased") { () =>
    // The record holds no personal field (data-model.md): the erasure answer names none, and the record none.
    val text = read.toString
    for field <- Vector("name", "email", "address", "dateOfBirth", "document", "phone", "ip", "userAgent") do
      assert(!read.has(field) && !read.get("binding").has(field), s"$field in $text")
  }

  Given("facts already emitted about an erased player") { () =>
    attributed()
    kit.eventually("the registration")(Option.when(aboutPlayer.exists(_._1.exists(_.startsWith("registration/"))))(())): Unit
    before = aboutPlayer
    erase()
    kit.eventually("the closure")(Option.when(aboutPlayer.exists(_._1.exists(_.startsWith("player-closed/"))))(())): Unit
  }
  When("a reader reads them again") { () => Thread.sleep(500) }
  Then("they are unchanged") { () =>
    assertEquals(aboutPlayer.filterNot(_._1.exists(_.startsWith("player-closed/"))), before)
  }

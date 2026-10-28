package io.eitheror.casino.attribution.it

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.attribution.Json
import io.eitheror.casino.attribution.tag.Pseudonyms
import io.eitheror.casino.contract.facts.*

import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration as JDuration
import scala.concurrent.duration.*

/**
 * Closure and erasure (US7; FR-027, FR-028, research R11): one `player-closed` with its category and never a
 * reason; erasure keeps the record's binding and changes no fact.
 */
class ClosureSuite extends munit.FunSuite with LogCapturing:
  override val munitTimeout: Duration = 300.seconds

  private def kit = SharedKit.kit
  override def beforeEach(context: BeforeEach): Unit = kit.reset()

  private def pseudonym(player: String) =
    Pseudonyms.derive(Pseudonyms.keyBytes(kit.testKit.secrets.get(Pseudonyms.secretName("programme")).get), player)

  private def bound(): String =
    val player = kit.bindPlayer(Some(kit.network.mint("aff-1", kit.fresh("lnk"), Some("autumn"), Vector("s1"))), clickId = Some(kit.fresh("c")))
    kit.eventually("bound")(kit.record(player).binding): Unit
    player

  private def closedFacts(player: String) =
    kit.attributionMessages.filter(_.message.metadata.get("ce-id").contains(s"player-closed/${pseudonym(player)}"))

  private def closed(player: String): PlayerClosedAttributed =
    val m = kit.eventually("the player-closed fact")(closedFacts(player).headOption)
    AffiliatesAttribution.serializer.fromBytes(m.message.payload) match
      case c: PlayerClosedAttributed =>
        assert(!String(m.message.payload, UTF_8).contains("reason"), "a player-closed fact carries no reason")
        c
      case other => fail(s"not a player-closed: $other")

  private def closeAccount(player: String) =
    kit.publish(CasinoPlayers, PlayerClosed(player, "active", "closed", "operator", Some("op-1"), Some("fraud suspected"), kit.now))

  private def erase(player: String, headers: Seq[(String, String)] = kit.asService()) =
    kit.request("POST", s"/v1/casino/brands/${kit.brand}/attribution/players/$player/erasure", headers = headers)

  test("player_closed emits one player-closed with the category closed and no reason; a second, nothing") {
    val player = bound()
    closeAccount(player)
    val c = closed(player)
    assertEquals((c.category, c.factId), ("closed", s"player-closed/${pseudonym(player)}"))
    closeAccount(player)
    Thread.sleep(1500)
    assertEquals(closedFacts(player).size, 1)
  }

  test("an indefinite self-exclusion is self-excluded; one with an end is nothing") {
    val period = bound()
    kit.publish(CasinoPlayers, PlayerSelfExcluded(period, "active", "excluded", "player", None, Some("a break"), kit.now,
      Some(kit.now.plus(JDuration.ofDays(30))), kit.now))
    val ever = bound()
    kit.publish(CasinoPlayers, PlayerSelfExcluded(ever, "active", "excluded", "player", None, None, kit.now, None, kit.now))
    assertEquals(closed(ever).category, "self-excluded")
    Thread.sleep(1000)
    assertEquals(closedFacts(period), Vector.empty)
    assertEquals(kit.record(period).closed, None)
  }

  test("erasure under players answers erased with no personal field, closes once, keeps the binding and changes no fact") {
    val player  = bound()
    val binding = kit.record(player).binding.get
    kit.eventually("the registration")(kit.attributionMessages.find(_.message.metadata.get("ce-id").contains(s"registration/${pseudonym(player)}")))
      : Unit
    def others = kit.attributionMessages.filter(m => !m.message.metadata.get("ce-id").exists(_.startsWith("player-closed/")))
    val before = others

    assertEquals(erase(player, kit.asOperator()).status, 403)
    val r = erase(player)
    assertEquals(r.status, 200, r.text)
    assertEquals(r.json, Json.parse(s"""{"player":"$player","erased":true,"personalFields":[]}""").get)
    assertEquals(closed(player).category, "closed")

    val after = kit.record(player)
    assert(after.erased.isDefined)
    assertEquals(after.binding, Some(binding))
    val b = after.binding.get
    assert(b.network.nonEmpty && b.affiliate.nonEmpty && b.link.nonEmpty && b.subIds.nonEmpty && b.clickId.isDefined && b.pseudonym.nonEmpty)

    assertEquals(erase(player).status, 200)
    Thread.sleep(1000)
    assertEquals(closedFacts(player).size, 1)
    assertEquals(others.take(before.size), before)
  }

  test("an erasure after a closure emits nothing more; an unbound player's closure emits nothing") {
    val player = bound()
    closeAccount(player)
    closed(player): Unit
    assertEquals(erase(player).status, 200)
    Thread.sleep(1000)
    assertEquals(closedFacts(player).size, 1)

    val organic = kit.bindPlayer(None)
    kit.eventually("registered")(kit.record(organic).registeredAt): Unit
    val count = kit.attributionMessages.size
    closeAccount(organic)
    kit.eventually("closed")(kit.record(organic).closed): Unit
    Thread.sleep(1000)
    assert(!kit.attributionMessages.drop(count).exists(_.message.metadata.get("ce-id").exists(_.startsWith("player-closed/"))))
  }

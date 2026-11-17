package io.eitheror.casino.players.features

import com.fasterxml.jackson.databind.JsonNode
import io.eitheror.casino.players.Json
import io.eitheror.casino.players.Json.*
import io.eitheror.casino.players.erasure.{ErasureFlow, ErasureWorkflow}
import io.eitheror.casino.players.player.PlayerEntity

import java.time.{Duration, Instant}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** features/players/devices.feature (spec 030 P8, T105): the devices are recorded by the log-in itself. */
class DevicesFeatures extends PlayersFeatures("devices.feature"):
  protected def pending = Map.empty

  private val Windows = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
  private val IPhone  = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148 Safari/604.1"

  private var email    = ""
  private var playerId = ""
  private var loggedInAt = Instant.EPOCH

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    email = ""; playerId = ""; loggedInAt = Instant.EPOCH

  /** An address the fake geo lookup resolves to `country`, unique to the run (the lookup caches per address). */
  private def address(country: String): String =
    val ip = s"198.51.100.${java.util.concurrent.ThreadLocalRandom.current().nextInt(1, 255)}"
    kit.geo.countries.put(ip, country)
    ip

  private def logIn(userAgent: String, country: String): Unit =
    kit.advance(Duration.ofMinutes(1))
    loggedInAt = kit.now()
    val r = kit.json("POST", kit.playerPath("/login"), Json.obj("email" -> email, "password" -> "Passw0rd!"),
      kit.asVisitor ++ Seq("User-Agent" -> userAgent, "X-Forwarded-For" -> address(country)))
    assertEquals(r.status, 200, r.text)

  private def devices: Vector[JsonNode] =
    val r = kit.request("GET", kit.operatorPath(s"/players/$playerId/devices"), None, kit.asOperator())
    assertEquals(r.status, 200, r.text)
    r.json.get("devices").elements.asScala.toVector

  private def twoDevices(): Unit =
    val (e, id) = kit.activePlayer()
    email = e; playerId = id
    logIn(Windows, "BR")
    logIn(IPhone, "PT")

  Given("a player who has logged in from one device") { () =>
    val (e, id) = kit.activePlayer()
    email = e; playerId = id
    logIn(Windows, "BR")
  }

  When("the player logs in from another device") { () => logIn(IPhone, "PT") }

  Then("the player has two seen devices") { () => assertEquals(devices.size, 2) }

  Then("the new one carries when it was first seen, its platform, its browser and its country") { () =>
    val d = devices.head
    assertEquals((d.textOr("platform", ""), d.textOr("browser", ""), d.textOr("deviceType", ""), d.textOr("country", "")), ("iOS", "Safari", "Mobile", "PT"))
    // recorded by the service as the log-in is handled: at or just after the moment the test asked
    val seen = Instant.parse(d.textOr("firstSeenAt", "")).getEpochSecond
    assert(seen >= loggedInAt.getEpochSecond && seen <= loggedInAt.getEpochSecond + 5, s"first seen at $seen, logged in at ${loggedInAt.getEpochSecond}")
    assertEquals(d.textOr("lastSeenAt", ""), d.textOr("firstSeenAt", ""))
  }

  When("the player logs in from that device again") { () => logIn(Windows, "BR") }

  Then("the player has one seen device") { () => assertEquals(devices.size, 1) }

  Then("when it was last seen is the time of this log-in") { () =>
    val d = devices.head
    val last = Instant.parse(d.textOr("lastSeenAt", "")).getEpochSecond
    assert(last >= loggedInAt.getEpochSecond && last <= loggedInAt.getEpochSecond + 5, s"last seen at $last, logged in at ${loggedInAt.getEpochSecond}")
    assert(Instant.parse(d.textOr("firstSeenAt", "")).isBefore(Instant.parse(d.textOr("lastSeenAt", ""))), d.toString)
    assertEquals((d.textOr("platform", ""), d.textOr("browser", ""), d.textOr("country", "")), ("Windows", "Chrome", "BR"))
  }

  Given("a player with two seen devices") { () =>
    twoDevices()
    assertEquals(devices.size, 2)
  }

  When("the player is erased") { () =>
    val op = (m: String, p: String) => kit.request(m, kit.operatorPath(p), Some("{}"), kit.asOperator())
    assertEquals(op("POST", s"/players/$playerId/close").status, 200)
    assertEquals(op("POST", s"/players/$playerId/erasure").status, 202)
    kit.advance(Duration.ofDays(ErasureWorkflow.DefaultRetentionDays.toLong + 1))
    kit.eventually("erased", 20.seconds)(Option.when(ErasureWorkflow.stateOf(kit.components, playerId).step == ErasureFlow.Erased)(()))
  }

  Then("no seen device of the player can be read") { () =>
    assertEquals(devices, Vector.empty)
    assertEquals(kit.components.forEventSourcedEntity(PlayerEntity.id(playerId)).call(PlayerEntity.get).invoke().devices, Vector.empty)
    // nor in what the brand would export of the player
    val part = kit.request("GET", kit.servicePath(s"/exports/players/$playerId"), None, kit.asService("players"))
    assertEquals(part.status, 200, part.text)
    assertEquals(part.json.get("part").get("devices").size, 0, part.text)
  }

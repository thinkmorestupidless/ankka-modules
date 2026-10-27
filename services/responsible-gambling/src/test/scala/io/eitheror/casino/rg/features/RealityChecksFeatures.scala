package io.eitheror.casino.rg.features

import io.eitheror.casino.rg.Json.*

import scala.jdk.CollectionConverters.*

/**
 * features/responsible-gambling/reality-checks.feature (US5, FR-023, FR-024). The check fires on the game
 * page, which reads the interval from the status; "no check fires" is that interval being absent.
 */
class RealityChecksFeatures extends RgFeatures("reality-checks.feature"):

  private def interval: Option[Long] = status().json.long("realityCheckIntervalMinutes")

  private def setInterval(minutes: String) =
    last = kit.json("PUT", kit.playerPath(s"/$player/reality-check"), json("intervalMinutes" -> minutes.toLong), kit.asPlayer(player))

  Given("the player has no reality check") { () => assertEquals(interval, None) }

  When("they set a reality check interval of {string} minutes") { (m: String) => setInterval(m) }

  Then("the profile shows it") { () =>
    assertEquals(last.status, 200, last.text)
    assertEquals(interval, last.json.long("realityCheckIntervalMinutes"))
    assert(interval.isDefined)
  }

  Then("it is refused naming the allowed values") { () =>
    assertEquals((last.status, last.str("error")), (400, "invalid_interval"))
    assertEquals(last.json.get("allowed").elements.asScala.map(_.asLong).toVector, Vector(15L, 30L, 45L, 60L))
    assertEquals(interval, None)
  }

  Given("a reality check interval set") { () =>
    setInterval("30")
    assertEquals(interval, Some(30L))
  }

  When("the player acknowledges a check naming their game session") { () =>
    startSession("gs-rc", kit.now().minusSeconds(60))
    counted(kit.now(), "gs-rc")
    last = kit.json("POST", kit.playerPath(s"/$player/reality-check/acknowledge"), json("sessionId" -> "gs-rc"), kit.asPlayer(player))
  }

  Then("the acknowledgement is recorded with the time and the session") { () =>
    assertEquals(last.status, 200, last.text)
    val ack = state.acknowledged.get
    assertEquals(ack.sessionId, "gs-rc")
    assert(near(ack.at, kit.now()))
  }

  When("the player removes it") { () =>
    last = kit.request("DELETE", kit.playerPath(s"/$player/reality-check"), None, kit.asPlayer(player))
  }

  Then("the profile shows none") { () =>
    assertEquals(last.status, 200, last.text)
    assertEquals(interval, None)
  }

  Then("no check fires") { () => assertEquals(interval, None) }

  When("a game runs") { () =>
    startSession("gs-run", kit.now().minusSeconds(60))
    counted(kit.now(), "gs-run")
  }

package io.eitheror.casino.players.features

import io.eitheror.casino.contract.facts.{AuditRecord, PlatformAudit}
import io.eitheror.casino.players.Json
import io.eitheror.casino.players.Json.*
import io.eitheror.casino.players.it.Reply

import java.time.Instant

/** features/players/manager-tags.feature (spec 030 P7, T105). */
class ManagerTagsFeatures extends PlayersFeatures("manager-tags.feature"):
  protected def pending = Map.empty

  private var playerId = ""
  private var reply: Reply = null
  private var asked: Instant = Instant.EPOCH

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    playerId = ""; reply = null; asked = Instant.EPOCH

  private def setTags(tags: String*): Unit =
    asked = kit.now()
    reply = kit.json("PUT", kit.operatorPath(s"/players/$playerId/tags"), Json.obj("tags" -> Json.arr(tags*)), kit.asOperator(subject = "op-admin"))

  Given("a player with no manager tag") { () =>
    playerId = kit.activePlayer()._2
    val r = kit.request("GET", kit.operatorPath(s"/players/$playerId/tags"), None, kit.asOperator())
    assertEquals((r.status, r.json.strings("tags")), (200, Vector.empty), r.text)
  }

  When("an admin operator sets the player's manager tags to {string} and {string}") { (a: String, b: String) => setTags(a, b) }

  When("an admin operator sets a manager tag of {string} characters") { (n: String) => setTags("t" * n.toInt) }

  Then("the player is read with both manager tags") { () =>
    assertEquals(reply.status, 200, reply.text)
    val profile = kit.request("GET", kit.operatorPath(s"/players/$playerId/profile"), None, kit.asOperator())
    assertEquals(profile.status, 200, profile.text)
    assertEquals(profile.json.get("player").strings("managerTags"), Vector("host: ana", "high value"))
  }

  Then("who set them and when is recorded") { () =>
    val r = kit.request("GET", kit.operatorPath(s"/players/$playerId/tags"), None, kit.asOperator())
    assertEquals((r.str("setBy"), r.json.strings("tags")), ("op-admin", Vector("host: ana", "high value")))
    assert(!Instant.parse(r.str("setAt")).isBefore(asked.minusSeconds(1)), r.text)
    val record = kit.eventually("the audit record")(kit.facts(PlatformAudit).collect { case a: AuditRecord => a }.find(a => a.action == "manager-tags" && a.subjectId == playerId))
    assertEquals((record.actorId, record.changes.flatMap(_.after)), ("op-admin", Vector("host: ana, high value")))
  }

  Then("the change is refused naming the manager tag") { () =>
    assertEquals((reply.status, reply.str("error"), reply.str("tag").length, reply.str("maxLength")), (400, "invalid_tag", 41, "40"), reply.text)
    val r = kit.request("GET", kit.operatorPath(s"/players/$playerId/tags"), None, kit.asOperator())
    assertEquals(r.json.strings("tags"), Vector.empty)
  }

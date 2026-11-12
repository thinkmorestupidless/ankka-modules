package io.eitheror.casino.publicapi.features

import io.eitheror.casino.contract.facts as f
import io.eitheror.casino.publicapi.api.{Pipeline, Served, V1Endpoint}
import io.eitheror.casino.publicapi.contract.Refusals
import io.eitheror.casino.publicapi.it.{Identifying, Keys, MadeKey, Reply, SharedKit}

import java.time.Instant

/** features/public-api/audit.feature (T065): the records on `platform.audit`, read as spec 018's queries would. */
class AuditFeatures extends PublicApiFeatures("audit.feature"):
  private var keys: Vector[MadeKey]    = Vector.empty
  private var players: Vector[String]  = Vector.empty
  private var answers: Vector[Reply]   = Vector.empty
  private var emails: Vector[String]   = Vector.empty

  private def records = kit.facts(f.PlatformAudit).collect { case a: f.AuditRecord if a.service == "public-api" => a }
  private def reads(k: MadeKey) = records.filter(r => r.action == "public_api.read" && r.actorId == k.id)
  private def player(email: String) = { emails :+= email; Identifying.player(kit, Instant.parse("2001-01-01T00:00:00Z"), email) }
  private def unique(n: String) = s"$n-${java.util.UUID.randomUUID().toString.take(8)}@example.com"

  Given("an access key allowed email") { () => keys = Vector(Keys.emailOnly(kit)) }
  When("a caller reads two players with it") { () =>
    players = Vector(player(unique("a")), player(unique("b")))
    val k = keys.head
    // One request serving both: the listing, ordered by registration, where these two come first.
    kit.eventually("both on the first page")(Some(kit.v1("/players?limit=100", Some(k.secret))).filter(r =>
      players.forall(r.text.contains)).map(r => answers = Vector(r)))
  }
  Then("one audit record names the key, the operation, both players and the field {string}") { (field: String) =>
    val k = keys.head
    val r = kit.eventually("the read recorded")(reads(k).find(r => players.forall(p => r.changes.exists(_.field == s"player:$p"))))
    players.foreach(p => assertEquals(r.changes.find(_.field == s"player:$p").flatMap(_.after), Some(field)))
    assert(r.changes.exists(c => c.field == "operation" && c.after.exists(_.contains("/players"))), r.toString)
  }
  Then("it carries no email") { () =>
    kit.factTexts(f.PlatformAudit).filter(_.contains(keys.head.id)).foreach(t => emails.foreach(e => assert(!t.contains(e), t)))
  }

  Given("an access key allowed no identifying field, and a disabled access key") { () =>
    keys = Vector(Keys.noFields(kit), Keys.disabled(kit))
  }
  When("a request presents each") { () => answers = keys.map(k => kit.v1("/contract", Some(k.secret))) }
  Then("each leaves one audit record with its outcome") { () =>
    assertEquals(answers.map(_.status), Vector(200, 403))
    val outcomes = keys.map(k => kit.eventually("recorded")(Option(reads(k)).filter(_.size == 1)).head.outcome)
    assertEquals(outcomes, Vector("done", "refused"))
  }

  private var subject: Vector[f.AuditRecord] = Vector.empty
  Given("two access keys were served a player's email") { () =>
    players = Vector(player(unique("sar")))
    keys = Vector(Keys.emailOnly(kit), Keys.allFields(kit))
    keys.foreach(k => assertEquals(kit.v1(s"/players/${players.head}", Some(k.secret)).status, 200))
  }
  When("the player's subject access request is answered") { () =>
    // Spec 018's query by subject: every record whose subject is player/<id>.
    subject = kit.eventually("both disclosures")(Option(records.filter(r => r.subjectKind == "player" && r.subjectId == players.head)).filter(_.size == 2))
  }
  Then("it lists both keys, the field and the times") { () =>
    assertEquals(subject.map(_.actorId).toSet, keys.map(_.id).toSet)
    subject.foreach { r =>
      assert(r.changes.find(_.field == "fields").flatMap(_.after).exists(_.split(",").contains("email")), r.toString)
      assert(r.occurredAt != null)
    }
  }

  private var quiet: MadeKey = null
  Given("an access key at its requests per minute") { () =>
    keys = Vector(Keys.create(kit, "at-rate", rate = Some(3)))
    quiet = Keys.noFields(kit)
    (1 to 3).foreach(_ => assertEquals(kit.v1("/contract", Some(keys.head.secret)).status, 200))
  }
  When("it sends one more") { () => last = kit.v1("/contract", Some(keys.head.secret)) }
  Then("it is refused with when to retry") { () =>
    assertEquals((last.status, last.error), (429, "quota_exceeded"))
    assert(last.header("Retry-After").exists(_.toInt >= 1))
  }
  Then("another access key's latency is unchanged") { () =>
    // The measured comparison is QuotaSuite's (SC-006); here: the other key is served at once.
    val s = System.nanoTime()
    assertEquals(kit.v1("/contract", Some(quiet.secret)).status, 200)
    assert((System.nanoTime() - s) / 1_000_000 < 1000)
  }

  private var peer: Option[(com.thinkmorestupidless.ankka.testkit.AnkkaTestKit.Peer, String)] = None
  Given("a request that cannot be answered within five seconds") { () =>
    val k = SharedKit.peered
    peer = Some(k.peer(Seq(clients =>
      val held = Map("listPlayers" -> ((_: Served) => { Thread.sleep(7000); Refusals.json(200, "{}") }))
      V1Endpoint(k.contract, Pipeline(k.settings, k.contract, clients, () => k.now, held)(_, _), _ => Refusals.notFound.respond))))
    keys = Vector(Keys.noFields(k))
  }
  When("the budget is spent") { () =>
    try last = SharedKit.peered.v1("/players", Some(keys.head.secret), at = peer.get._2)
    finally peer.foreach(p => io.eitheror.casino.publicapi.it.Kit.stopPeer(p._1))
  }
  Then("it is told the service is temporarily unavailable") { () =>
    assertEquals((last.status, last.error), (503, "unavailable"))
  }

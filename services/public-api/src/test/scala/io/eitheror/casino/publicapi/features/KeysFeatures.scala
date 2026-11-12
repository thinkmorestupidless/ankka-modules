package io.eitheror.casino.publicapi.features

import io.eitheror.casino.publicapi.it.{Keys, MadeKey}

import scala.jdk.CollectionConverters.*

import io.eitheror.casino.contract.facts as f

import scala.concurrent.duration.*

/** features/public-api/keys.feature (T030): an operator's keys through `/access-keys`, a caller's requests through `/v1`. */
class KeysFeatures extends PublicApiFeatures("keys.feature"):
  private var operator: Seq[(String, String)] = Nil
  private var key: MadeKey                    = null
  private var answers: Vector[String]         = Vector.empty
  private var statuses: Vector[Int]           = Vector.empty

  private def keys(rest: String = "") = s"/access-keys/${kit.brand}$rest"
  private def read(k: MadeKey)         = kit.v1("/contract", Some(k.secret))

  Given("an operator holding the access keys permission on brand {string}") { (brand: String) =>
    assertEquals(brand, kit.brand); operator = kit.asKeyManager
  }
  Given("an operator whose roles name brand {string} without the access keys permission") { (brand: String) =>
    assertEquals(brand, kit.brand); operator = kit.asStaff(Nil)
  }
  Given("an operator holding the access keys permission on brand {string} only") { (brand: String) =>
    operator = kit.asStaff(Seq("api_keys", s"brand_$brand"), brandRole = false)
  }
  When("they create an access key named {string}") { (name: String) =>
    last = kit.request("POST", keys(), Some(s"""{"name":"$name"}"""), operator)
  }
  Then("they are shown the key's secret") { () =>
    assertEquals(last.status, 201, last.text)
    key = MadeKey(last.json.path("key").path("id").asText(), last.json.path("secret").asText())
    assert(key.secret.startsWith(s"ak_${key.id}_"))
  }
  Then("the access key can read brand {string}") { (_: String) =>
    assertEquals(read(key).status, 200)
  }

  Given("an access key of brand {string} created yesterday") { (_: String) =>
    key = Keys.noFields(kit); kit.moveClock(1.day)
  }
  When("an operator lists or reads the brand's access keys") { () =>
    kit.eventually("the key listed")(Option.when(kit.request("GET", keys(), headers = kit.asKeyManager).text.contains(key.id))(()))
    answers = Vector(keys(), keys("?deleted=true"), keys(s"/${key.id}")).map(p => kit.request("GET", p, headers = kit.asKeyManager).text)
  }
  Then("no answer carries the key's secret") { () =>
    val tail = key.secret.drop(s"ak_${key.id}_".length)
    answers.foreach(a => assert(!a.contains(tail), a))
  }

  When("they list, create or change an access key of brand {string}") { (_: String) =>
    val existing = Keys.noFields(kit)
    statuses = Vector(
      kit.request("GET", keys(), headers = operator),
      kit.request("POST", keys(), Some("""{"name":"x"}"""), operator),
      kit.request("PATCH", keys(s"/${existing.id}"), Some("""{"name":"y"}"""), operator),
      kit.request("POST", keys(s"/${existing.id}/disable"), headers = operator)).map(_.status)
  }
  Then("they are refused") { () =>
    statuses.foreach(s => assert(s == 403 || s == 404, statuses.toString))
  }

  Given("an access key presented at brand {string}") { (_: String) =>
    key = Keys.noFields(kit); assertEquals(read(key).status, 200)
  }
  When("an operator disables it") { () => Keys.act(kit, key, "disable"): Unit }
  When("an operator deletes it") { () =>
    assertEquals(kit.request("DELETE", keys(s"/${key.id}"), headers = kit.asKeyManager).status, 204)
  }
  Then("the next request with it is refused") { () =>
    assert(Set(401, 403)(read(key).status))
  }
  Then("once the operator enables it again, the next request is served") { () =>
    Keys.act(kit, key, "enable"): Unit
    assertEquals(read(key).status, 200)
  }
  Then("it cannot be enabled again") { () =>
    val r = kit.request("POST", keys(s"/${key.id}/enable"), headers = kit.asKeyManager)
    assertEquals((r.status, r.error), (409, "key_deleted"))
    assertEquals(read(key).status, 401)
  }

  Given("an access key whose expiry has passed") { () =>
    key = Keys.expiring(kit); kit.moveClock(61.seconds)
  }
  When("a request presents it") { () => last = read(key) }
  Then("it is refused as expired") { () =>
    assertEquals((last.status, last.error), (401, "key_expired"))
  }

  When("an operator changes the identifying fields it may read or its requests per minute") { () =>
    assertEquals(Keys.change(kit, key, """{"identifyingFields":["email"],"requestsPerMinute":7}""").status, 200)
  }
  Then("the next request with it is served under the change") { () =>
    import com.thinkmorestupidless.ankka.core.EntityId
    import io.eitheror.casino.publicapi.keys.{AccessKey, Admit, RawKeys}
    assertEquals(read(key).status, 200)
    val a = kit.components.forEventSourcedEntity(EntityId(key.id)).call(AccessKey.admit).invoke(Admit(RawKeys.digest(key.secret), kit.now))
    assertEquals((a.identifyingFields, a.requestsPerMinute), (Set("email"), 7))
  }

  // ── the listing (T066) ──────────────────────────────────────────────────────────────────────────

  private var pair: Vector[MadeKey] = Vector.empty

  Given("brand {string} holds two access keys") { (_: String) =>
    pair = Vector(Keys.create(kit, "listed-a", fields = Seq("email"), rate = Some(60), expiresAt = Some(kit.now.plusSeconds(86400).toString)),
      Keys.noFields(kit))
    assertEquals(read(pair.head).status, 200)
  }
  When("an operator holding the access keys permission lists them") { () =>
    last = kit.eventually("both listed, the first used")(Some(kit.request("GET", keys(), headers = kit.asKeyManager)).filter { r =>
      val shown = r.json.path("keys").elements().asScala.toVector
      pair.forall(k => shown.exists(_.path("id").asText() == k.id)) &&
        shown.exists(n => n.path("id").asText() == pair.head.id && n.has("lastUsedAt"))
    })
  }
  Then("each is shown with its name, the start of the key, its fields, its rate, its expiry, who created it, when, and when it was last used") { () =>
    val shown = last.json.path("keys").elements().asScala.toVector
    val a     = shown.find(_.path("id").asText() == pair.head.id).get
    assertEquals(a.path("name").asText(), "listed-a")
    assert(pair.head.secret.startsWith(a.path("prefix").asText()), a.toString)
    assertEquals(a.path("identifyingFields").elements().asScala.map(_.asText()).toVector, Vector("email"))
    assertEquals(a.path("requestsPerMinute").asInt(), 60)
    Vector("expiresAt", "createdAt", "lastUsedAt").foreach(n => assert(a.hasNonNull(n), n))
    assertEquals(a.path("createdBy").asText(), "keys-1")
    val b = shown.find(_.path("id").asText() == pair(1).id).get
    assert(!b.has("lastUsedAt"), b.toString)
  }

  // ── the audit of changes (T065) ─────────────────────────────────────────────────────────────────

  When("they create, change, disable, enable or delete an access key") { () =>
    key = Keys.noFields(kit)
    Keys.change(kit, key, """{"name":"audited"}"""): Unit
    Vector("disable", "enable").foreach(a => Keys.act(kit, key, a): Unit)
    assertEquals(kit.request("DELETE", keys(s"/${key.id}"), headers = operator).status, 204)
  }
  private def changes = kit.facts(f.PlatformAudit).collect { case a: f.AuditRecord if a.subjectKind == "access_key" && a.subjectId == key.id && a.actorKind == "operator" => a }
  Then("an audit record names the operator, the key, what changed and the time") { () =>
    val mine = kit.eventually("five changes recorded")(Option(changes).filter(_.size == 5))
    assertEquals(mine.map(_.action), Vector("access_key.created", "access_key.changed", "access_key.disabled", "access_key.enabled", "access_key.deleted"))
    mine.foreach(r => assert(r.actorId.nonEmpty && r.changes.nonEmpty && r.occurredAt != null, r.toString))
    assertEquals(mine(1).changes.find(_.field == "name").map(c => (c.before, c.after)), Some((Some("none"), Some("audited"))))
  }
  Then("no audit record carries the key's secret") { () =>
    val tail = key.secret.drop(s"ak_${key.id}_".length)
    kit.factTexts(f.PlatformAudit).foreach(t => assert(!t.contains(tail), t))
  }


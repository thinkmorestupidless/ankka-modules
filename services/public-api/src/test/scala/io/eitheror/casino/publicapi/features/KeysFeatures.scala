package io.eitheror.casino.publicapi.features

import io.eitheror.casino.publicapi.it.{Keys, MadeKey}

import scala.concurrent.duration.*

/** features/public-api/keys.feature (T030): an operator's keys through `/access-keys`, a caller's requests through `/v1`. */
class KeysFeatures extends PublicApiFeatures("keys.feature"):
  override protected def ranElsewhere: Map[String, String] = Map(
    "the brand's keys are listed with their name, the start of the key, their settings, who created them, when, and when each was last used" ->
      "T066 (pending: the last use is the request window's)",
    "every change to a key is an audit record naming the operator, the key and what changed, and never the secret" ->
      "T065 (pending: the audit records are US8's)")

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

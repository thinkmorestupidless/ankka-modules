package io.eitheror.casino.publicapi.it

import io.eitheror.casino.contract.facts as f

import java.time.Instant

/** T063 end to end: the records `platform.audit` carries for reads and key changes (FR-021, FR-036, FR-037). */
class AuditTrailSuite extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(120, "s")
  private lazy val kit = SharedKit.kit

  private def records = kit.facts(f.PlatformAudit).collect { case a: f.AuditRecord if a.service == "public-api" => a }
  private def reads(keyId: String)    = records.filter(r => r.action == "public_api.read" && r.actorId == keyId)

  test("a request that served email: one read record naming the players and field, a disclosure per player, no value") {
    val at = Instant.parse("2026-11-03T10:00:00Z")
    val a  = Identifying.player(kit, at, "trail-a@example.com")
    val b  = Identifying.player(kit, at, "trail-b@example.com")
    val k  = Keys.emailOnly(kit)
    assertEquals(kit.v1(s"/players/$a", Some(k.secret)).status, 200)
    assertEquals(kit.v1(s"/players/$b", Some(k.secret)).status, 200)
    val disclosed = kit.eventually("both disclosures published")(Option(records.filter(r =>
      r.action == "public_api.identifying_fields_served" && r.actorId == k.id)).filter(_.size == 2))
    assertEquals(disclosed.map(_.subjectId).toSet, Set(a, b))
    disclosed.foreach(r => assertEquals(r.changes.find(_.field == "fields").flatMap(_.after), Some("email")))
    assertEquals(reads(k.id).size, 2)
    val texts = kit.factTexts(f.PlatformAudit).filter(_.contains(k.id))
    texts.foreach(t => assert(!t.contains("trail-a@example.com") && !t.contains("trail-b@example.com") && !t.contains(k.secret), t))
  }

  test("a refusal of a key of the brand is recorded with its outcome; no key, a malformed key or a made-up key id is not") {
    val d = Keys.disabled(kit)
    assertEquals(kit.v1("/contract", Some(d.secret)).status, 403)
    val r = kit.eventually("the refusal recorded")(reads(d.id).headOption)
    assertEquals((r.outcome, r.changes.find(_.field == "status").flatMap(_.after)), ("refused", Some("403")))
    val (madeUp, madeUpKey) = io.eitheror.casino.publicapi.keys.RawKeys.mint()
    val wrongSecret          = s"ak_${d.id}_${madeUpKey.stripPrefix(s"ak_${madeUp}_")}"
    assertEquals(kit.v1("/contract").status, 401)
    assertEquals(kit.v1("/contract", Some("ak_nope")).status, 401)
    assertEquals(kit.v1("/contract", Some(madeUpKey)).status, 401)
    assertEquals(kit.v1("/contract", Some(wrongSecret)).status, 401)
    // The wrong secret names the disabled key, and is its second record: once it is published, the made-up key's
    // record, had there been one, was written before it (each key's log is written within its request).
    val both = kit.eventually("the wrong secret recorded under its key")(Option(reads(d.id)).filter(_.size == 2))
    assertEquals(both.map(_.changes.find(_.field == "status").flatMap(_.after)), Vector(Some("403"), Some("401")))
    assertEquals(records.filter(_.actorId == madeUp), Vector.empty)
  }

  test("each request record carries status, outcome and duration, so a key's volume, errors and latency are its records (FR-038)") {
    val k = Keys.noFields(kit)
    (1 to 3).foreach(_ => kit.v1("/contract", Some(k.secret)))
    kit.v1("/players/not-a-uuid", Some(k.secret))
    val mine = kit.eventually("four reads")(Option(reads(k.id)).filter(_.size == 4))
    assertEquals(mine.map(_.outcome).groupBy(identity).view.mapValues(_.size).toMap, Map("done" -> 3, "refused" -> 1))
    mine.foreach(r => assert(r.changes.exists(c => c.field == "duration_ms" && c.after.exists(_.toLongOption.isDefined)), r.toString))
  }

  test("every change to a key is a security record naming the operator, the key and what changed, never the secret") {
    val k = Keys.noFields(kit)
    Keys.change(kit, k, """{"name":"renamed","requestsPerMinute":30}"""): Unit
    Keys.act(kit, k, "disable"): Unit
    val mine = kit.eventually("created, changed and disabled")(Option(records.filter(_.subjectId == k.id)).filter(_.size >= 3))
    assertEquals(mine.map(_.action).take(3), Vector("access_key.created", "access_key.changed", "access_key.disabled"))
    assert(mine.forall(r => r.actorKind == "operator" && r.recordClass == "security"))
    val changed = mine(1).changes.map(c => c.field -> c.after).toMap
    assertEquals((changed("name"), changed("requestsPerMinute")), (Some("renamed"), Some("30")))
    kit.factTexts(f.PlatformAudit).filter(_.contains(k.id)).foreach(t => assert(!t.contains(k.secret.drop(s"ak_${k.id}_".length)), t))
  }

package io.eitheror.casino.publicapi.it

import com.thinkmorestupidless.ankka.core.DataSubject
import io.eitheror.casino.contract.facts as f

import java.time.Instant
import scala.jdk.CollectionConverters.*

object Identifying:
  val Names: Vector[String] = io.eitheror.casino.publicapi.keys.IdentifyingFields.Names.toVector

  /** Every identifying value `FactSet.registered` writes for a player with `email`. */
  def values(email: String): Vector[String] =
    Vector(email, "Ana", "Silva", "1990-04-02", "+5511999990000", "Rua 1", "São Paulo", "01000-000", "SP")

  def player(kit: Kit, at: Instant, email: String): String =
    val pl = FactSet.id()
    kit.publish(f.CasinoPlayers, FactSet.registered(pl, at, email))
    kit.eventually("the player served")(Option.when(kit.v1(s"/players/$pl", Some(Seeded.none.secret)).status == 200)(()))
    pl

/** Original `test/entitlements.test.ts`, per key: none, some, all; withdrawn from the next request; absent, never null. */
class IdentifyingFieldsSuite extends munit.FunSuite:
  private lazy val kit = SharedKit.kit
  private lazy val pl  = Identifying.player(kit, Instant.parse("2026-11-02T10:00:00Z"), "fields@example.com")

  private def read(k: MadeKey) = kit.v1(s"/players/$pl", Some(k.secret)).json

  test("no fields: every identifying field absent") {
    val j = read(Keys.noFields(kit))
    Identifying.Names.foreach(n => assert(!j.has(n), n))
  }

  test("some fields: those and no others") {
    val j = read(Keys.create(kit, "some", Seq("email", "city", "stateProv")))
    assertEquals((j.path("email").asText(), j.path("city").asText(), j.path("stateProv").asText()), ("fields@example.com", "São Paulo", "SP"))
    Identifying.Names.filterNot(Set("email", "city", "stateProv")).foreach(n => assert(!j.has(n), n))
  }

  test("all fields: each with its value, dateOfBirth as a date") {
    val j = read(Keys.allFields(kit))
    assertEquals(Identifying.Names.map(n => j.path(n).asText()), Vector("fields@example.com", "Ana", "Silva", "1990-04-02", "+5511999990000",
      "Rua 1", "São Paulo", "SP", "01000-000"))
  }

  test("a field withdrawn applies from the next request; a page of players obeys the key as a read by id does") {
    val k = Keys.emailOnly(kit)
    assert(read(k).has("email"))
    assertEquals(Keys.change(kit, k, """{"identifyingFields":[]}""").status, 200)
    assert(!read(k).has("email"))
    val page = kit.v1("/players", Some(k.secret)).json.path("data").elements().asScala.toVector
    assert(page.forall(p => Identifying.Names.forall(n => !p.has(n))))
  }

/** FR-025: after the erasure route, every identifying field is absent with every key; id, status and amounts remain. */
class ErasureSuite extends munit.FunSuite:
  private lazy val kit = SharedKit.kit
  private val at        = Instant.parse("2026-11-03T10:00:00Z")

  private def erase(brand: String, player: String, as: Seq[(String, String)]) =
    kit.request("POST", s"/v1/casino/brands/$brand/public-api/erasure/players/$player", None, as)

  test("the route erases the player's identifying fields; a fact read again does not bring them back") {
    val pl = Identifying.player(kit, at, "erase-me@example.com")
    kit.publish(f.MoneyTransactions, FactSet.deposit(pl, FactSet.id(), 1250, at.plusSeconds(1)))
    val r = erase(kit.brand, pl, kit.asService("players"))
    assertEquals(r.status, 200, r.text)
    assertEquals((r.json.path("service").asText(), r.json.path("subject").asText()), ("public-api", s"player/$pl"))
    val all = Keys.allFields(kit)
    val j   = kit.eventually("the fields erased")(Option(kit.v1(s"/players/$pl", Some(all.secret)).json).filter(j => !j.has("email")))
    Identifying.Names.foreach(n => assert(!j.has(n), n))
    assertEquals((j.path("id").asText(), j.path("status").asText()), (pl, "ACTIVE"))
    kit.eventually("the deposit kept")(Option.when(
      kit.v1(s"/players/$pl/transactions", Some(all.secret)).json.path("data").path(0).path("amount").asText() == "12.50")(()))
    kit.publish(f.CasinoPlayers, f.PlayerEmailChanged(pl, FactSet.p("again@example.com"), at.plusSeconds(5)))
    Thread.sleep(500)
    assert(!kit.v1(s"/players/$pl", Some(all.secret)).json.has("email"))
  }

  test("the installation's erasure (ankka 042) makes them absent too, and leaves no value in the database") {
    val pl = Identifying.player(kit, at, "platform-erased@example.com")
    kit.testKit.erase(DataSubject(s"player/$pl"))
    val all = Keys.allFields(kit)
    kit.eventually("the fields erased")(Option.when(!kit.v1(s"/players/$pl", Some(all.secret)).json.has("email"))(()))
    Identifying.Names.foreach(n => assert(!kit.v1(s"/players/$pl", Some(all.secret)).json.has(n), n))
    // Values of two or three letters ("SP", "Ana") turn up by chance in other rows' ciphertext, as base64 or hex.
    kit.testKit.assertNoPersonalValue(Identifying.values("platform-erased@example.com").filter(_.length >= 5)*)
  }

  test("only players may call it; another brand's segment is 404") {
    val pl = FactSet.id()
    assertEquals(erase(kit.brand, pl, kit.asService("wallet")).status, 403)
    assertEquals(erase(kit.brand, pl, kit.fromInternet).status, 403, "a call from outside the project")
    assertEquals(erase("spinvibe", pl, kit.asService("players")).status, 404)
  }

/** Original `no-pii-leak.test.ts` and `logger.test.ts`: no identifying value, no key and no digest in the logs. */
class DiagnosticsSuite extends munit.FunSuite with com.thinkmorestupidless.ankka.testkit.LogCapturing:
  private lazy val kit = SharedKit.kit

  test("a full walk with an every-field key, refusals and a slow request log no identifying value, key or digest") {
    val s   = Seeded.set
    val all = Keys.allFields(kit)
    val paths = Vector("/players", s"/players/${s.players.head}", s"/players/${s.players.head}/sessions", s"/players/${s.players.head}/rounds",
      s"/players/${s.players.head}/bets", s"/players/${s.players.head}/transactions", s"/players/${s.players.head}/bonus-awards",
      s"/sessions/${s.sessions(0)}", s"/rounds/${s.rounds(0)}", s"/rounds/${s.rounds(0)}/bets", s"/bets/${s.bets(0)}",
      s"/transactions/${s.transactions.head}", s"/bonus-awards/${s.bonuses.head}", "/players/not-an-id", "/players?limit=101")
    paths.foreach(p => kit.v1(p, Some(all.secret)): Unit)
    kit.v1("/players", Some(all.secret.dropRight(1) + "x")): Unit
    val logged = capturedLog
    val secrets = Vector(all.secret, all.secret.drop(s"ak_${all.id}_".length), io.eitheror.casino.publicapi.keys.RawKeys.digest(all.secret))
    (Identifying.values("p0@example.com") ++ secrets).foreach(v => assert(!logged.contains(v), s"logged: $v"))
  }

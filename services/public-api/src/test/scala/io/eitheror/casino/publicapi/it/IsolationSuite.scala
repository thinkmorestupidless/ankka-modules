package io.eitheror.casino.publicapi.it

import java.time.Instant

/** Original `test/isolation.test.ts`: two brands in two instances; the other brand's ids are not found, as absent ids are. */
class IsolationSuite extends munit.FunSuite:
  private lazy val mine  = Seeded.set
  private lazy val other = SharedKit.other
  private lazy val theirs =
    val s = FactSet.seed(other, Instant.parse("2026-11-01T10:00:00Z"))
    val k = Keys.noFields(other)
    other.eventually("the other brand served")(Option.when(other.v1(s"/rounds/${s.rounds(0)}", Some(k.secret)).status == 200)(()))
    s

  private def byId(s: FactSet.Seeded): Vector[String] = Vector(
    s"/players/${s.players.head}", s"/players/${s.players.head}/sessions", s"/players/${s.players.head}/rounds",
    s"/players/${s.players.head}/bets", s"/players/${s.players.head}/transactions", s"/players/${s.players.head}/bonus-awards",
    s"/sessions/${s.sessions(0)}", s"/sessions/${s.sessions(0)}/rounds", s"/rounds/${s.rounds(0)}", s"/rounds/${s.rounds(0)}/bets",
    s"/bets/${s.bets(0)}", s"/transactions/${s.transactions.head}", s"/bonus-awards/${s.bonuses.head}")

  test("every by-id route and every collection's parent, with the other brand's ids, is 404 exactly as an absent id") {
    val _ = mine
    val absent = FactSet.Seeded(Vector(FactSet.id()), Vector(FactSet.id()), Vector(FactSet.id()), Vector(FactSet.id()), Vector(FactSet.id()),
      Vector(FactSet.id()), "")
    byId(theirs).zip(byId(absent)).foreach { (theirsPath, absentPath) =>
      val a = Seeded.get(theirsPath)
      val b = Seeded.get(absentPath)
      assertEquals((a.status, a.text), (404, """{"error":"not_found"}"""), theirsPath)
      assertEquals((a.status, a.text), (b.status, b.text), theirsPath)
    }
  }

  test("each brand lists only its own players") {
    val mineListed   = Seeded.data(Seeded.get("/players")).map(_.path("id").asText()).toSet
    val theirsListed = theirs.players.toSet
    assert(mineListed.intersect(theirsListed).isEmpty)
    assert(Seeded.data(Seeded.get("/players")).forall(_.path("brandId").asText() == "goldvibe"))
  }

  test("another brand's key is not a key here") {
    val k = Keys.noFields(other)
    val r = SharedKit.kit.v1("/players", Some(k.secret))
    assertEquals((r.status, r.error), (401, "key_invalid"))
  }

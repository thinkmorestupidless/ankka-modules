package io.eitheror.casino.engagement.it

import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.contract.facts as f
import io.eitheror.casino.contract.money.{Currency, MinorUnits, SignedMinorUnits}
import io.eitheror.casino.contract.personal.Personal
import io.eitheror.casino.engagement.profile.{MoneyFact, PlayersFact, Profile, ProfileState}
import io.eitheror.casino.engagement.reads.{ProfilesView, SendsView}
import io.eitheror.casino.engagement.send.*

import java.time.{Instant, LocalDate}
import java.util.UUID
import scala.concurrent.duration.*

/** data-model.md's `SendsView` and `ProfilesView`: what the routes, the dashboard and the deposit-day reset read. */
class ViewsSuite extends munit.FunSuite:
  override val munitTimeout = 120.seconds
  private lazy val kit = SharedKit.kit

  override def beforeAll(): Unit =
    kit.reset()
    kit.configure(Map("DepositConfirmed" -> "7"))

  private def sends    = kit.views.forView(SendsView)
  private def profiles = kit.views.forView(ProfilesView)

  private def player(): String =
    val id = s"v-${UUID.randomUUID().toString.take(8)}"
    val at = Instant.now().minusSeconds(60)
    players(f.PlayerRegistered(id, at, None, None, None, Some("ZA"), Some("EUR"), None, at, email = Some(Personal(s"$id@example.com"))))
    players(f.PlayerConsentChanged(id, "email", true, "registration", at))
    id

  private def players(fact: f.CasinoPlayersFact): Unit =
    kit.components.forKeyValueEntity(EntityId(fact.playerId)).call(Profile.players).invoke(PlayersFact(fact)): Unit

  private def request(playerId: String, trigger: String = "deposit_confirmed") =
    SendRequest(s"$trigger/${UUID.randomUUID()}", playerId, trigger, Some(trigger), "marketing", SendRequest.Crm,
      s"fact-${UUID.randomUUID()}", Instant.now(), transactional = Some("DepositConfirmed"),
      data = Vector(DataField("amount", ProfileState.personal(playerId, "10.00"))))

  private def run(r: SendRequest): SendState =
    kit.components.forWorkflow(EntityId(r.key)).call(Send.start).invoke(r): Unit
    kit.eventually(s"send ${r.key} settled") {
      Option(kit.components.forWorkflow(EntityId(r.key)).call(Send.outcome).invoke()).filter(s => s.ended || s.outcome == Outcome.Parked)
    }

  test("a player's sends, newest first, with no personal value in a row") {
    val p = player()
    val first = request(p)
    run(first)
    val second = request(p, "payment_failed")
    run(second)
    val page = kit.eventually("both rows accepted")(Option(SendsView.byPlayer(sends, p))
      .filter(pg => pg.total == 2 && pg.rows.forall(_.outcome == Outcome.Accepted)))
    assertEquals(page.rows.map(_.key), Vector(second.key, first.key))
    assertEquals(page.rows.map(_.outcome), Vector(Outcome.Accepted, Outcome.Accepted))
    val rows = kit.tables.collect { case (t, rs) if t.contains("sends") && t.contains("view") => rs }.flatten.filter(_.contains(p))
    assert(rows.nonEmpty, kit.tables.keys.mkString(", "))
    assert(rows.forall(r => !r.contains(s"$p@example.com") && !r.contains("10.00")), rows.mkString("\n"))
  }

  test("the parked sends, searched by trigger and reason, and the oldest of them") {
    val p = player()
    kit.crm.reset()
    kit.crm.fail(503, 8)
    val a = request(p, "withdrawal_failed")
    run(a)
    val b = request(p, "kyc_approved")
    run(b)
    kit.crm.reset()
    val parked = kit.eventually("two parked")(Option(SendsView.parked(sends)).filter(pg => Set(a.key, b.key).subsetOf(pg.rows.map(_.key).toSet)))
    assert(parked.rows.forall(_.parked))
    assertEquals(SendsView.parked(sends, Some("WITHDRAWAL")).rows.map(_.key).filter(Set(a.key, b.key)), Vector(a.key))
    assertEquals(SendsView.parked(sends, Some("answered 503")).rows.map(_.key).filter(Set(a.key, b.key)).toSet, Set(a.key, b.key))
    val oldest = SendsView.oldestParked(sends).map(_.parkedAt.get).get
    assert(!oldest.isAfter(parked.rows.flatMap(_.parkedAt).min), s"$oldest")
    kit.components.forWorkflow(EntityId(a.key)).call(Send.discard).invoke(Discard("op-1", "test")): Unit
    kit.components.forWorkflow(EntityId(b.key)).call(Send.discard).invoke(Discard("op-1", "test")): Unit
    kit.eventually("both discarded")(Option(SendsView.parked(sends)).filter(pg => !pg.rows.exists(r => Set(a.key, b.key)(r.key))))
  }

  test("the counts by outcome since a time") {
    val since = Instant.now()
    val p = player()
    run(request(p))
    val counts = kit.eventually("counted")(Option(SendsView.counts(sends, since)).filter(_.get(Outcome.Accepted).exists(_ >= 1)))
    assert(!counts.contains(Outcome.Pending) || counts(Outcome.Pending) >= 0)
    assertEquals(SendsView.counts(sends, Instant.now().plusSeconds(3600)), Map.empty)
  }

  test("the reset's players: a count above zero on an earlier day, paged by player id") {
    val eur   = Currency.unsafe("EUR")
    val today = LocalDate.now(java.time.ZoneOffset.UTC)
    val at    = Instant.now().minusSeconds(60)
    val ids   = Vector.fill(3)(player()).sorted
    for id <- ids do
      val tx = UUID.randomUUID().toString
      kit.components.forKeyValueEntity(EntityId(id)).call(Profile.money)
        .invoke(MoneyFact(f.DepositConfirmed(id, eur, tx, MinorUnits.unsafe(1000L), s"ref-$tx", 1, SignedMinorUnits(1000L),
          MinorUnits.unsafe(0L), at))): Unit
    val tomorrow = today.plusDays(2)
    val found = kit.eventually("three rows")(Option(ProfilesView.dailyCountAbove(profiles, tomorrow).map(_.playerId).filter(ids.contains))
      .filter(_.size == 3))
    assertEquals(found, ids)
    assertEquals(ProfilesView.dailyCountAbove(profiles, tomorrow, after = ids(0)).map(_.playerId).filter(ids.contains), ids.drop(1))
    assertEquals(ProfilesView.dailyCountAbove(profiles, today.minusDays(5)).map(_.playerId).filter(ids.contains), Vector.empty)
  }

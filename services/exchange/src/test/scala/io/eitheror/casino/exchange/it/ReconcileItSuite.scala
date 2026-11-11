package io.eitheror.casino.exchange.it

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.contract.facts as f
import io.eitheror.casino.contract.money.{Currency, MinorUnits, SignedMinorUnits}
import io.eitheror.casino.exchange.reconcile.{CheckNow, KeyTally, KeyTallyEntity, Reconcile, TallyEntity}

import java.util.UUID
import scala.concurrent.duration.*

/**
 * The reconciliation over Postgres (T030): a split trade, the wallet's facts as the wallet publishes
 * them with one leg a unit short, and a check that writes one record naming the market, the family,
 * the key and both figures.
 */
class ReconcileItSuite extends munit.FunSuite with LogCapturing:
  override val munitTimeout: Duration = 300.seconds

  private def kit = SharedKit.kit
  override def beforeEach(context: BeforeEach): Unit = kit.reset()

  private def key(m: String, family: String, k: String): KeyTally =
    kit.components.forKeyValueEntity(EntityId(KeyTallyEntity.id(m, family, k))).call(KeyTallyEntity.get).invoke()

  test("a deliberate difference is one audit record naming the market, the family, the key and both figures") {
    val m = s"reconcile-${UUID.randomUUID().toString.take(8)}"
    assertEquals(kit.open(m, Vector("yes", "no"), "EUR").status, 200)
    kit.wallet.real.put("ada", 1000L); kit.wallet.real.put("bob", 1000L)
    assertEquals(kit.place("ada", m, s"$m-a", "buy", "yes", 10, 60).status, 200)
    assertEquals(kit.place("bob", m, s"$m-b", "buy", "no", 10, 40).status, 200)
    val splits = kit.eventually("the split on the exchange's side") {
      Option(Reconcile.differences(m).filter(_.family == "split").map(_.key)).filter(_.size == 2)
    }
    val trade = splits.head.stripSuffix("/ada") // the market's trade id, `<market>/<trade>` (R4)

    val eur = Currency.unsafe("EUR"); val at = kit.now(); val z = SignedMinorUnits(0)
    def u(n: Long) = MinorUnits.unsafe(n)
    kit.publish(f.MoneyTransactions, "ada/EUR", f.ReservationPlaced("ada", eur, s"$m-1", s"$m/$m-a", u(600), z, u(0), u(600), at))
    kit.publish(f.MoneyTransactions, "bob/EUR", f.ReservationPlaced("bob", eur, s"$m-2", s"$m/$m-b", u(400), z, u(0), u(400), at))
    kit.publish(f.MoneyTransactions, "ada/EUR",
      f.AccountDebit("ada", eur, s"$m-3", "pool", m, "split", s"$trade/ada", u(600), z, u(0), u(0), at, orderId = Some(s"$m/$m-a")))
    kit.publish(f.MoneyTransactions, "bob/EUR",
      f.AccountDebit("bob", eur, s"$m-4", "pool", m, "split", s"$trade/bob", u(399), z, u(0), u(0), at, orderId = Some(s"$m/$m-b")))
    // A fact published twice counts once.
    kit.publish(f.MoneyTransactions, "ada/EUR", f.ReservationPlaced("ada", eur, s"$m-1", s"$m/$m-a", u(600), z, u(0), u(600), at))
    kit.eventually("the wallet's four moves") {
      Option.when(key(m, "split", s"$trade/bob").wallet == 399 && key(m, "reservation", s"$m/$m-a").sources.size == 2)(())
    }
    assertEquals(key(m, "reservation", s"$m/$m-a").wallet, 600L, "a fact published twice counts once")
    // The unbalanced keys' view lags the key's own: one difference may be ada's split matched and bob's still at 0.
    val differences = kit.eventually("the keys' view caught up") {
      Option(Reconcile.differences(m)).filter(_.map(d => (d.family, d.key, d.wallet)) == Vector(("split", s"$trade/bob", 399L)))
    }

    kit.components.forKeyValueEntity(EntityId(m)).call(TallyEntity.checkNow).invoke(CheckNow(kit.now(), last = true, differences)): Unit
    val records = kit.eventually("the difference recorded") {
      Option(kit.audit.filter(r => r.subjectId == m && r.action == "exchange.reconciliation_difference")).filter(_.nonEmpty)
    }
    assertEquals(records.size, 1)
    val r = records.head
    assertEquals(r.reasonCode, Some("split"))
    assertEquals(r.changes.map(c => c.field -> c.after), Vector("key" -> Some(s"$trade/bob"), "wallet" -> Some("399"),
      "exchange" -> Some("400"), "check" -> Some("final")))
  }

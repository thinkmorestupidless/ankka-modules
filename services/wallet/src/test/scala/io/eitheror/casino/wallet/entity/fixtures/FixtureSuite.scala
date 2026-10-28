package io.eitheror.casino.wallet.entity.fixtures

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.testkit.EventSourcedTestKit
import io.eitheror.casino.wallet.entity.*
import io.eitheror.casino.wallet.entity.fixtures.FixtureRunner.*
import io.eitheror.casino.wallet.funding.{Leg, Parts, Resolver}

/**
 * One handler's fixtures: every case of the original's file is a test named `<handler>/<case>`,
 * or is reported ignored with the reason the rebuild does not have that behaviour. A case is never
 * dropped: the first test checks that every ignored name is a real case of the file.
 */
abstract class FixtureSuite(handler: String) extends munit.FunSuite:

  /** Case name → why the rebuild does not do it (a dropped behaviour, never a failure). */
  protected def ignored: Map[String, String] = Map.empty

  protected def check(c: Case): Unit

  private val cases = FixtureRunner.load(handler)

  test(s"$handler: the file's cases are all here, and every ignored one names a case of the file") {
    assert(cases.nonEmpty, s"no cases in fixtures/$handler.json")
    ignored.keys.foreach(name => assert(cases.exists(_.name == name), s"ignored name '$name' is not a case of $handler"))
  }

  cases.foreach { c =>
    ignored.get(c.name) match
      case Some(reason) => test(s"$handler/${c.name} [ignored: $reason]".ignore)(())
      case None         => test(s"$handler/${c.name}")(check(c))
  }

  // ── shared checks ─────────────────────────────────────────────────────────

  type Kit = EventSourcedTestKit[WalletEntity, WalletState, WalletEvent]

  protected def same(what: String, expected: Option[Long], actual: => Long)(using c: Case): Unit =
    expected.foreach(e => assertEquals(actual, e, s"${c.name}: $what"))

  protected def sameText(what: String, expected: Option[String], actual: => String)(using c: Case): Unit =
    expected.foreach(e => assertEquals(actual, e, s"${c.name}: $what"))

  /** A pipe-set field (`|a|b|`) compared as a set. */
  protected def sameSet(what: String, expected: Option[String], actual: => Iterable[String])(using c: Case): Unit =
    expected.foreach(e => assertEquals(actual.toSet, pipeSet(e), s"${c.name}: $what"))

  /** The wallet's and every record's `set_properties`, against the state after the command. */
  protected def assertWalletAndRecords(state: WalletState)(using c: Case): Unit =
    text(c.wallet, "id").foreach { walletId =>
      c.setOn(walletId).foreach { m =>
        same("wallet real_balance", long(m, "real_balance"), state.realBalance)
        same("wallet restricted_balance", long(m, "restricted_balance"), state.restrictedBalance)
        same("wallet deposit_turnover_pending", long(m, "deposit_turnover_pending"), state.depositTurnoverPending)
        same("wallet deposit_pool_balance", long(m, "deposit_pool_balance"), state.depositPool)
        same("wallet converted_pool_balance", long(m, "converted_pool_balance"), state.convertedPool)
      }
    }
    c.mutations.filter(m => m.op == "set_properties" && m.target.startsWith("restricted-funds/")).foreach { m =>
      val r = state.record(m.target).getOrElse(fail(s"${c.name}: record ${m.target} is not in the wallet")).record
      val p = m.properties
      sameText(s"${m.target} status", text(p, "status"), r.status)
      same(s"${m.target} balance", long(p, "balance"), r.balance)
      same(s"${m.target} release_requirement", long(p, "release_requirement"), r.releaseRequirement)
      same(s"${m.target} released_progress", long(p, "released_progress"), r.releasedProgress)
      same(s"${m.target} max_convertible_amount", long(p, "max_convertible_amount"), r.maxConvertible)
      same(s"${m.target} remaining_allowance", long(p, "remaining_allowance"), r.remainingAllowance)
      same(s"${m.target} deposits_since_release", long(p, "deposits_since_release"), r.depositsSinceRelease)
      same(s"${m.target} open_round_count", long(p, "open_round_count"), r.openRoundCount)
    }

  /**
   * Spec 025: every new transaction asserting a movement's parts under `prefix` (`funded_deposit_amount`,
   * `won_converted_amount`, `from_deposit_amount`, …) against the parts the wallet recorded.
   */
  protected def assertParts(prefix: String, actual: Parts)(using c: Case): Unit =
    c.partsTransactions(prefix).foreach { t =>
      same(s"${prefix}_deposit_amount", long(t, s"${prefix}_deposit_amount"), actual.deposit)
      same(s"${prefix}_converted_amount", long(t, s"${prefix}_converted_amount"), actual.converted)
    }

  /** The result's balances against the reply. */
  protected def assertResultBalances(o: Outcome)(using c: Case): Unit =
    c.result.foreach { r =>
      same("result real_balance", long(r, "real_balance"), o.realBalance)
      same("result restricted_balance", long(r, "restricted_balance"), o.restrictedBalance)
    }

  /** A refusal: the original's code, nothing moved, only the refusal recorded. */
  protected def assertRefused(error: JsonNode, before: WalletState, kit: Kit, events: Vector[WalletEvent], o: Outcome)(using c: Case): Unit =
    assertEquals(o.code, text(error, "code"), s"${c.name}: refusal code (reply $o)")
    assert(events.forall(_.isInstanceOf[WalletEvent.CommandRefused]), s"${c.name}: a refusal persisted $events")
    val after = kit.currentState
    assertEquals((after.realBalance, after.restrictedBalance, after.depositTurnoverPending, after.records),
      (before.realBalance, before.restrictedBalance, before.depositTurnoverPending, before.records), s"${c.name}: a refusal moved money")

  protected def accepted(o: Outcome)(using c: Case): Unit =
    assert(o.accepted, s"${c.name}: refused as ${o.code} ${o.message}, the original accepted it")

  protected def total(legs: Seq[Leg]): Long = legs.map(_.amount).sum

  protected def breakdown(legs: Seq[Leg]): String = Resolver.formatLegList(legs)

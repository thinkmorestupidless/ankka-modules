package io.eitheror.casino.payouts.it

import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.contract.facts.*
import io.eitheror.casino.contract.money.{Currency, MinorUnits, SignedMinorUnits}
import io.eitheror.casino.contract.personal.Personal
import io.eitheror.casino.payouts.player.{Kyc, PlayerPayouts}
import io.eitheror.casino.payouts.request.*
import io.eitheror.casino.payouts.terminals.{CatalogueTimer, TerminalCatalogue}

import java.time.Instant

/** The plumbing: the components start against Postgres, the consumers read the broker, the publishers write it, the timer refreshes. */
class KitSuite extends munit.FunSuite:
  private lazy val kit = SharedKit.kit

  override def munitTimeout = scala.concurrent.duration.Duration(180, "s")
  override def beforeEach(context: BeforeEach): Unit = kit.reset()

  private def player(id: String) = kit.components.forEventSourcedEntity(EntityId(id)).call(PlayerPayouts.get).invoke()

  test("casino.players and money.transactions reach the player's record"):
    val at = Instant.now()
    kit.publish(CasinoPlayers, "kit-p1", PlayerRegistered("kit-p1", at, None, None, None, None, None, None, at,
      givenName = Some(Personal("Ana")), familyName = Some(Personal("Silva")), email = Some(Personal("ana@example.test"))))
    kit.publish(CasinoPlayers, "kit-p1", PlayerKycVerified("kit-p1", "pending", "verified", "operator", None, None, at))
    kit.publish(MoneyTransactions, "kit-p1/EUR", DepositConfirmed("kit-p1", Currency.unsafe("EUR"), "t-1", MinorUnits.unsafe(5000), "ref-1",
      1, SignedMinorUnits(5000), MinorUnits.unsafe(0), at, psp = Some("UnityFinance"), scheme = Some("SEPA")))
    val s = kit.eventually("the record holds all three") {
      Some(player("kit-p1")).filter(s => s.kyc == Kyc.Verified && s.deposits.nonEmpty && s.names.isDefined)
    }
    assertEquals((s.email, s.deposits), (Some(Personal("ana@example.test")), Map("unityfinance" -> Set("SEPA"))))

  test("an opened request counts on the player and is published, with its audit record"):
    val terminal = TerminalRef("t-1", "sb-1", "Bank", "unityfinance", "EUBANKING", true)
    kit.components.forEventSourcedEntity(EntityId("kit-r1")).call(WithdrawalRequest.open)
      .invoke(Open("kit-r1", "kit-p2", 5000L, 5000L, "EUR", terminal, Payee(), Forfeit(), "verified", Instant.now())): Unit
    kit.eventually("counted")(Some(player("kit-p2")).filter(_.recent.exists(_.id == "kit-r1")))
    val requested = kit.eventually("published") {
      kit.facts(CasinoWithdrawals).collectFirst { case f: WithdrawalRequested if f.payoutId == "kit-r1" => f }
    }
    assertEquals((requested.playerId, requested.amountCents.value, requested.gateway), ("kit-p2", 5000L, Some("unityfinance")))
    kit.eventually("audited")(kit.facts(PlatformAudit).collectFirst { case r: AuditRecord if r.subjectId == "kit-r1" => r })

  test("the catalogue timer is scheduled, and refreshes a held currency from the gateway"):
    assert(kit.timerExists(CatalogueTimer.name(kit.slug)))
    kit.gateway.terminals("EUR", FakeGateway.terminal("t-9", "unityfinance"))
    val catalogue = kit.components.forKeyValueEntity(EntityId(kit.slug))
    catalogue.call(TerminalCatalogue.refresh).invoke(io.eitheror.casino.payouts.terminals.Refresh("EUR", Vector.empty, Instant.EPOCH)): Unit
    kit.fireTimer(CatalogueTimer.name(kit.slug))
    kit.eventually("refreshed")(catalogue.call(TerminalCatalogue.get).invoke().of("EUR").filter(_.exists(_.id == "t-9")))

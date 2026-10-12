package io.eitheror.casino.gameprovider.facts

import com.thinkmorestupidless.ankka.testkit.ConsumerTestKit
import io.eitheror.casino.contract.facts.{CasinoRounds, RoundSettled}
import io.eitheror.casino.gameprovider.rounds.*

import java.time.Instant

/** contracts/round-settled.md, through the consumer alone (FR-019). */
class RoundFactsSuite extends munit.FunSuite:

  private val at = Instant.parse("2026-10-09T10:00:00Z")
  private val summary = SettlementSummary("st8", "r-1", "p-1", "EUR", Some("book-of-ra"), Some("gs-1"), stake = 300, win = 500,
    outcome = "win", providerTransactionIds = Vector("t-1", "t-2", "c-1"), walletTransactionIds = Vector("w1", "w2", "w3", "w4"),
    freeRoundsGrant = None, at = at)

  test("a settled round publishes one round_settled with every field of the contract") {
    val result = ConsumerTestKit.of(RoundFacts).onMessage(RoundEvent.RoundSettled(summary), subject = "st8/r-1", sequenceNumber = 7)
    val fact   = result.payloads.loneElement.asInstanceOf[RoundSettled]
    assertEquals((fact.playerId, fact.currency.code, fact.roundId, fact.provider, fact.providerRoundId), ("p-1", "EUR", "st8/r-1", "st8", "r-1"))
    assertEquals((fact.gameId, fact.sessionId, fact.outcome), ("book-of-ra", "gs-1", "win"))
    assertEquals((fact.stakeCents.value, fact.winCents.value, fact.netResultCents.value), (300L, 500L, 200L))
    assertEquals(fact.providerTransactionIds, Vector("t-1", "t-2", "c-1"))
    assertEquals(fact.walletTransactionIds, Vector("w1", "w2", "w3", "w4"))
    assertEquals(fact.transactionIds, fact.walletTransactionIds, "kept for readers written against it")
    assertEquals(fact.freeRoundsGrant, None)
    assertEquals(fact.occurredAt, at)
  }

  test("it is keyed by the wallet, and its ce-id is the round and the event's sequence, so a redelivery is the same id") {
    val kit    = ConsumerTestKit.of(RoundFacts)
    val first  = kit.onMessage(RoundEvent.RoundSettled(summary), subject = "st8/r-1", sequenceNumber = 7)
    val second = kit.onMessage(RoundEvent.RoundSettled(summary), subject = "st8/r-1", sequenceNumber = 7)
    assertEquals(first.recordKeys, Vector(Some("p-1/EUR")))
    assertEquals(first.messages.head.metadata.get("ce-id"), Some("st8/r-1/7"))
    assertEquals(second.messages.head.metadata.get("ce-id"), first.messages.head.metadata.get("ce-id"))
  }

  test("a cancelled round publishes outcome cancelled; a free round carries its grant") {
    val kit       = ConsumerTestKit.of(RoundFacts)
    val cancelled = kit.onMessage(RoundEvent.RoundCancelled(summary.copy(outcome = "cancelled", stake = 0, win = 0)), "st8/r-1", 9)
    assertEquals(cancelled.payloads.loneElement.asInstanceOf[RoundSettled].outcome, "cancelled")
    val free = kit.onMessage(RoundEvent.RoundSettled(summary.copy(freeRoundsGrant = Some("g-1"), walletTransactionIds = Vector.empty)), "st8/r-2", 3)
    assertEquals(free.payloads.loneElement.asInstanceOf[RoundSettled].freeRoundsGrant, Some("g-1"))
  }

  test("every other event of a round publishes nothing") {
    val kit = ConsumerTestKit.of(RoundFacts)
    val opened = kit.onMessage(RoundEvent.RoundOpened(RoundOpening("st8", "r-1", "p-1", "EUR", None, None, at)), "st8/r-1", 1)
    assertEquals(opened.payloads, Vector.empty)
  }

  test("the fact is the contract's family on the contract's topic") {
    assertEquals(RoundFacts.produceTo, Some(CasinoRounds.name))
    assertEquals(RoundFacts.outputSerializer.map(_.manifest), Some(CasinoRounds.contractName))
  }

  extension [A](xs: Vector[A]) def loneElement: A = { assertEquals(xs.size, 1, xs.toString); xs.head }

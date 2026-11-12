package io.eitheror.casino.gameprovider.facts

import com.thinkmorestupidless.ankka.testkit.{ConsumerTestKit, LogCapturing}
import io.eitheror.casino.contract.facts.{CasinoRounds, RoundOpened, RoundSettled}
import io.eitheror.casino.gameprovider.rounds.*

import java.time.Instant

/** contracts/round-settled.md, through the consumer alone (FR-019). */
class RoundFactsSuite extends munit.FunSuite with LogCapturing:

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

  test("the game's name and type are the catalogue's the round recorded, and absent for a game it did not hold (spec 015)") {
    val kit   = ConsumerTestKit.of(RoundFacts)
    val named = kit.onMessage(RoundEvent.RoundSettled(summary.copy(gameName = Some("Book of Ra"), gameType = Some("slots"))), "st8/r-1", 7)
    val fact  = named.payloads.loneElement.asInstanceOf[RoundSettled]
    assertEquals((fact.gameName, fact.gameType), (Some("Book of Ra"), Some("slots")))
    val unnamed = kit.onMessage(RoundEvent.RoundSettled(summary), "st8/r-2", 4).payloads.loneElement.asInstanceOf[RoundSettled]
    assertEquals((unnamed.gameName, unnamed.gameType, unnamed.gameId), (None, None, "book-of-ra"), "the game id stands for the name")
  }

  test("a round's opening publishes one round_opened, keyed and identified as round_settled is (spec 016 G3)") {
    val opening = RoundOpening("st8", "r-1", "p-1", "EUR", Some("book-of-ra"), Some("gs-1"), at)
    val result  = ConsumerTestKit.of(RoundFacts).onMessage(RoundEvent.RoundOpened(opening), subject = "st8/r-1", sequenceNumber = 1)
    val fact    = result.payloads.loneElement.asInstanceOf[RoundOpened]
    assertEquals(fact, RoundOpened("p-1", fact.currency, "st8/r-1", "st8", "r-1", "book-of-ra", "gs-1", None, at))
    assertEquals(fact.currency.code, "EUR")
    assertEquals(result.recordKeys, Vector(Some("p-1/EUR")))
    assertEquals(result.messages.head.metadata.get("ce-id"), Some("st8/r-1/1"))
  }

  test("round_opened names a free round's grant, and is empty-named for a round no ticket named (spec 016 G3)") {
    val kit  = ConsumerTestKit.of(RoundFacts)
    val free = kit.onMessage(RoundEvent.RoundOpened(RoundOpening("st8", "r-2", "p-1", "EUR", Some("g"), Some("gs-1"), at, freeRoundsGrant = Some("g-1"))), "st8/r-2", 1)
    assertEquals(free.payloads.loneElement.asInstanceOf[RoundOpened].freeRoundsGrant, Some("g-1"))
    val bare = kit.onMessage(RoundEvent.RoundOpened(RoundOpening("st8", "r-3", "p-1", "EUR", None, None, at)), "st8/r-3", 1)
    val fact = bare.payloads.loneElement.asInstanceOf[RoundOpened]
    assertEquals((fact.gameId, fact.sessionId, fact.freeRoundsGrant), ("", "", None))
  }

  test("round_settled carries the round's opening and the real-money parts of its stake and win (spec 016 G3, G7)") {
    val opened = Instant.parse("2026-10-09T09:59:00Z")
    val kit    = ConsumerTestKit.of(RoundFacts)
    val fact = kit.onMessage(RoundEvent.RoundSettled(summary.copy(openedAt = Some(opened), realStake = Some(200), realWin = Some(500))), "st8/r-1", 7)
      .payloads.loneElement.asInstanceOf[RoundSettled]
    assertEquals((fact.openedAt, fact.realStakeCents.map(_.value), fact.realWinCents.map(_.value)), (Some(opened), Some(200L), Some(500L)))
    val older = kit.onMessage(RoundEvent.RoundSettled(summary), "st8/r-2", 4).payloads.loneElement.asInstanceOf[RoundSettled]
    assertEquals((older.openedAt, older.realStakeCents, older.realWinCents), (None, None, None), "a summary without them leaves them out")
  }

  test("every other event of a round publishes nothing") {
    val kit = ConsumerTestKit.of(RoundFacts)
    val debit = kit.onMessage(RoundEvent.FreeRoundsTagged("g-1"), "st8/r-1", 2)
    assertEquals(debit.payloads, Vector.empty)
    val tx = ProviderTransaction("t-1", "debit", 100, None, Vector.empty, StoredAnswer("ok", Some(900), Some("EUR")), at)
    assertEquals(kit.onMessage(RoundEvent.DebitRecorded(tx, None), "st8/r-1", 3).payloads, Vector.empty)
  }

  test("the fact is the contract's family on the contract's topic") {
    assertEquals(RoundFacts.produceTo, Some(CasinoRounds.name))
    assertEquals(RoundFacts.outputSerializer.map(_.manifest), Some(CasinoRounds.contractName))
  }

  extension [A](xs: Vector[A]) def loneElement: A = { assertEquals(xs.size, 1, xs.toString); xs.head }

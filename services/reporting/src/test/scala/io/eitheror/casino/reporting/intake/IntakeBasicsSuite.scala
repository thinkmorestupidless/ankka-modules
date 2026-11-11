package io.eitheror.casino.reporting.intake

import io.eitheror.casino.contract.facts.*
import io.eitheror.casino.contract.money.{Currency, MinorUnits}
import io.eitheror.casino.reporting.ReportingCodecs

import java.time.Instant

/** T017: the fact id, the lenient reading, the generation's ids and the codecs' settings. */
class IntakeBasicsSuite extends munit.FunSuite:
  private val at  = Instant.parse("2026-11-08T10:00:00Z")
  private val eur = Currency.unsafe("EUR")

  private def captured(at: Instant = at) = PaymentCaptured("pay-1", "p-1", "psp", MinorUnits.unsafe(1000), eur, at)

  test("a fact's id is 32 hex characters, the same for a redelivery, other for another time or topic") {
    val id = FactId.of(PaymentsPayments, captured())
    assert(id.matches("[0-9a-f]{32}"), id)
    assertEquals(FactId.of(PaymentsPayments, captured()), id)
    assertNotEquals(FactId.of(PaymentsPayments, captured(at.plusMillis(1))), id)
    // engagement's rule: the topic's name is part of the digest
    val withdrawal = WithdrawalCompleted("p-1", "w-1", MinorUnits.unsafe(1000), eur, at)
    assertNotEquals(FactId.of(CasinoWithdrawals, withdrawal), FactId.of(PaymentsPayments, captured()))
  }

  test("every fact's time is its occurredAt") {
    assertEquals(FactId.occurredAt(captured()), at)
    assertEquals(FactId.occurredAt(PlayerLoggedIn("p-1", "s-1", at.plusSeconds(5))), at.plusSeconds(5))
  }

  test("the seen window keeps the last 1,000 ids") {
    val full = (1 to 1000).foldLeft(Vector.empty[String])((s, i) => FactId.remember(s, s"f-$i"))
    assertEquals(full.length, 1000)
    val next = FactId.remember(full, "f-1001")
    assertEquals((next.length, next.head, next.last), (1000, "f-2", "f-1001"))
  }

  test("the lenient serializer answers none for what it cannot read, and reads what it can") {
    val s = Lenient.serializer(PaymentsPayments.serializer)
    assertEquals(s.fromBytes("""{"type":"payment.teleported"}""".getBytes), None)
    assertEquals(s.fromBytes(PaymentsPayments.serializer.toBytes(captured())), Some(captured()))
    assertEquals(s.manifest, PaymentsPayments.serializer.manifest)
  }

  test("every topic is read from the earliest offset under its contract") {
    Lenient.from(MoneyTransactions) match
      case t: com.thinkmorestupidless.ankka.sdk.ChangeSource.Topic[?] =>
        assertEquals(t.topic, "money.transactions")
        assertEquals(t.startFrom, Some(com.thinkmorestupidless.ankka.sdk.StartFrom.Earliest))
        assertEquals(t.options.contract.map(_.name), Some("money.transactions.v1"))
      case other => fail(s"not a topic source: $other")
  }

  test("the generation is the first segment of an id and the suffix of a component id") {
    assertEquals(Generation.id(1, "p-1", "EUR"), "g1/p-1/EUR")
    assertEquals(Generation.componentId("money-facts", 2).toString, "money-facts-g2")
    assertEquals(Generation.of("g12/p-1/EUR"), Some(12))
    assertEquals(Generation.of("p-1/EUR"), None)
    assertEquals(Generation.of("g1"), None)
    assert(Generation.belongs("g1/2026-11-08", 1))
    assert(!Generation.belongs("g2/2026-11-08", 1))
    assertEquals(Generation.rest("g1/p-1/EUR"), "p-1/EUR")
  }

  test("a stored type leaves its defaults out; a row writes them") {
    final case class Sample(a: Long = 0, b: Option[String] = None, c: Vector[String] = Vector.empty)
    val stored = ReportingCodecs.serializer[Sample]("sample")
    assertEquals(String(stored.toBytes(Sample())), "{}")
    assertEquals(stored.manifest, "reporting.sample")
    assertEquals(String(ReportingCodecs.row[Sample]("sample-row").toBytes(Sample())), """{"a":0,"c":[]}""")
  }

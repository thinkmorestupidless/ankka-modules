package io.eitheror.casino.wallet.accounts

import io.eitheror.casino.wallet.entity.{MoneyCommand, Purpose}

import java.time.Instant

/** The move flow's digest across FR-008a: a start without an order keeps the digest it had before. */
class MoveDigestSuite extends munit.FunSuite:

  private val t0 = Instant.parse("2026-10-31T10:00:00Z")

  /** `AccountMoveStart` as it was before the amendment, field for field. */
  private final case class Before(accountId: String, direction: String, purpose: String, player: String, amount: Long, key: String,
      reference: String, caller: String, at: Instant)

  test("a start with no order has the digest of the start as it was before the amendment") {
    val start  = AccountMoveStart("pool/m1/EUR", "to-account", Purpose.Split, "ada", 600, "sp-1", "sp-1", "service:p/exchange", t0)
    val before = Before("pool/m1/EUR", "to-account", Purpose.Split, "ada", 600, "sp-1", "sp-1", "service:p/exchange", t0)
    assertEquals(AccountMoveWorkflow.digest(start), MoneyCommand.digestOf(Purpose.Split, before))
  }

  test("an order or an unused part changes the digest, so a reuse with either is told from a repeat") {
    val start     = AccountMoveStart("pool/m1/EUR", "to-account", Purpose.Split, "ada", 600, "t1/ada", "t1/ada", "service:p/exchange", t0)
    val withOrder = start.copy(orderId = Some("o1"))
    assertNotEquals(AccountMoveWorkflow.digest(withOrder), AccountMoveWorkflow.digest(start))
    assertNotEquals(AccountMoveWorkflow.digest(withOrder.copy(unused = 50)), AccountMoveWorkflow.digest(withOrder))
  }

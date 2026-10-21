package io.eitheror.casino.merchant.payments

/** The original's `PaymentStatus`: the stored words and the frontend's. */
class StatusSuite extends munit.FunSuite:

  test("the terminal statuses read as the frontend's words; anything else passes through") {
    assertEquals(Status.toApi("captured"), "COMPLETED")
    assertEquals(Status.toApi("failed"), "FAILED")
    assertEquals(Status.toApi("expired"), "EXPIRED")
    assertEquals(Status.toApi("AWAITING_REDIRECT"), "AWAITING_REDIRECT")
    assertEquals(Status.toApi("something-new"), "something-new")
  }

  test("captured, failed and expired are terminal, the awaiting two are not") {
    assert(Status.Terminal == Set("captured", "failed", "expired"))
    assert(!Status.isTerminal(Status.AwaitingRedirect) && !Status.isTerminal(Status.AwaitingCrypto))
  }

  test("the backoffice filter counts every non-terminal status as pending (FR-029)") {
    assert(Status.matches("pending", "AWAITING_CRYPTO") && !Status.matches("pending", "captured"))
    assert(Status.matches("all", "expired") && Status.matches("failed", "failed") && !Status.matches("failed", "expired"))
  }

  test("a failure with no reason of its own reads as the original's default") {
    assertEquals(FailureReason.Unknown, "Payment could not be processed")
    assertEquals(FailureReason.Abandoned, "Payment not completed")
  }

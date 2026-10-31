package io.eitheror.casino.payouts.request

import io.eitheror.casino.contract.personal.Personal.reveal

/** A provider's reason is published only when it is a code; its own text is a personal note (FR-025, FR-027). */
class ReasonCodeSuite extends munit.FunSuite:
  test("a code is kept as one: the gateway's words and a provider's numeric code"):
    for c <- Vector("missing_credentials", "terminal_inactive", "93", "payout_failed") do
      assertEquals(ReasonCode.split(Some(c)), (Some(c), None), c)

  test("free text is a note and never a code: a message, upper case, spaces, punctuation, a name"):
    for t <- Vector("Payout to Jane Doe declined", "FAILED", "Pending", "insufficient funds", "card-declined", "x" * 65) do
      val (code, note) = ReasonCode.split(Some(t))
      assertEquals(code, None, t)
      assertEquals(note.map(_.reveal), Some(t), t)

  test("no reason, or a blank one, is neither"):
    assertEquals(ReasonCode.split(None), (None, None))
    assertEquals(ReasonCode.split(Some("  ")), (None, None))

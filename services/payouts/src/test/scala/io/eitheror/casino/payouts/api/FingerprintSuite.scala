package io.eitheror.casino.payouts.api

import io.eitheror.casino.payouts.request.{Amount, Draft}

/** A key's body digest: the same body under any key is the same request; any field changed is another. */
class FingerprintSuite extends munit.FunSuite:
  private val d = Draft(Some("k-1"), Amount.Whole(5000L), Some("EUR"), Some("t-1"), mobile = Some("447700900123"))

  test("the key is not part of the body, and the same body digests the same"):
    assertEquals(Requests.fingerprint(d), Requests.fingerprint(d.copy(key = Some("k-2"))))

  test("each field changed is another body, an absent field apart from an empty one"):
    val others = Vector(d.copy(amount = Amount.Whole(5001L)), d.copy(currency = Some("GBP")), d.copy(mobile = Some("447700900124")),
      d.copy(mobile = None), d.copy(confirmForfeit = true), d.copy(amount = Amount.NotWhole), d.copy(walletAddress = Some("")))
    others.foreach(o => assertNotEquals(Requests.fingerprint(o), Requests.fingerprint(d), o))

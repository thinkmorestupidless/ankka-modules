package io.eitheror.casino.merchant.it

import java.util.concurrent.CompletableFuture

/** SC-004: a capture delivered ten times, at once, credits once and raises the deposit count once. */
class RepeatSuite extends SharedKitSuite:

  test("ten simultaneous deliveries of one capture credit once") {
    val r  = kit.subBrand()
    val id = kit.payment(r)
    val replies = (1 to 10).map(_ => CompletableFuture.supplyAsync(() => kit.capture(id))).map(_.get())
    assert(replies.forall(_.status == 200), replies.toString)
    val player = kit.paymentState(id).opened.get.playerId
    assertEquals((kit.wallet.depositCount("acme", player, "EUR"), kit.wallet.balance("acme", player, "EUR")), (1, 5000L))
  }

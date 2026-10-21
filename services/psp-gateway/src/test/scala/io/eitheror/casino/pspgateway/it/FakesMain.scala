package io.eitheror.casino.pspgateway.it

import io.eitheror.casino.pspgateway.sessions.Provider

/**
 * `merchant` and the brands' `payouts` as the suites fake them, on fixed ports, for quickstart §6 when
 * `merchant` itself is not run: `sbt "Test/runMain io.eitheror.casino.pspgateway.it.fakesMain"`, then
 * the gateway with `-Dankka.local-services.merchant=http://localhost:9000` and
 * `-Dankka.local-services.payouts=http://localhost:9002`. Sub-brand `sb-1` holds psp-stub's credential
 * for every provider.
 */
@main def fakesMain(merchantPort: Int = 9000, payoutsPort: Int = 9002): Unit =
  val merchant = FakeMerchant(merchantPort)
  val payouts  = FakePayouts(payoutsPort)
  Provider.values.foreach(p => merchant.credential("sb-1", p.wire, PspStub.credentials(p)))
  println(s"merchant on ${merchant.address}, payouts on ${payouts.address}; ctrl-c to stop")
  sys.addShutdownHook { merchant.stop(); payouts.stop() }
  Thread.currentThread().join()

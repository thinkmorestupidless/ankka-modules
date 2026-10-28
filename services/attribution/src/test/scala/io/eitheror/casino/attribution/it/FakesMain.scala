package io.eitheror.casino.attribution.it

/**
 * Quickstart §6: the programme as a `FakeNetwork` on 9103, for a local run of the service. Its key document
 * is `/keys`, its revocation list `/revocations`, and `/tag?aff=…&lnk=…` mints a tag under its key.
 * `sbt "Test/runMain io.eitheror.casino.attribution.it.fakes"`; stopped with Ctrl-C.
 */
@main def fakes(): Unit =
  val network = FakeNetwork("programme", bindPort = 9103)
  println(s"FakeNetwork programme on ${network.address}: keys ${network.keyDocumentUrl}, revocations ${network.revocationsUrl}, " +
    s"a tag at ${network.address}/tag?aff=aff-1&lnk=lnk-1")
  sys.addShutdownHook(network.stop())
  Thread.currentThread().join()

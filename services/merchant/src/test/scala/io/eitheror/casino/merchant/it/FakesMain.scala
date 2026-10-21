package io.eitheror.casino.merchant.it

/**
 * `psp-gateway` and the brand's wallet as the suites fake them, on fixed loopback ports, for a local run
 * of the service (quickstart §7): `sbt "Test/runMain io.eitheror.casino.merchant.it.FakesMain"`, then
 * the service with `-Dankka.local-services.psp-gateway=http://127.0.0.1:18301` and
 * `-Dankka.local-services.wallet=http://127.0.0.1:18302`. Every request either receives is printed.
 */
object FakesMain:

  def main(args: Array[String]): Unit =
    val gateway = FakeGateway(sys.env.get("FAKE_GATEWAY_PORT").fold(18301)(_.toInt))
    val wallet  = FakeWallet(sys.env.get("FAKE_WALLET_PORT").fold(18302)(_.toInt))
    println(s"psp-gateway ${gateway.address}")
    println(s"wallet      ${wallet.address}")
    var seen = (0, 0)
    while true do
      Thread.sleep(500)
      val (g, w) = (gateway.received, wallet.received)
      g.drop(seen._1).foreach(r => println(s"psp-gateway ${r.method} ${r.path} ${r.body}"))
      w.drop(seen._2).foreach(r => println(s"wallet      ${r.method} ${r.path} ${r.body}"))
      seen = (g.size, w.size)

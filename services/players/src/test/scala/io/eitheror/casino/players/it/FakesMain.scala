package io.eitheror.casino.players.it

/**
 * The neighbours as the suites fake them, on fixed local ports, for `just players-run` without the other
 * brand services: the realm on 9010, `responsible-gambling` 9011, `kyc` 9012, `engagement` 9013, `ingest`
 * 9014, `wallet` 9015, ip-api 9016, the vendor's agent 9017. The realm's admin client is `players-admin`
 * with the secret `fake-admin-secret`; every send's link is printed so a local registration can be verified.
 */
@main def fakes(): Unit =
  val brand = sys.env.getOrElse("CASINO_BRAND", "goldvibe")
  val all = Seq(
    "realm"                -> FakeRealm(brand, 9010),
    "responsible-gambling" -> FakeRg(brand, 9011),
    "kyc"                  -> FakeKyc(brand, 9012),
    "engagement"           -> FakeEngagement(brand, 9013),
    "ingest"               -> FakeIngest(9014),
    "wallet"               -> FakeWallet(brand, 9015),
    "geo"                  -> FakeGeo(9016),
    "vendor-agent"         -> FakeAgent(9017)
  )
  all.foreach((name, f) => println(s"$name on ${f.address}"))
  Thread.currentThread().join()

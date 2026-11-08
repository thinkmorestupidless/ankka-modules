package io.eitheror.casino.programme.postbacks

class AddressesSuite extends munit.FunSuite:
  test("a public https address is admitted") {
    assertEquals(Addresses.refusal("https://tracker.example.com/pb?c={click_id}", plainHttp = false), None)
    assertEquals(Addresses.refusal("https://93.184.216.34/pb", plainHttp = false), None)
  }

  test("http, no host, or an address that is not one is refused unless plain http is admitted") {
    assertEquals(Addresses.refusal("http://tracker.example.com/pb", plainHttp = false), Some("not https"))
    assertEquals(Addresses.refusal("https:///pb", plainHttp = false), Some("no host"))
    assertEquals(Addresses.refusal("not an address at all", plainHttp = false).isDefined, true)
    assertEquals(Addresses.refusal("http://localhost:9000/pb", plainHttp = true), None)
    assertEquals(Addresses.refusal("ftp://localhost/pb", plainHttp = true), Some("not an http address"))
  }

  test("the cluster's own addresses are refused: loopback, private, link-local, CGNAT, metadata, benchmarking, reserved, ULA, mapped, NAT64 and 6to4") {
    val refused = Seq(
      "https://localhost/pb", "https://svc.cluster.local/pb", "https://metadata.google.internal/pb", "https://127.0.0.1/pb",
      "https://10.1.2.3/pb", "https://172.16.0.1/pb", "https://192.168.1.1/pb", "https://169.254.169.254/latest/meta-data",
      "https://100.64.0.1/pb", "https://0.0.0.0/pb", "https://[::1]/pb", "https://[fd00::1]/pb", "https://[fe80::1]/pb",
      "https://[::ffff:10.0.0.1]/pb", "https://198.18.0.1/pb", "https://240.0.0.1/pb", "https://[64:ff9b::a00:1]/pb",
      "https://[2002:a00:1::]/pb"
    )
    refused.foreach(u => assert(Addresses.refusal(u, plainHttp = false).isDefined, u))
    // An IPv4 address inside NAT64 or 6to4 is judged as itself: a public one passes.
    Seq("https://[64:ff9b::808:808]/pb", "https://[2002:808:808::]/pb").foreach(u => assertEquals(Addresses.refusal(u, plainHttp = false), None, u))
  }

  test("at send time a host that resolves inside is refused, and plain http checks nothing") {
    assertEquals(Addresses.resolvedRefusal("https://127.0.0.1/pb", plainHttp = false), Some("an internal address"))
    assertEquals(Addresses.resolvedRefusal("https://localhost/pb", plainHttp = false), Some("an internal host"))
    assertEquals(Addresses.resolvedRefusal("http://127.0.0.1:9/pb", plainHttp = true), None)
  }

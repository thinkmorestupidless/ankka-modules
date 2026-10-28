package io.eitheror.casino.attribution.networks

import com.thinkmorestupidless.ankka.testkit.{KeyValueEntityTestKit, LogCapturing}
import java.time.Instant

class NetworkSuite extends munit.FunSuite with LogCapturing:
  private val at   = Instant.parse("2026-10-25T10:00:00Z")
  private val addr = Addresses("https://n.example/keys", "https://n.example/revocations")
  private def kit(id: String = "programme") = KeyValueEntityTestKit.of(Network, id)

  test("add, then a second add of a live network is refused") {
    val k = kit()
    val s = k.call(Network.add)(AddNetwork("programme", addr, "staff-1", at)).replyValue
    assert(s.live)
    assertEquals((s.addedBy, s.roundStream, s.pseudonymKeyMade), (Some("staff-1"), false, false))
    assert(k.call(Network.add)(AddNetwork("programme", addr, "staff-1", at)).isError)
  }

  test("a removed network keeps everything and may be added again") {
    val k = kit()
    k.call(Network.add)(AddNetwork("programme", addr, "staff-1", at)): Unit
    k.call(Network.setRoundStream)(true): Unit
    k.call(Network.setMachine)("org-1/feed"): Unit
    k.call(Network.pseudonymKeyMade)(at): Unit
    val removed = k.call(Network.remove)(RemoveNetwork("staff-2", at.plusSeconds(60))).replyValue
    assertEquals((removed.removed, removed.roundStream, removed.machine, removed.pseudonymKeyMade, removed.addresses),
      (Some(at.plusSeconds(60)), true, Some("org-1/feed"), true, Some(addr)))
    assert(!removed.live)
    val back = k.call(Network.add)(AddNetwork("programme", addr, "staff-1", at.plusSeconds(120))).replyValue
    assert(back.live)
    assert(back.pseudonymKeyMade)
  }

  test("update replaces the addresses; a non-http address is refused naming the field") {
    val k = kit()
    k.call(Network.add)(AddNetwork("programme", addr, "staff-1", at)): Unit
    val more = addr.copy(legacyTagDocument = Some("https://n.example/legacy"))
    assertEquals(k.call(Network.update)(UpdateNetwork(more, "staff-1", at)).replyValue.addresses, Some(more))
    assert(k.call(Network.update)(UpdateNetwork(addr.copy(codeDocument = Some("ftp://x")), "s", at)).errorMessage.startsWith("codeDocument"))
  }

  test("a network id of 65 characters, or with a capital, is refused") {
    assert(kit().call(Network.add)(AddNetwork("a" * 65, addr, "s", at)).isError)
    assert(kit().call(Network.add)(AddNetwork("Programme", addr, "s", at)).isError)
    assert(!kit().call(Network.add)(AddNetwork("a" * 64, addr, "s", at)).isError)
  }

  test("commands on a network that does not exist are refused") {
    assert(kit().call(Network.setRoundStream)(true).isError)
    assert(kit().call(Network.remove)(RemoveNetwork("s", at)).isError)
  }

  test("a machine is <organization>/<name>; the empty text clears it") {
    val k = kit()
    k.call(Network.add)(AddNetwork("programme", addr, "staff-1", at)): Unit
    assert(k.call(Network.setMachine)("nameonly").isError)
    assertEquals(k.call(Network.setMachine)("org/name").replyValue.machine, Some("org/name"))
    assertEquals(k.call(Network.setMachine)("").replyValue.machine, None)
  }

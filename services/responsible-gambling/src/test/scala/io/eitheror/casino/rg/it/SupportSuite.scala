package io.eitheror.casino.rg.it

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.rg.Json
import io.eitheror.casino.rg.profile.{KeptReply, KeyEntry, RgKeys, RgKeysState}

import java.time.Duration
import java.util.UUID
import scala.concurrent.duration.*

/** `support-agent`'s routes (spec 017 amendments R1–R4): admission, each change keyed, and each refusal. */
class SupportSuite extends munit.FunSuite with LogCapturing:
  override val munitTimeout = 3.minutes

  private lazy val kit = SharedKit.kit
  override def beforeEach(context: BeforeEach): Unit = kit.reset()

  private def player()            = s"p-${UUID.randomUUID().toString.take(8)}"
  private def path(rest: String)  = s"/v1/casino/rg-service/${kit.slug}$rest"
  private def agent               = kit.asService("support-agent")
  private def put(p: String, rest: String, body: (String, Any)*) = kit.json("PUT", path(s"/$p$rest"), Json.obj(body*), agent)

  test("R1: support-agent reads the limits as the player route answers them; a player and another service are refused"):
    val p = player()
    kit.json("PUT", kit.playerPath(s"/$p/limits/DEPOSIT_DAILY"), Json.obj("value" -> "5000", "currency" -> "EUR"), kit.asPlayer(p)): Unit
    val mine  = kit.request("GET", kit.playerPath(s"/$p/limits?currency=EUR"), None, kit.asPlayer(p))
    val agents = kit.request("GET", path(s"/$p/limits?currency=EUR"), None, agent)
    assertEquals((agents.status, agents.json), (200, mine.json))
    assertEquals(kit.request("GET", path(s"/$p/limits"), None, kit.asPlayer(p)).status, 403)
    assertEquals(kit.request("GET", path(s"/$p/limits"), None, kit.asService("wallet")).status, 403)

  test("R2: a limit under a key; its repeat answers the first; another value under it is key_reused; no key is key_required"):
    val p     = player()
    val first = put(p, "/limits/DEPOSIT_DAILY", "value" -> "5000", "currency" -> "EUR", "key" -> "ap-1")
    assertEquals(first.status, 200, first.text)
    val again = put(p, "/limits/DEPOSIT_DAILY", "value" -> "5000", "currency" -> "EUR", "key" -> "ap-1")
    assertEquals((again.status, again.text), (200, first.text))
    val other = put(p, "/limits/DEPOSIT_DAILY", "value" -> "9000", "currency" -> "EUR", "key" -> "ap-1")
    assertEquals((other.status, other.str("error")), (409, "key_reused"))
    assertEquals(put(p, "/limits/DEPOSIT_DAILY", "value" -> "5000", "currency" -> "EUR").str("error"), "key_required")
    // a refusal is the outcome too: its repeat is refused the same
    val bad = put(p, "/limits/DEPOSIT_DAILY", "value" -> "12.5", "currency" -> "EUR", "key" -> "ap-2")
    assertEquals((bad.status, bad.str("error")), (400, "invalid_value"))
    assertEquals(put(p, "/limits/DEPOSIT_DAILY", "value" -> "12.5", "currency" -> "EUR", "key" -> "ap-2").text, bad.text)
    assertEquals(kit.request("PUT", path(s"/$p/limits/DEPOSIT_DAILY"), Some("""{"value":"1","key":"k"}"""), kit.asPlayer(p)).status, 403)

  test("R2: the player's rule holds: a looser limit is staged, as on the player route"):
    val p = player()
    put(p, "/limits/DEPOSIT_DAILY", "value" -> "5000", "currency" -> "EUR", "key" -> "ap-1"): Unit
    val looser = put(p, "/limits/DEPOSIT_DAILY", "value" -> "9000", "currency" -> "EUR", "key" -> "ap-2")
    assertEquals(looser.status, 200, looser.text)
    assert(looser.text.contains("9000") && looser.text.toLowerCase.contains("pending"), looser.text)

  test("R3: a cooling-off under a key; its repeat; a second while one is in place is break_active"):
    val p     = player()
    val until = kit.now().plus(Duration.ofDays(2)).toString
    val first = put(p, "/cooling-off", "until" -> until, "key" -> "ap-1")
    assertEquals(first.status, 200, first.text)
    assertEquals(put(p, "/cooling-off", "until" -> until, "key" -> "ap-1").text, first.text)
    val second = put(p, "/cooling-off", "until" -> kit.now().plus(Duration.ofDays(3)).toString, "key" -> "ap-2")
    assertEquals((second.status, second.str("error")), (409, "break_active"))
    assertEquals(put(p, "/cooling-off", "until" -> "soon", "key" -> "ap-3").str("error"), "invalid_until")

  test("R4: a permanent exclusion under a key; its repeat and a second key both answer 200 self_excluded"):
    val p = player()
    val first = put(p, "/permanent-exclusion", "key" -> "ap-1")
    assertEquals((first.status, first.str("status")), (200, "self_excluded"), first.text)
    assertEquals(put(p, "/permanent-exclusion", "key" -> "ap-1").str("status"), "self_excluded")
    val second = put(p, "/permanent-exclusion", "key" -> "ap-2")
    assertEquals((second.status, second.str("status")), (200, "self_excluded"), second.text)
    assertEquals(kit.request("GET", kit.servicePath(s"/$p/may-play"), None, kit.asService("game-provider")).status, 403)

  test("a key older than the last 64 is key_expired, and changes nothing"):
    val p    = player()
    val keys = kit.components.forKeyValueEntity(RgKeys.id(p))
    (0 to RgKeysState.Recent).foreach(i => keys.call(RgKeys.record).invoke(KeyEntry(s"old-$i", "h", KeptReply(200, "{}"))): Unit)
    val r = put(p, "/limits/DEPOSIT_DAILY", "value" -> "5000", "currency" -> "EUR", "key" -> "old-0")
    assertEquals((r.status, r.str("error")), (409, "key_expired"), r.text)
    assert(!kit.request("GET", path(s"/$p/limits?currency=EUR"), None, agent).text.contains("5000"))

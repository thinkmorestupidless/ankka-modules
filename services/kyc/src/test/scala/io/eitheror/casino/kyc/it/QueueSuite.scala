package io.eitheror.casino.kyc.it

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.kyc.Json
import io.eitheror.casino.kyc.Json.*
import io.eitheror.casino.kyc.policy.Profiles
import io.eitheror.casino.kyc.record.*

import java.util.UUID
import scala.jdk.CollectionConverters.*

/**
 * The operator's queue (FR-035, FR-024): each filter lists its players with a count equal to its rows, and
 * a flag leaves the queue on verification or dismissal, a source of funds flag on clearance.
 */
class QueueSuite extends munit.FunSuite with LogCapturing:
  override def munitTimeout = scala.concurrent.duration.Duration(180, "s")

  private lazy val kit = SharedKit.kit

  override def beforeEach(context: BeforeEach): Unit = kit.reset()

  private def newPlayer() = s"p-${UUID.randomUUID().toString.take(8)}"
  private def record(p: String) = kit.components.forEventSourcedEntity(EntityId(p))

  private def queue(query: String): JsonNode =
    val r = kit.request("GET", kit.operatorPath(s"/queue?$query"), None, kit.asOperator())
    assertEquals(r.status, 200, r.text)
    r.json

  private def ids(q: JsonNode): Vector[String] = q.objects("rows").flatMap(_.text("playerId"))

  private def submit(p: String): Unit =
    val o = kit.json("POST", kit.playerPath(s"/$p/uploads"), Json.obj("category" -> "identity_front", "contentType" -> "image/png"), kit.asPlayer(p))
    assertEquals(kit.putSigned(o.str("url"), Array.fill(16)(1.toByte), "image/png") / 100, 2)
    assertEquals(kit.json("POST", kit.playerPath(s"/$p/documents"), Json.obj("documentId" -> o.str("documentId")), kit.asPlayer(p)).status, 200)

  /** A large deposit under MGA: the `large_deposit` flag; over the source of funds threshold, that flag too. */
  private def flag(p: String, amount: Long = 300000): Unit =
    val mga = Profiles.Mga.copy(sofThreshold = Some(250000))
    record(p).call(KycRecord.countDeposit).invoke(CountDeposit(p, "EUR", amount, 1, mga, kit.now())): Unit
    record(p).call(KycRecord.flagForPolicy).invoke(FlagForPolicy(mga, kit.now())): Unit

  private def listedUnder(query: String, p: String, listed: Boolean = true): JsonNode =
    kit.eventually(s"$p ${if listed then "in" else "out of"} '$query'")(Some(queue(query)).filter(q => ids(q).contains(p) == listed))

  test("each filter lists its players, and each count is its filter's rows"):
    val a = newPlayer(); submit(a)
    val b = newPlayer(); flag(b)
    listedUnder("filter=awaiting", a); listedUnder("filter=flagged&reason=large_deposit", b)
    val q = queue("filter=awaiting")
    val counts = q.get("counts")
    for (filter, count) <- Seq("filter=awaiting" -> "awaiting", "filter=pending" -> "pending", "filter=unverified" -> "unverified",
        "filter=verified" -> "verified", "filter=rejected" -> "rejected", "filter=ready" -> "ready",
        "filter=flagged&reason=large_deposit" -> "largeDeposit", "filter=sof" -> "sourceOfFunds",
        "filter=flagged&reason=threshold" -> "threshold", "filter=flagged&reason=window" -> "window")
    do assertEquals(queue(filter).objects("rows").size.toLong, counts.get(count).asLong, s"$filter against $count")
    assert(ids(queue("filter=pending")).contains(a))
    assert(ids(queue("filter=unverified")).contains(b))
    assert(ids(queue("filter=flagged")).contains(b))

  test("a filter lists oldest first unless sort=newest"):
    val first = newPlayer(); submit(first)
    kit.advance(java.time.Duration.ofMinutes(5))
    val second = newPlayer(); submit(second)
    listedUnder("filter=awaiting", second)
    def order(query: String) = ids(queue(query)).filter(Set(first, second))
    assertEquals(order("filter=awaiting"), Vector(first, second))
    assertEquals(order("filter=awaiting&sort=newest"), Vector(second, first))

  test("an unknown filter, or a flag reason that is not one, is invalid_filter"):
    for query <- Seq("filter=everything", "", "filter=flagged&reason=nope", "filter=awaiting&reason=window") do
      val r = kit.request("GET", kit.operatorPath(s"/queue?$query"), None, kit.asOperator())
      assertEquals((r.status, r.str("error")), (400, "invalid_filter"), query)

  test("a flag leaves the queue when the player is verified"):
    val p = newPlayer(); flag(p)
    listedUnder("filter=flagged&reason=large_deposit", p)
    assertEquals(kit.json("POST", kit.operatorPath(s"/$p/override"), Json.obj("status" -> "verified"), kit.asOperator()).status, 200)
    listedUnder("filter=flagged&reason=large_deposit", p, listed = false)
    listedUnder("filter=verified", p)

  test("a flag leaves the queue when dismissed with a note; with no note, or no such flag, it stays"):
    val p = newPlayer(); flag(p)
    listedUnder("filter=flagged&reason=large_deposit", p)
    val path = kit.operatorPath(s"/$p/flags/large_deposit/dismiss")
    val bare = kit.json("POST", path, Json.obj("note" -> "  "), kit.asOperator())
    assertEquals((bare.status, bare.str("error")), (400, "note_required"))
    val none = kit.json("POST", kit.operatorPath(s"/$p/flags/window/dismiss"), Json.obj("note" -> "n"), kit.asOperator())
    assertEquals((none.status, none.str("error")), (404, "flag_not_found"))
    val done = kit.json("POST", path, Json.obj("note" -> "known customer"), kit.asOperator())
    assertEquals(done.status, 200, done.text)
    assert(!done.text.contains("known customer"))
    assert(done.json.get("flags").elements.asScala.exists(f => f.text("reason").contains("large_deposit") && !f.get("open").asBoolean))
    listedUnder("filter=flagged&reason=large_deposit", p, listed = false)

  test("a source of funds flag leaves the queue on clearance, and the review can be set back"):
    val p = newPlayer(); flag(p)
    listedUnder("filter=sof", p)
    val cleared = kit.json("POST", kit.operatorPath(s"/$p/sof"), Json.obj("status" -> "cleared"), kit.asOperator())
    assertEquals((cleared.status, cleared.str("sof")), (200, "cleared"))
    listedUnder("filter=sof", p, listed = false)
    assert(ids(queue("filter=flagged&reason=large_deposit")).contains(p))
    assertEquals(kit.json("POST", kit.operatorPath(s"/$p/sof"), Json.obj("status" -> "unverified"), kit.asOperator()).str("sof"), "unverified")
    val bad = kit.json("POST", kit.operatorPath(s"/$p/sof"), Json.obj("status" -> "maybe"), kit.asOperator())
    assertEquals((bad.status, bad.str("error")), (400, "invalid_status"))

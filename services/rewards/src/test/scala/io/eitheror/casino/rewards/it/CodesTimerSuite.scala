package io.eitheror.casino.rewards.it

import com.fasterxml.jackson.databind.JsonNode
import io.eitheror.casino.rewards.Json.*

import java.time.Instant

/**
 * `codes-<slug>` against `FakeProgramme` (research R13; FR-021) over the whole service: the document is fetched on the
 * refresh bound (2 s in the kit) and served by `GET /offers/code-document`; a failure keeps the last copy, reported
 * stale once the staleness bound (6 s in the kit) has passed since its fetch.
 */
class CodesTimerSuite extends munit.FunSuite:
  private lazy val kit = SharedKit.kit
  override def munitTimeout = scala.concurrent.duration.Duration(120, "s")
  override def beforeEach(context: BeforeEach): Unit = kit.reset()

  private def read: JsonNode =
    val r = kit.request("GET", kit.offersPath("/code-document"), None, kit.asOperator())
    assertEquals(r.status, 200, r.text)
    r.json

  private def entries(d: JsonNode): Vector[(String, String, String)] =
    d.objects("entries").map(e => (e.textOr("code", ""), e.textOr("offerId", ""), e.textOr("tag", "")))

  test("the document is fetched every refresh, served with its time and not stale; a new publication replaces it"):
    val v = System.nanoTime()
    kit.programme.documentFor(kit.slug, v, Instant.now(), ("TIMER1", "o-1", "tag-1"))
    val first = kit.eventually("fetched")(Some(read).filter(d => entries(d).contains(("timer1", "o-1", "tag-1"))))
    assertEquals(first.bool("stale"), Some(false))
    assert(first.text("fetchedAt").isDefined)
    kit.programme.documentFor(kit.slug, v + 1, Instant.now(), ("TIMER2", "o-2", "tag-2"))
    val second = kit.eventually("refreshed")(Some(read).filter(d => entries(d) == Vector(("timer2", "o-2", "tag-2"))))
    assert(second.textOr("fetchedAt", "") > first.textOr("fetchedAt", ""))
    assert(kit.programme.fetches(kit.slug).size >= 2)

  test("unreachable, the programme's last copy serves, reported stale past the bound"):
    kit.programme.documentFor(kit.slug, System.nanoTime(), Instant.now(), ("KEPT", "o-k", "tag-k"))
    kit.eventually("fetched")(Some(read).filter(d => entries(d).contains(("kept", "o-k", "tag-k")))): Unit
    kit.programme.answerFor(kit.slug, Answer(503, """{"error":"down"}"""))
    val fetchedBefore = kit.programme.fetches(kit.slug).size
    kit.eventually("tried again")(Option.when(kit.programme.fetches(kit.slug).size > fetchedBefore + 1)(())): Unit
    assertEquals(entries(read), Vector(("kept", "o-k", "tag-k")))
    val stale = kit.eventually("stale", within = scala.concurrent.duration.Duration(30, "s"))(Some(read).filter(_.bool("stale").contains(true)))
    assertEquals(entries(stale), Vector(("kept", "o-k", "tag-k")))

  test("a document of another brand is not kept"):
    kit.programme.documentFor(kit.slug, System.nanoTime(), Instant.now(), ("MINE", "o-m", "tag-m"))
    kit.eventually("fetched")(Some(read).filter(d => entries(d).contains(("mine", "o-m", "tag-m")))): Unit
    kit.programme.answerFor(kit.slug, Answer.ok(io.eitheror.casino.rewards.it.FakeProgramme.document("otherbrand", 9L, Instant.now(),
      ("THEIRS", "o-t", "tag-t"))))
    val before = kit.programme.fetches(kit.slug).size
    kit.eventually("tried again")(Option.when(kit.programme.fetches(kit.slug).size > before + 1)(())): Unit
    assertEquals(entries(read), Vector(("mine", "o-m", "tag-m")))

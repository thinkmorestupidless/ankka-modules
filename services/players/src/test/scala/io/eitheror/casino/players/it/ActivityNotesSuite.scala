package io.eitheror.casino.players.it

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.players.reads.{ActivityEntry, ActivityRow, ActivityView}

import java.time.Instant
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** Spec 030 P3, P4: a player's activity for a period, by cursor, with its total; the notes listed and paged. */
class ActivityNotesSuite extends munit.FunSuite with LogCapturing:
  override val munitTimeout = 2.minutes
  private lazy val kit = SharedKit.kit

  override def beforeEach(context: BeforeEach): Unit = kit.reset()

  private def op(method: String, path: String, body: Option[String] = None) =
    kit.request(method, kit.operatorPath(path), body, kit.asOperator())

  private def line(playerId: String, i: Int, at: Instant, category: String = "account"): Unit =
    val id = s"suite/$playerId/$i"
    kit.components.forKeyValueEntity(EntityId(id)).call(ActivityEntry.record)
      .invoke(ActivityRow(playerId, at.toEpochMilli, category, "profile_updated", "player", description = s"line $i", factId = id)): Unit

  test("from after to is refused; a date's to includes the day; an instant's to is excluded; a bad cursor is none; the category counts") {
    val id = kit.activePlayer()._2
    line(id, 0, Instant.parse("2026-05-10T00:00:00Z"))
    line(id, 1, Instant.parse("2026-05-10T23:59:59Z"))
    line(id, 2, Instant.parse("2026-05-11T00:00:00Z"), "games")
    kit.eventually("the lines", 30.seconds)(Option.when(op("GET", s"/players/$id/activity").json.get("total").asInt == 3)(()))
    val refused = op("GET", s"/players/$id/activity?from=2026-05-12&to=2026-05-11")
    assertEquals((refused.status, refused.str("error")), (400, "invalid_date_range"))
    def total(q: String) = op("GET", s"/players/$id/activity?$q").json.get("total").asInt
    assertEquals(total("from=2026-05-10&to=2026-05-10"), 2)
    assertEquals(total("from=2026-05-10T00:00:00Z&to=2026-05-10T23:59:59Z"), 1)
    assertEquals(total("from=nonsense"), 3)
    assertEquals(total("category=games"), 1)
    assertEquals(op("GET", s"/players/$id/activity?after=notacursor").json.get("events").size, 3)
    val r = op("GET", s"/players/$id/activity")
    assert(r.json.get("next").isNull && !r.json.get("hasMore").asBoolean, r.text)
    // the cursor is the line's place, its time and key
    val first = ActivityView.inPeriod(kit.views, ActivityView.Period(id), 0, 1).head
    assertEquals(ActivityView.place(ActivityView.cursor(first)), Some(first.at -> first.factId))
  }

  test("the notes: pinned first then newest, all with neither page nor pageSize, paged with them; 404 for no player") {
    val id = kit.activePlayer()._2
    val ids = (1 to 3).map(i => op("POST", s"/players/$id/notes", Some(s"""{"content":"note $i"}""")).str("noteId"))
    assertEquals(op("PATCH", s"/players/$id/notes/${ids.head}/pin").status, 200)
    val all = kit.eventually("the notes", 30.seconds) {
      val r = op("GET", s"/players/$id/notes")
      Option.when(r.json.get("total").asInt == 3 && r.json.get("notes").get(0).get("pinned").asBoolean)(r)
    }
    assert(!all.json.has("page"))
    assertEquals(all.json.get("notes").elements().asScala.map(_.get("noteId").asText).toVector, Vector(ids(0), ids(2), ids(1)))
    val paged = op("GET", s"/players/$id/notes?page=2&pageSize=2")
    assertEquals((paged.json.get("page").asInt, paged.json.get("pageSize").asInt, paged.json.get("notes").size, paged.json.get("total").asInt), (2, 2, 1, 3))
    assertEquals(op("GET", "/players/nobody/notes").status, 404)
  }

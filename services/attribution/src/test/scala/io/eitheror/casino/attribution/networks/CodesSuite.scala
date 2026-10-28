package io.eitheror.casino.attribution.networks

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.attribution.Json

import java.time.Instant

/** `Codes.resolve` over the cached code documents (research R6, R7). */
class CodesSuite extends munit.FunSuite with LogCapturing:
  private val t0 = Instant.parse("2026-10-25T10:00:00Z")

  private def network(id: String, addedSeconds: Long, removed: Boolean = false) =
    NetworkState(id, Some(Addresses(s"https://$id/keys", s"https://$id/revocations", None, Some(s"https://$id/codes"))),
      addedAt = Some(t0.plusSeconds(addedSeconds)), removed = Option.when(removed)(t0))

  private def codes(entries: (String, String)*)(expires: Instant = t0.plusSeconds(60)) =
    val body = Json.render(Json.obj("brand" -> "goldvibe", "version" -> 1, "cacheSeconds" -> 60,
      "codes" -> Json.arr(entries.map((c, t) => Json.obj("code" -> c, "offer" -> "o", "tag" -> t))*)))
    DocumentsState(Map(Documents.Kind.Codes -> CachedDocument(Some(t0), Some(expires), Some(body))))

  test("found in the second network, case-insensitively") {
    val got = Codes.resolve("summer50", Vector(network("a", 0) -> codes("WINTER10" -> "tag-a")(), network("b", 1) -> codes("SUMMER50" -> "tag-b")()), t0)
    assertEquals(got, Codes.Found("b", "tag-b"))
  }

  test("two networks listing one code: the first added wins") {
    val got = Codes.resolve("SUMMER50", Vector(network("late", 10) -> codes("summer50" -> "tag-late")(),
      network("early", 1) -> codes("Summer50" -> "tag-early")()), t0)
    assertEquals(got, Codes.Found("early", "tag-early"))
  }

  test("a code no fresh document lists is unknown; a stale document is not read; with none fresh, unresolved") {
    assertEquals(Codes.resolve("nope", Vector(network("a", 0) -> codes("summer50" -> "t")()), t0), Codes.Unknown)
    val stale = Vector(network("a", 0) -> codes("summer50" -> "t")(t0.minusSeconds(1)))
    assertEquals(Codes.resolve("summer50", stale, t0), Codes.Unresolved)
    assertEquals(Codes.resolve("summer50", stale :+ (network("b", 1) -> codes("other" -> "t")()), t0), Codes.Unknown)
  }

  test("a removed network's codes are not read") {
    assertEquals(Codes.resolve("summer50", Vector(network("gone", 0, removed = true) -> codes("summer50" -> "t")()), t0), Codes.Unresolved)
  }

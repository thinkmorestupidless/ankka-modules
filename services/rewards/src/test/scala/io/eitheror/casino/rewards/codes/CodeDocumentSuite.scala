package io.eitheror.casino.rewards.codes

import com.thinkmorestupidless.ankka.testkit.KeyValueEntityTestKit
import io.eitheror.casino.rewards.clients.{ProgrammeCode, ProgrammeDocument}
import io.eitheror.casino.rewards.offers.ProgrammeEntry

import java.time.{Duration, Instant}

/** data-model.md's `CodeDocument` (research R13): the copy kept, its codes normalised, and when it is stale (FR-021). */
class CodeDocumentSuite extends munit.FunSuite:
  private val at = Instant.parse("2026-11-01T12:00:00Z")
  private val doc = ProgrammeDocument("goldvibe", 7L, Some(at), Vector(
    ProgrammeCode(" AFF100 ", "o-1", "tag-1"), ProgrammeCode("aff100", "o-2", "tag-2"), ProgrammeCode("Spring", "o-3", ""),
    ProgrammeCode("  ", "o-4", "tag-4")))

  test("the document is kept by its codes trimmed and in lower case, the first of a code twice, an empty code left out"):
    val s = CodeDocumentState.of(doc, at)
    assertEquals(s.entries, Map("aff100" -> ProgrammeEntry("o-1", Some("tag-1")), "spring" -> ProgrammeEntry("o-3", None)))
    assertEquals((s.fetchedAt, s.version, s.codes), (Some(at), 7L, Set("aff100", "spring")))

  test("stale past the bound from its fetch, and when never fetched"):
    val s = CodeDocumentState.of(doc, at)
    val bound = Duration.ofMinutes(15)
    assert(!s.stale(at.plus(bound), bound))
    assert(s.stale(at.plus(bound).plusSeconds(1), bound))
    assert(CodeDocumentState().stale(at, bound))

  test("a fetch replaces the copy whole"):
    val kit = KeyValueEntityTestKit.of(CodeDocument, "goldvibe")
    kit.call(CodeDocument.fetched)(Fetched(doc, at)): Unit
    val next = ProgrammeDocument("goldvibe", 8L, None, Vector(ProgrammeCode("NEW", "o-9", "t")))
    val r = kit.call(CodeDocument.fetched)(Fetched(next, at.plusSeconds(300)))
    assertEquals((r.state.codes, r.state.version, r.state.fetchedAt), (Set("new"), 8L, Some(at.plusSeconds(300))))
    assertEquals(kit.call(CodeDocument.get).replyValue, r.state)

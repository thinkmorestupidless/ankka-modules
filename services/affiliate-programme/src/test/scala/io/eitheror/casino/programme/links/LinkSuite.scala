package io.eitheror.casino.programme.links

import com.thinkmorestupidless.ankka.testkit.{KeyValueEntityTestKit, LogCapturing}

import java.time.Instant

class LinkSuite extends munit.FunSuite with LogCapturing:
  private val at  = Instant.parse("2026-11-01T10:00:00Z")
  private val cmd = CreateLink("lnk-1", "aff-1", "goldvibe", Some("summer"), Some("/slots"), Map("utm_source" -> "x"), Vector("s1"),
    Some("tag"), Some("2f1c…"), "aff-1", at)

  test("made once, revoked once") {
    val k = KeyValueEntityTestKit.of(Link, "lnk-1")
    assert(k.call(Link.createLink)(cmd).replyValue.live)
    assert(!k.call(Link.createLink)(cmd.copy(campaign = None)).changed)
    assert(!k.call(Link.revoke)(at).replyValue.live)
    assert(!k.call(Link.revoke)(at.plusSeconds(1)).changed)
    assertEquals(KeyValueEntityTestKit.of(Link, "lnk-2").call(Link.revoke)(at).errorMessage, "link_not_found")
  }

  test("a new id fits the tag's grammar") {
    assert(Link.newId().matches("lnk-[a-z2-7]{16}"))
  }

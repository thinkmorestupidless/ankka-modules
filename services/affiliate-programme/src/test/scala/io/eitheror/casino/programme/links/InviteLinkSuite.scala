package io.eitheror.casino.programme.links

import com.thinkmorestupidless.ankka.testkit.{KeyValueEntityTestKit, LogCapturing}

import java.time.Instant

class InviteLinkSuite extends munit.FunSuite with LogCapturing:
  private val at  = Instant.parse("2026-11-01T10:15:00Z")
  private val cmd = CreateInviteLink("tok-1", "inv-1", "aff-1", "goldvibe", at)

  test("made once; revoked, it takes no application") {
    val k = KeyValueEntityTestKit.of(InviteLink, "tok-1")
    assert(k.call(InviteLink.createLink)(cmd).replyValue.live)
    assert(!k.call(InviteLink.createLink)(cmd.copy(affiliate = "aff-2")).changed)
    assert(!k.call(InviteLink.revoke)(at).replyValue.live)
    assertEquals(k.call(InviteLink.use)(at).errorMessage, InviteLink.NotFound)
    assertEquals(KeyValueEntityTestKit.of(InviteLink, "tok-2").call(InviteLink.use)(at).errorMessage, InviteLink.NotFound)
  }

  test("50 applications an hour; the 51st refused; the next hour starts again") {
    val k = KeyValueEntityTestKit.of(InviteLink, "tok-1")
    k.call(InviteLink.createLink)(cmd): Unit
    (1 to 50).foreach(i => assertEquals(k.call(InviteLink.use)(at.plusSeconds(i)).replyValue.used, i))
    assert(k.call(InviteLink.use)(at.plusSeconds(60)).errorMessage.startsWith(InviteLink.TooMany))
    assertEquals(k.call(InviteLink.use)(Instant.parse("2026-11-01T11:00:00Z")).replyValue.used, 1)
  }

  test("a token is 32 hex characters") {
    assert(InviteLink.mint().matches("[0-9a-f]{32}"))
  }

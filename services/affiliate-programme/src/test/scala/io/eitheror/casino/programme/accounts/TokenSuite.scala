package io.eitheror.casino.programme.accounts

import com.thinkmorestupidless.ankka.testkit.{KeyValueEntityTestKit, LogCapturing}

import java.time.{Duration, Instant}

class TokenSuite extends munit.FunSuite with LogCapturing:
  private val at = Instant.parse("2026-11-01T10:00:00Z")
  private def kit(token: String) = KeyValueEntityTestKit.of(Token, Token.idOf(token).toString)

  test("the id is the token's digest, never the token") {
    val t = Token.mint()
    assert(!Token.idOf(t).toString.contains(t))
    assertEquals(Token.idOf(t).toString.length, 64)
  }

  test("claimed once while it lasts") {
    val k = kit("t-1")
    k.call(Token.issue)(IssueToken("aff-1", Token.Invite, Some("goldvibe"), at.plus(Token.life(Token.Invite)))): Unit
    assertEquals(k.call(Token.claim)(at.plusSeconds(60)).replyValue.user, "aff-1")
    assertEquals(k.call(Token.claim)(at.plusSeconds(61)).errorMessage, Token.Invalid)
  }

  test("an expired or unknown token is invalid") {
    val k = kit("t-2")
    k.call(Token.issue)(IssueToken("aff-1", Token.Reset, None, at.plus(Token.life(Token.Reset)))): Unit
    assertEquals(k.call(Token.claim)(at.plus(Duration.ofMinutes(30))).errorMessage, Token.Invalid)
    assertEquals(kit("t-3").call(Token.claim)(at).errorMessage, Token.Invalid)
  }

package io.eitheror.casino.players.realm

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.players.realm.Tokens.{Claims, Purpose, Refused}

import java.time.Instant

/** FR-005: the signed link, verified with nothing stored, in the original's form. */
class TokensSuite extends munit.FunSuite with LogCapturing:

  private val secret = "s3cret"
  private val now    = Instant.parse("2026-10-25T10:00:00Z")
  private val claims = Claims("goldvibe", "p-1", "ada@example.test", now.plusSeconds(86400), Purpose.EmailVerify)

  test("a minted token verifies to its claims") {
    assertEquals(Tokens.verify(secret, Tokens.mint(secret, claims), Purpose.EmailVerify, now), Right(claims))
  }

  test("a token with a byte changed is invalid") {
    val token = Tokens.mint(secret, claims)
    val i     = token.indexOf('.') - 3
    val bad   = token.updated(i, if token(i) == 'A' then 'B' else 'A')
    assertEquals(Tokens.verify(secret, bad, Purpose.EmailVerify, now), Left(Refused.Invalid))
    assertEquals(Tokens.verify("other", token, Purpose.EmailVerify, now), Left(Refused.Invalid))
    assertEquals(Tokens.verify(secret, "not-a-token", Purpose.EmailVerify, now), Left(Refused.Invalid))
  }

  test("a token 24 hours and one second old is expired") {
    val token = Tokens.mint(secret, claims)
    assertEquals(Tokens.verify(secret, token, Purpose.EmailVerify, now.plusSeconds(86401)), Left(Refused.Expired))
  }

  test("a token of one purpose is refused for another") {
    val token = Tokens.mint(secret, claims)
    Seq(Purpose.EmailAdd, Purpose.EmailChange, Purpose.PasswordChange).foreach { p =>
      assertEquals(Tokens.verify(secret, token, p, now), Left(Refused.Invalid), p.toString)
    }
  }

  test("a known payload under a known key gives the original's payload.signature form") {
    // The players driver's vector (tests/original/players-driver), computed with openssl.
    val known = Claims("spinvibe", "p-1", "a@example.test", Instant.ofEpochMilli(1700000000000L), Purpose.EmailVerify)
    assertEquals(Tokens.mint(secret, known),
      "eyJiIjoic3BpbnZpYmUiLCJwIjoicC0xIiwiZSI6ImFAZXhhbXBsZS50ZXN0IiwieCI6MTcwMDAwMDAwMDAwMCwidCI6ImVtYWlsX3ZlcmlmeSJ9" +
        ".N6l66h-TkE1GyIJl8rYcauwEy02ZhtxHPXITlV3hLEE")
  }

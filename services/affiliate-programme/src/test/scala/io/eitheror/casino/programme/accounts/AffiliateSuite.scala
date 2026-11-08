package io.eitheror.casino.programme.accounts

import com.thinkmorestupidless.ankka.testkit.{EventSourcedTestKit, LogCapturing}
import io.eitheror.casino.programme.sealing.{Identity, Sealed, Sealing}

import java.time.Instant

class AffiliateSuite extends munit.FunSuite with LogCapturing:
  private val at       = Instant.parse("2026-11-01T10:00:00Z")
  private val key      = Sealing.newKey()
  private val identity = Identity("Ada Lovelace", None, "ada@example.com", None, None, None, None)
  private val sealedId = Sealing.seal(identity, key, "aff-1")
  private def kit      = EventSourcedTestKit.of(Affiliate, "aff-1")
  private def make(parent: Option[String] = None) =
    CreateAffiliate("aff-1", sealedId, "digest-1", parent, 500, AccountFields(minimumPayout = Some(Map("EUR" -> 10_000L))), "m-1", at)

  test("create stores the identity only sealed, once; an import run again changes nothing") {
    val k = kit
    val r = k.call(Affiliate.createAccount)(make())
    val s = r.replyValue
    assertEquals((s.id, s.emailDigest, s.overrideBp, s.minimumIn("EUR"), s.minimumIn("USD")), ("aff-1", "digest-1", 500, 10_000L, 0L))
    assert(!s.identity.ciphertext.contains("ada@example.com"))
    assertEquals(Sealing.open(s.identity, key, "aff-1"), Right(identity))
    assert(!k.call(Affiliate.createAccount)(make()).persisted)
  }

  test("an account that is its own parent, or with an override over 100%, is refused") {
    assertEquals(kit.call(Affiliate.createAccount)(make(Some("aff-1"))).errorMessage, "parent_cycle")
    assert(kit.call(Affiliate.createAccount)(make().copy(overrideBp = 10_001)).isError)
  }

  test("update changes only the fields it names; the same values persist nothing") {
    val k = kit
    k.call(Affiliate.createAccount)(make()): Unit
    val s = k.call(Affiliate.update)(UpdateAccount(AccountFields(postbackUrl = Some("https://t.example/pb")), "m-1", at)).replyValue
    assertEquals((s.postbackUrl, s.minimumIn("EUR")), (Some("https://t.example/pb"), 10_000L))
    assert(!k.call(Affiliate.update)(UpdateAccount(AccountFields(postbackUrl = Some("https://t.example/pb")), "m-1", at)).persisted)
  }

  test("identity, parent and override are replaced") {
    val k = kit
    k.call(Affiliate.createAccount)(make()): Unit
    val other = Sealing.seal(identity.copy(name = "Ada King"), key, "aff-1")
    assertEquals(k.call(Affiliate.updateIdentity)(UpdateIdentity(other, "digest-2", "aff-1", at)).replyValue.emailDigest, "digest-2")
    assertEquals(k.call(Affiliate.setParent)(SetParent(Some("aff-0"), "m-1", at)).replyValue.parent, Some("aff-0"))
    assertEquals(k.call(Affiliate.setParent)(SetParent(Some("aff-1"), "m-1", at)).errorMessage, "parent_cycle")
    assertEquals(k.call(Affiliate.setOverride)(SetOverride(1_000, "m-1", at)).replyValue.overrideBp, 1_000)
    assertEquals(k.call(Affiliate.setOverride)(SetOverride(-1, "m-1", at)).errorMessage, Affiliate.OverrideRange)
  }

  test("login keeps the first sign-in; a closed account signs in no more") {
    val k = kit
    k.call(Affiliate.createAccount)(make()): Unit
    k.call(Affiliate.login)(Login(first = true, at)): Unit
    val s = k.call(Affiliate.login)(Login(first = true, at.plusSeconds(60))).replyValue
    assertEquals((s.firstLoginAt, s.lastLoginAt), (Some(at), Some(at.plusSeconds(60))))
    k.call(Affiliate.close)(CloseAccount("m-1", at)): Unit
    assert(!k.call(Affiliate.close)(CloseAccount("m-1", at.plusSeconds(1))).persisted)
    assertEquals(k.call(Affiliate.login)(Login(first = false, at)).errorMessage, "account_closed")
  }

  test("erased replaces the identity and digest once, and nothing changes after") {
    val k = kit
    k.call(Affiliate.createAccount)(make()): Unit
    val s = k.call(Affiliate.erased)(at).replyValue
    assertEquals((s.identity, s.emailDigest, s.erased), (Sealed.erased, "", Some(at)))
    assert(!k.call(Affiliate.erased)(at.plusSeconds(1)).persisted)
    assertEquals(k.call(Affiliate.update)(UpdateAccount(AccountFields(postbackUrl = Some("https://g.example/pb")), "m-1", at)).errorMessage, "erased")
  }

  test("an account that is not there is not changed") {
    assertEquals(kit.call(Affiliate.setOverride)(SetOverride(1, "m", at)).errorMessage, "account_not_found")
  }

  test("a new id is aff- and 16 base32 characters") {
    assert(Affiliate.newId().matches("aff-[a-z2-7]{16}"))
  }

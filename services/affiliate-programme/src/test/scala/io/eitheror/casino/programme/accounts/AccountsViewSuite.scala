package io.eitheror.casino.programme.accounts

import io.eitheror.casino.programme.sealing.Sealed

import java.time.Instant

class AccountsViewSuite extends munit.FunSuite:
  private val at = Instant.parse("2026-11-01T10:00:00Z")

  test("a row is the account as its events make it, the identity sealed") {
    val created = Affiliate.Created(Sealed("c", "n"), "d", Some("aff-0"), 500, AccountFields(), "m", at)
    val row     = Vector(created, Affiliate.OverrideSet(700, "m", at), Affiliate.Erased(at)).foldLeft(AffiliateState())(Affiliate.next("aff-1", _, _))
    assertEquals((row.id, row.parent, row.overrideBp, row.identity, row.erased), ("aff-1", Some("aff-0"), 700, Sealed.erased, Some(at)))
  }

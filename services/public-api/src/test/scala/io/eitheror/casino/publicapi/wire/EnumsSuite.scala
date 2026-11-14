package io.eitheror.casino.publicapi.wire

import io.eitheror.casino.publicapi.contract.Contract

import scala.jdk.CollectionConverters.*

/** Original `test/enums.test.ts`, over the words the facts here carry (contracts/fields.md §2). */
class EnumsSuite extends munit.FunSuite:

  private val schemas = Contract.file().path("components").path("schemas")

  /** A property's enum, through its `$ref` where it has one; `null` is left out. */
  private def contractEnum(schema: String, property: String): Set[String] =
    val prop = schemas.path(schema).path("properties").path(property)
    val node = Option(prop.path("$ref").asText(null)).fold(prop)(r => schemas.path(r.stripPrefix("#/components/schemas/")))
    node.path("enum").elements().asScala.filterNot(_.isNull).map(_.asText()).toSet

  private val maps = Vector(
    ("Player", "status", Enums.PlayerStatus), ("Player", "kycStatus", Enums.KycStatus), ("Session", "status", Enums.SessionStatus),
    ("Transaction", "type", Enums.TransactionType), ("Transaction", "walletType", Enums.WalletType),
    ("Bet", "fundedFrom", Enums.WalletType), ("BonusAward", "status", Enums.BonusStatus))

  test("every mapped value is in the contract's enum") {
    maps.foreach { (schema, property, map) =>
      val allowed = contractEnum(schema, property)
      assert(allowed.nonEmpty, s"$schema.$property has no enum in the contract")
      map.values.foreach(v => assert(allowed(v), s"$v is not in $schema.$property"))
    }
  }

  test("a word with no published equivalent is None, never coerced, and lookups are exact") {
    assertEquals(Enums.playerStatus("blocked"), None)
    assertEquals(Enums.playerStatus("pending_email_verification"), None)
    assertEquals(Enums.playerStatus("ACTIVE"), None)
    assertEquals(Enums.walletType(""), None)
    assertEquals(Enums.bonusStatus("AVAILABLE"), None)
    assertEquals(Enums.bonusStatus("cancelled"), None)
    assertEquals(Enums.bonusStatus("CANCELLED"), Some("CANCELLED"))
  }

  test("each money family's type, as contracts/fields.md §2's table has it") {
    val expected = Map(
      "deposit_confirmed" -> "DEPOSIT", "bet_placed" -> "BET", "win_credited" -> "WIN", "bet_rolled_back" -> "ROLLBACK",
      "round_settled_as_loss" -> "LOSS", "restricted_funds_awarded" -> "BONUS", "restricted_funds_converted" -> "BONUS",
      "restricted_funds_forfeited" -> "BONUS", "admin_adjusted" -> "ADMIN_ADJUSTMENT", "hold_placed" -> "WITHDRAWAL",
      "hold_released" -> "WITHDRAWAL", "withdrawal_reversed" -> "WITHDRAWAL", "provider_credited" -> "GAME_PROVIDER_CREDIT")
    expected.foreach((family, t) => assertEquals(Enums.typeWordOf(family).flatMap(Enums.transactionType), Some(t), family))
    assertEquals(Enums.typeWordOf("restricted_funds_forfeited", Some("cancel")), Some("bonus_cancel"))
    assertEquals(Enums.typeWordOf("restricted_funds_forfeited", Some("loss")), Some("bonus_credit"))
  }

  test("families that are not the casino's movements are not transactions") {
    Vector("restricted_funds_closed", "reservation_placed", "settlement_debit", "account_debit", "account_adjusted")
      .foreach(f => assertEquals(Enums.typeWordOf(f), None, f))
  }

  test("the wallet's sides are MAIN and BONUS") {
    assertEquals(Enums.side("real"), Some("MAIN"))
    assertEquals(Enums.side("restricted"), Some("BONUS"))
    assertEquals(Enums.side("other"), None)
  }

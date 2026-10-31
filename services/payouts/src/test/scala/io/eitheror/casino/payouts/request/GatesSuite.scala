package io.eitheror.casino.payouts.request

import io.eitheror.casino.contract.money.Currency
import io.eitheror.casino.contract.personal.Personal
import io.eitheror.casino.payouts.limits.{Counted, Pair}
import io.eitheror.casino.payouts.player.Kyc
import io.eitheror.casino.payouts.terminals.Terminal

import java.time.Instant

/** Research R4's table, row by row in its order: each refusal's code, status and fields; the earlier wins. */
class GatesSuite extends munit.FunSuite:
  private val now = Instant.parse("2026-10-15T12:00:00Z")
  private val Eur = Currency.unsafe("EUR")
  private val bank    = Terminal("bank", "sb-1", "Bank", "EUBANKING", "unityfinance", true, true, true, hasStatus = true)
  private val crypto  = Terminal("coin", "sb-1", "Crypto", "USDT", "COZENI", true, true, false, coins = Vector("USDT", "BTC"))
  private val interac = Terminal("interac", "sb-1", "Interac", "INTERAC", "gigadat", true, true, false)
  private val visa    = Terminal("visa", "sb-1", "Visa", "CARD_VISA", "unityfinance", true, true, false)
  private val holder  = Holder(Personal("Ana"), Personal("Silva"))
  private val standing = Standing(Kyc.Verified, destination = {
    case "d-1" => Some((holder, Personal("DE89370400440532013000")))
    case _     => None
  })
  private val draft = Draft(Some("k-1"), Amount.Whole(5000L), Some("EUR"), Some("bank"), destinationId = Some("d-1"))

  private var previewed = 0
  private def run(
      d: Draft = draft,
      st: Option[Standing] = Some(standing),
      limits: Pair = Pair(),
      preview: Either[Refusal, Preview] = Right(Preview())
  ) =
    previewed = 0
    Gates.check(Inputs(d, st, Vector(bank, crypto, interac, visa), _ => limits, now, (_, _) => { previewed += 1; preview }))
  private def code(r: Either[Refusal, Passed]) = r.left.map(x => (x.status, x.code))

  test("a request every check passes carries the destination's holder and IBAN"):
    val passed = run().toOption.get
    assertEquals((passed.key, passed.amount, passed.currency, passed.terminal.id), ("k-1", 5000L, Eur, "bank"))
    assertEquals(passed.payee.holder, Some(holder))
    assertEquals(passed.payee.iban.map(Personal.reveal), Some("DE89370400440532013000"))

  test("no key is 400 MISSING_KEY, before the amount"):
    assertEquals(code(run(draft.copy(key = None, amount = Amount.NotWhole))), Left((400, "MISSING_KEY")))

  test("2. zero, a negative or a decimal amount is 422 INVALID_AMOUNT"):
    for a <- Vector(Amount.Whole(0L), Amount.Whole(-1L), Amount.NotWhole) do
      assertEquals(code(run(draft.copy(amount = a))), Left((422, "INVALID_AMOUNT")), a)

  test("2. an invalid amount comes before a missing currency or terminal"):
    assertEquals(code(run(draft.copy(amount = Amount.Whole(0L), currency = None, terminalId = None))), Left((422, "INVALID_AMOUNT")))

  test("2a. amount, currency and terminal present, each refusal naming its field"):
    def field(r: Either[Refusal, Passed]) = r.left.toOption.flatMap(_.fields.collectFirst { case ("field", Field.Text(f)) => f })
    assertEquals(field(run(draft.copy(amount = Amount.Absent))), Some("amount"))
    assertEquals(field(run(draft.copy(currency = None))), Some("currency"))
    assertEquals(field(run(draft.copy(currency = Some("euro")))), Some("currency"))
    assertEquals(field(run(draft.copy(terminalId = None))), Some("terminalId"))
    assertEquals(code(run(draft.copy(terminalId = None))), Left((400, "INVALID_REQUEST")))

  test("2a. a Gigadat terminal needs payoutMethod, then mobile for ETO or the three bank numbers for ACH"):
    val g = draft.copy(terminalId = Some("interac"), destinationId = None)
    def field(d: Draft) = run(d).left.toOption.flatMap(_.fields.collectFirst { case ("field", Field.Text(f)) => f })
    assertEquals(field(g), Some("payoutMethod"))
    assertEquals(field(g.copy(payoutMethod = Some("cheque"))), Some("payoutMethod"))
    assertEquals(field(g.copy(payoutMethod = Some("ETO"))), Some("mobile"))
    assertEquals(field(g.copy(payoutMethod = Some("ach"))), Some("bankAccount"))
    assertEquals(field(g.copy(payoutMethod = Some("ACH"), bankAccount = Some("1"))), Some("institutionCode"))
    assertEquals(field(g.copy(payoutMethod = Some("ACH"), bankAccount = Some("1"), institutionCode = Some("2"))), Some("transitNumber"))
    val eto = run(g.copy(payoutMethod = Some("eto"), mobile = Some("+15551234567"))).toOption.get.payee
    assertEquals((eto.method, eto.mobile.map(Personal.reveal), eto.account), (Some(Method.Eto), Some("+15551234567"), None))

  test("2a. comes before the player is known"):
    assertEquals(code(run(draft.copy(terminalId = Some("interac")), st = None)), Left((400, "INVALID_REQUEST")))

  test("3. a player not known is 404 PLAYER_NOT_FOUND, and the wallet is not asked"):
    assertEquals(code(run(st = None)), Left((404, "PLAYER_NOT_FOUND")))
    assertEquals(previewed, 0)

  test("4. a player not verified is 402 WITHDRAWAL_BLOCKED_KYC with the status and the original's message"):
    val rows = Vector(
      Kyc.Unverified -> "Please verify your identity before withdrawing",
      Kyc.Pending    -> "Your identity documents are under review",
      Kyc.Rejected   -> "Your identity documents were not accepted — please re-submit",
      Kyc.Submitted  -> "KYC verification required"
    )
    for (kyc, message) <- rows do
      val r = run(st = Some(standing.copy(kyc = kyc))).left.toOption.get
      assertEquals((r.status, r.code, r.message, r.fields), (402, "WITHDRAWAL_BLOCKED_KYC", message, Vector("kycStatus" -> Field.Text(kyc.wire))))
    assertEquals(previewed, 0)

  test("5. the preview's first refusal: insufficient funds and turnover are 402, turnover naming no amount"):
    assertEquals(code(run(preview = Right(Preview(Some("INSUFFICIENT_FUNDS"))))), Left((402, "INSUFFICIENT_FUNDS")))
    val turnover = run(preview = Right(Preview(Some("WITHDRAWAL_BLOCKED_TURNOVER")))).left.toOption.get
    assertEquals((turnover.status, turnover.code, turnover.fields), (402, "WITHDRAWAL_BLOCKED_TURNOVER", Vector.empty))

  test("5. a request already pending is 409 at once, before the limits"):
    assertEquals(code(run(limits = Pair(Some(1L)), preview = Right(Preview(Some("WITHDRAWAL_REQUEST_ALREADY_PENDING"))))),
      Left((409, "WITHDRAWAL_REQUEST_ALREADY_PENDING")))

  test("5. a wallet not found, or not answering, is the preview's refusal"):
    assertEquals(code(run(preview = Left(Refusals.WalletNotFound))), Left((404, "WALLET_NOT_FOUND")))
    assertEquals(code(run(preview = Left(Refusals.Unavailable))), Left((503, "SERVICE_UNAVAILABLE")))

  test("5 before 6: turnover before the limits"):
    assertEquals(code(run(limits = Pair(Some(1L)), preview = Right(Preview(Some("WITHDRAWAL_BLOCKED_TURNOVER"))))),
      Left((402, "WITHDRAWAL_BLOCKED_TURNOVER")))

  test("6. the weekly, then the monthly limit, with the limit, the used, the remaining and the currency"):
    val recent = Standing(Kyc.Verified, recent = Vector(Counted("r", 9000L, Eur, now.minusSeconds(3600))), destination = standing.destination)
    val weekly = run(st = Some(recent), limits = Pair(Some(10000L), Some(10000L))).left.toOption.get
    assertEquals((weekly.status, weekly.code), (402, "WITHDRAWAL_BLOCKED_WEEKLY_LIMIT"))
    assertEquals(weekly.fields, Vector("limit" -> Field.Num(10000L), "used" -> Field.Num(9000L), "remaining" -> Field.Num(1000L),
      "currency" -> Field.Text("EUR")))
    assertEquals(code(run(st = Some(recent), limits = Pair(Some(50000L), Some(10000L)))), Left((402, "WITHDRAWAL_BLOCKED_MONTHLY_LIMIT")))

  test("6 before 8: the limits before the forfeit leaving too little"):
    assertEquals(code(run(limits = Pair(Some(1L)), preview = Right(Preview(Some("WITHDRAWAL_INSUFFICIENT_AFTER_BONUS_FORFEIT"))))),
      Left((402, "WITHDRAWAL_BLOCKED_WEEKLY_LIMIT")))

  test("8. a forfeit leaving too little is 422 with the most withdrawable and the total forfeit"):
    val r = run(preview = Right(Preview(Some("WITHDRAWAL_INSUFFICIENT_AFTER_BONUS_FORFEIT"), true, 2000L, 300L, 2000L, 4000L)))
      .left.toOption.get
    assertEquals((r.status, r.code, r.fields), (422, "WITHDRAWAL_INSUFFICIENT_AFTER_BONUS_FORFEIT",
      Vector("maxWithdrawable" -> Field.Num(4000L), "totalForfeit" -> Field.Num(2000L))))

  test("9. a forfeit not confirmed is 402 with the bonus, the clawback and the total; confirmed passes"):
    val p = Right(Preview(None, true, 2000L, 300L, 2300L, 9000L))
    val r = run(preview = p).left.toOption.get
    assertEquals((r.status, r.code, r.fields), (402, "WITHDRAWAL_REQUIRES_BONUS_FORFEIT_CONFIRMATION",
      Vector("bonusBalance" -> Field.Num(2000L), "clawbackAmount" -> Field.Num(300L), "totalForfeit" -> Field.Num(2300L))))
    assertEquals(run(draft.copy(confirmForfeit = true), preview = p).map(_.confirmForfeit), Right(true))

  test("9 before 10: the confirmation before the terminal"):
    assertEquals(code(run(draft.copy(terminalId = Some("nowhere")), preview = Right(Preview(None, true)))),
      Left((402, "WITHDRAWAL_REQUIRES_BONUS_FORFEIT_CONFIRMATION")))

  test("10. a terminal not eligible is 422 naming it: unknown, or VISA without a VISA deposit"):
    val r = run(draft.copy(terminalId = Some("nowhere"))).left.toOption.get
    assertEquals((r.status, r.code, r.fields), (422, "TERMINAL_NOT_ELIGIBLE", Vector("terminalId" -> Field.Text("nowhere"))))
    assertEquals(code(run(draft.copy(terminalId = Some("visa")))), Left((422, "TERMINAL_NOT_ELIGIBLE")))
    assertEquals(run(draft.copy(terminalId = Some("visa")), st = Some(standing.copy(deposits = Map("x" -> Set("VISA"))))).isRight, true)

  test("11. a terminal requiring a destination with none named is DESTINATION_REQUIRED"):
    assertEquals(code(run(draft.copy(destinationId = None))), Left((422, "DESTINATION_REQUIRED")))

  test("12. a crypto terminal without an address is WALLET_ADDRESS_REQUIRED, before the coin"):
    assertEquals(code(run(draft.copy(terminalId = Some("coin"), destinationId = None, cryptoCurrency = Some("DOGE")))),
      Left((422, "WALLET_ADDRESS_REQUIRED")))

  test("13. a coin not in the terminal's list is refused with the list; one in it, in any case, passes"):
    val d = draft.copy(terminalId = Some("coin"), destinationId = None, walletAddress = Some("0xabc"))
    val r = run(d.copy(cryptoCurrency = Some("DOGE"))).left.toOption.get
    assertEquals((r.status, r.code), (422, "CRYPTO_CURRENCY_NOT_SUPPORTED"))
    assertEquals(r.fields.collectFirst { case ("supported", v) => v }, Some(Field.Texts(Vector("USDT", "BTC"))))
    val passed = run(d.copy(cryptoCurrency = Some("btc"))).toOption.get.payee
    assertEquals((passed.coin, passed.walletAddress.map(Personal.reveal)), (Some("BTC"), Some("0xabc")))

  test("13. a terminal with no coins listed takes any coin"):
    val open = crypto.copy(id = "open", coins = Vector.empty)
    val d    = draft.copy(terminalId = Some("open"), destinationId = None, walletAddress = Some("0x"), cryptoCurrency = Some("DOGE"))
    assertEquals(Gates.check(Inputs(d, Some(standing), Vector(open), _ => Pair(), now, (_, _) => Right(Preview()))).isRight, true)

  test("14. a destination not the player's, or not active, is INVALID_PAYOUT_DESTINATION"):
    assertEquals(code(run(draft.copy(destinationId = Some("someone-elses")))), Left((422, "INVALID_PAYOUT_DESTINATION")))

  test("a value the terminal's gateway does not pay with is not kept"):
    val passed = run(draft.copy(walletAddress = Some("0xabc"), mobile = Some("+1"))).toOption.get.payee
    assertEquals((passed.walletAddress, passed.mobile), (None, None))

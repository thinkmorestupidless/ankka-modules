package io.eitheror.casino.rewards.clients

import io.eitheror.casino.contract.money.Currency
import io.eitheror.casino.rewards.Settings
import io.eitheror.casino.rewards.Json.*
import io.eitheror.casino.rewards.it.{Answer, DirectServices, FakeGameProvider, FakePlayers, FakeProgramme, FakeWallet}

import java.time.{Duration, Instant}

/** Each client against its fake: what it sends, what it reads from an answer, a refusal with its code, and no answer. */
class ClientsSuite extends munit.FunSuite:
  private val settings  = Settings(brand = "goldvibe", requestBound = Duration.ofSeconds(2))
  private val wallet    = FakeWallet(settings.brand)
  private val provider  = FakeGameProvider(settings.brand)
  private val players   = FakePlayers(settings.brand)
  private val programme = FakeProgramme()
  private val Nowhere   = "http://127.0.0.1:1"

  private val services = DirectServices(Map(settings.wallet -> wallet.address, settings.gameProvider -> provider.address,
    settings.players -> players.address))
  private val nowhere = DirectServices(Map(settings.wallet -> Nowhere, settings.gameProvider -> Nowhere, settings.players -> Nowhere))

  private val eur = Currency.unsafe("EUR")

  private def unanswered(c: Called[?]): Boolean = c match
    case Called.Unreachable(_) => true
    case _                     => false

  override def beforeEach(context: BeforeEach): Unit =
    wallet.reset(); provider.reset(); players.reset(); programme.reset()

  override def afterAll(): Unit =
    wallet.stop(); provider.stop(); players.stop(); programme.stop()

  private def terms = AwardTerms("offer-1", 1000L, 30L, 5000L, Some(3L), Some(Instant.parse("2026-12-01T00:00:00Z")),
    AwardRules(maxStake = Some(500L), fundingOrder = "real_first", scopeInclude = Set("slots")), maxConcurrentGrants = Some(2))

  // ── wallet ────────────────────────────────────────────────────────────────

  test("the award names the record by its key and sends the terms flattened"):
    val w = Wallet.http(services, settings)
    w.award("p 1", eur, "r-1", terms) match
      case Called.Done(a) => assertEquals((a.recordId, a.wageringRequirement, a.balances.bonusBalance), (Some("r-1"), 30000L, 1000L))
      case other          => fail(s"not awarded: $other")
    val sent = wallet.calls("awards").head
    assertEquals(sent.path, "/v1/casino/brands/goldvibe/wallets/p 1/EUR/awards")
    val b = sent.json.get
    assertEquals((b.text("key"), b.text("bonusRef"), b.long("maxWin"), b.long("maxConcurrentGrants"), b.text("fundingOrder"),
      b.strings("scopeInclude"), b.text("expiresAt")), (Some("r-1"), Some("offer-1"), Some(5000L), Some(2L), Some("real_first"),
      Vector("slots"), Some("2026-12-01T00:00:00Z")))
    assert(b.get("winAllocation") == null, "an empty rule is left out")

  test("forfeit, switch, credit, close and cancel act on the named record; the reads list what is open"):
    val w = Wallet.http(services, settings)
    w.award("p1", eur, "r-1", terms): Unit
    w.award("p1", eur, "r-2", terms.copy(priority = Some(1L))): Unit
    assertEquals(w.restrictedFunds("p1", eur), Called.Done(RestrictedFunds(Vector(
      OpenRecord("r-1", "r-1", "offer-1", "active", 3L, 1000L, 30000L, 0L, 5000L),
      OpenRecord("r-2", "r-2", "offer-1", "active", 1L, 1000L, 30000L, 0L, 5000L)), 2000L)))
    assertEquals(w.balance("p1", eur), Called.Done(WalletBalance(0L, 2000L, Some("r-1"))))
    assertEquals(w.switch("p1", eur, "r-2/switch-1", "r-2"), Called.Done(Switched(Some(4L), alreadyPrimary = false)))
    w.providerCredit("p1", eur, "r-9/credit", 250L, "r-9", "free_rounds", "rewards") match
      case Called.Done(c) => assertEquals((c.landedOn, c.txnId), (Some("r-2"), Some("txn-r-9/credit")))
      case other          => fail(s"not credited: $other")
    wallet.pendingCap("p1", eur.code, "r-1")
    assertEquals(w.closePendingCap("p1", eur, "r-1/close-cap", "r-1"), Called.Done(CapClosed(Some("r-1"))))
    w.cancel("p1", eur, "r-2/cancel", "r-2", requireBaseline = true) match
      case Called.Done(c) => assertEquals(c.forfeited, Vector("r-2"))
      case other          => fail(s"not cancelled: $other")
    assertEquals(wallet.keys("awards/cancel"), Vector("r-2/cancel"))
    assertEquals(wallet.calls("awards/cancel").head.json.get.bool("requireBaseline"), Some(true))
    assertEquals(w.restrictedFunds("p1", eur), Called.Done(RestrictedFunds(Vector.empty, 0L)))

  test("a wallet refusal carries its status and code"):
    val w = Wallet.http(services, settings)
    assertEquals(w.forfeit("p1", eur, "r-1/forfeit", "r-1"), Called.Refused(409, Wallet.NoActiveBonus, Wallet.NoActiveBonus))
    assertEquals(w.closePendingCap("p1", eur, "r-1/close-cap", "r-1"), Called.Refused(409, Wallet.NotPendingCap, Wallet.NotPendingCap))
    w.award("p1", eur, "r-1", terms): Unit
    wallet.noBaseline("p1", "EUR", "r-1")
    assertEquals(w.cancel("p1", eur, "r-1/cancel", "r-1", requireBaseline = true),
      Called.Refused(409, Wallet.MissingBaseline, Wallet.MissingBaseline))

  test("a repeated key is answered as the first, as the wallet's idempotency is"):
    val w = Wallet.http(services, settings)
    val first = w.award("p1", eur, "r-1", terms)
    assertEquals(w.award("p1", eur, "r-1", terms), first)
    assertEquals(wallet.open("p1", "EUR").size, 1)

  test("a 5xx, a refusal with no code, and a wallet that cannot be reached are not answered"):
    val w = Wallet.http(services, settings)
    wallet.down = Some(Answer(503, """{"error":"unavailable"}"""))
    assert(unanswered(w.award("p1", eur, "r-1", terms)))
    wallet.down = Some(Answer(400, ""))
    assert(unanswered(w.balance("p1", eur)))
    assert(unanswered(Wallet.http(nowhere, settings).award("p1", eur, "r-1", terms)))

  // ── game-provider ─────────────────────────────────────────────────────────

  private def grant = FreeRoundsGrant("r-7", "p1", Vector("sweet-bonanza"), 10, "0.20", eur, 7L * 86400L)

  test("free rounds: known, create, poll and cancel under the grant id"):
    val g = GameProvider.http(services, settings)
    assertEquals(g.known("p1", eur), Called.Done(()))
    assertEquals(g.create(grant), Called.Done(BonusStatus.Active))
    val sent = provider.createCalls.head.json.get
    assertEquals((sent.text("grantId"), sent.long("count"), sent.text("spinValue"), sent.strings("gameCodes"), sent.long("durationSeconds")),
      (Some("r-7"), Some(10L), Some("0.20"), Vector("sweet-bonanza"), Some(604800L)))
    provider.statusFor("r-7", "rejected")
    assertEquals(g.status("r-7"), Called.Done(BonusStatus.Rejected))
    assert(!BonusStatus.Rejected.playable)
    assertEquals(g.cancel("r-7", "p1"), Called.Done(BonusStatus.Cancelled))
    assertEquals(provider.cancelCalls("r-7").head.json.get.text("playerId"), Some("p1"))
    provider.createStatus("r-8", "something-new")
    assertEquals(g.create(grant.copy(grantId = "r-8")), Called.Done(BonusStatus.Error))

  test("spin values and a game's bonus types; an unknown game is refused game_not_found"):
    val g = GameProvider.http(services, settings)
    provider.spinValues("EUR", "0.10", "0.20")
    provider.game("sweet-bonanza", "free_spins")
    assertEquals(g.spinValues(Vector("sweet-bonanza", "gates-of-olympus"), eur), Called.Done(SpinValues(Vector("0.10", "0.20"), configured = true)))
    assertEquals(provider.received.last.query.get("gameCodes"), Some("sweet-bonanza,gates-of-olympus"))
    assertEquals(g.game("sweet-bonanza"), Called.Done(ProviderGame("sweet-bonanza", Vector("free_spins"))))
    assert(ProviderGame("x", Vector("free_bets")).freeRoundsEligible && !ProviderGame("x", Vector("jackpot")).freeRoundsEligible)
    assertEquals(g.game("missing"), Called.Refused(404, GameProvider.GameNotFound, ""))
    provider.notConfigured = true
    assertEquals(g.spinValues(Vector("sweet-bonanza"), eur), Called.Done(SpinValues(Vector.empty, configured = false)))

  test("game-provider unreachable or failing is not answered"):
    assert(unanswered(GameProvider.http(nowhere, settings).cancel("r-7", "p1")))
    provider.down = Some(Answer(503, """{"error":"provider_unavailable"}"""))
    assert(unanswered(GameProvider.http(services, settings).spinValues(Vector("x"), eur)))

  // ── players ───────────────────────────────────────────────────────────────

  test("the brand's duplicate detector switch, on by default"):
    val p = Players.http(services, settings)
    assertEquals(p.brandConfig(), Called.Done(BrandConfig(multiAccountDetection = true)))
    players.multiAccountDetection = false
    assertEquals(p.brandConfig(), Called.Done(BrandConfig(multiAccountDetection = false)))
    players.brandConfigAnswer = Some(Answer(403, """{"error":"forbidden"}"""))
    assertEquals(p.brandConfig(), Called.Refused(403, "forbidden", ""))
    assert(unanswered(Players.http(nowhere, settings).brandConfig()))

  // ── the programme ─────────────────────────────────────────────────────────

  test("the programme's code document, read at its address"):
    val at = Instant.parse("2026-10-25T10:00:00Z")
    programme.documentFor("goldvibe", 12L, at, ("SUMMER50", "offer-3", "tag-1"))
    assertEquals(Programme.http(settings).fetch(programme.url("goldvibe")),
      Called.Done(ProgrammeDocument("goldvibe", 12L, Some(at), Vector(ProgrammeCode("SUMMER50", "offer-3", "tag-1")))))

  test("a document refused, unreadable, too slow or out of reach is not a document"):
    val p = Programme.http(settings)
    programme.answerFor("goldvibe", Answer(410, """{"error":"gone"}"""))
    assertEquals(p.fetch(programme.url("goldvibe")), Called.Refused(410, "gone", ""))
    programme.answerFor("goldvibe", Answer(200, """{"brand":"goldvibe"}"""))
    assert(unanswered(p.fetch(programme.url("goldvibe"))))
    // A brand the programme publishes nothing for (404) has no codes, rather than no answer.
    assertEquals(p.fetch(programme.url("nobody")), Called.Done(ProgrammeDocument.none))
    assert(unanswered(p.fetch("ftp://example.test/codes.json")))
    assert(unanswered(p.fetch(s"$Nowhere/codes/goldvibe.json")))
    programme.documentFor("goldvibe", 1L, Instant.EPOCH)
    programme.delayMillis = 1500L
    assert(unanswered(Programme.http(settings.copy(requestBound = Duration.ofMillis(300))).fetch(programme.url("goldvibe"))))

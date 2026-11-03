package io.eitheror.casino.rewards.it

import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.contract.money.Currency
import io.eitheror.casino.rewards.Json
import io.eitheror.casino.rewards.player.{ClaimOffer, Outcome, PlayerRewards}
import io.eitheror.casino.rewards.reward.{RewardStatus, Source}

import java.time.Duration as JDuration

/**
 * One evaluator on every path (FR-005, FR-006; SC of US2), over the whole service: each decline reason a path can reach,
 * answered in that path's own shape (contracts/routes.md). The player's selection answers `409 {error: <reason>}`; a
 * code `409 {status: "ineligible", reason}`; a confirming deposit records the reason on the reward, which stays pending
 * and is awarded nothing (FR-012), or expires it for the claim window; the campaign's award route `422 {error}`, `503`
 * for `ELIGIBILITY_CHECK_UNAVAILABLE`; the operator's award `409 {status: "ineligible", reason}`, held to rules 1 to 4.
 */
class PathsSuite extends munit.FunSuite:
  private lazy val kit   = SharedKit.kit
  private lazy val paths = ClaimPaths(kit)
  override def munitTimeout = scala.concurrent.duration.Duration(300, "s")
  override def beforeEach(context: BeforeEach): Unit =
    kit.reset()
    io.eitheror.casino.rewards.consumers.Detection.forget()

  private val Eur = Currency.unsafe("EUR")

  enum Path:
    case Select, Code, Deposit

  /** The player as the reason needs them, and the offer's further fields (which may name an offer they hold). */
  private type Setup = String => Seq[(String, Any)]

  /**
   * On each path: a new player set up, an offer of the path's kind (a free bonus for a selection and a code, a match for
   * a deposit) with `fields`, the claim, and the refusal in the path's shape.
   */
  private def declined(reason: String, on: Seq[Path], setup: Setup, deposit: Long = 3000L, ordinal: Option[Int] = None): Unit =
    on.foreach { path =>
      val p      = paths.newPlayer("pa-p")
      val fields = setup(p)
      path match
        case Path.Select =>
          val c = paths.viaSelect(p, paths.noDeposit(fields*))
          val r = c.reply.get
          assertEquals((r.status, r.str("error")), (409, reason), s"select: ${r.text}")
        case Path.Code =>
          val code = paths.unique("PATH")
          paths.noDeposit((fields :+ paths.codes(code))*): Unit
          val r = paths.enterCode(p, code)
          assertEquals((r.status, r.str("status"), r.str("reason")), (409, "ineligible", reason), s"code: ${r.text}")
        case Path.Deposit =>
          val offerId = paths.depositMatch(fields*)
          def awards  = kit.wallet.calls("awards").filter(_.path.contains(s"/wallets/$p/")).size
          val before  = awards
          val c       = paths.viaDeposit(p, offerId, deposit, ordinal)
          assertEquals(c.refusal, Some(reason), "deposit")
          val r = paths.rewardOf(p, offerId)
          if reason == "CLAIM_WINDOW_EXPIRED" then assertEquals((r.status, r.terminationReason), (RewardStatus.Expired, Some("pending_expired")))
          else assertEquals((r.status, r.lastDecline), (RewardStatus.Pending, Some(reason)))
          assertEquals(awards, before, "the deposit was awarded nothing")
    }

  private val all = Seq(Path.Select, Path.Code, Path.Deposit)

  test("SUSPECTED_DUPLICATE on every path"):
    declined("SUSPECTED_DUPLICATE", all, p => { paths.checked(p); paths.flagged(p); Seq.empty })

  test("NO_DEPOSIT_OFFER_BLOCKED on the selection and a code, offers with no deposit only"):
    declined("NO_DEPOSIT_OFFER_BLOCKED", Seq(Path.Select, Path.Code), p => { paths.blocked(p); Seq.empty })

  test("ELIGIBILITY_CHECK_UNAVAILABLE on the selection and a code, inside ten minutes of registration"):
    declined("ELIGIBILITY_CHECK_UNAVAILABLE", Seq(Path.Select, Path.Code), p => { paths.checkStarted(p, kit.now()); Seq.empty })

  test("ACTIVE_BONUS_CONFLICT on every path"):
    declined("ACTIVE_BONUS_CONFLICT", all, p => { paths.holdingActive(p); Seq("maxConcurrentGrants" -> 1) })

  test("CONFLICTING_GRANT on every path"):
    declined("CONFLICTING_GRANT", all, p => Seq("conflictsWith" -> Json.arr(paths.holdingActive(p))))

  test("EXCLUDED_BY_PRIOR_OFFER on every path"):
    declined("EXCLUDED_BY_PRIOR_OFFER", all, p => Seq("excludedOfferIds" -> Json.arr(paths.holdingActive(p))))

  test("MAX_CLAIMS_REACHED on every path"):
    // The player claimed the offer once; it is made visible to them again.
    all.foreach { path =>
      val p = paths.newPlayer("pa-p")
      path match
        case Path.Select =>
          val id = paths.holdingActive(p, "maxClaimsPerPlayer" -> 1)
          val r  = paths.viaSelect(p, id).reply.get
          assertEquals((r.status, r.str("error")), (409, "MAX_CLAIMS_REACHED"), r.text)
        case Path.Code =>
          val code = paths.unique("AGAIN")
          val id   = paths.noDeposit("maxClaimsPerPlayer" -> 1, paths.codes(code))
          assertEquals(paths.viaSelect(p, id).refusal, None)
          paths.active(p, id): Unit
          val r = paths.enterCode(p, code)
          assertEquals((r.status, r.str("status"), r.str("reason")), (409, "ineligible", "MAX_CLAIMS_REACHED"), r.text)
        case Path.Deposit =>
          val id = paths.depositMatch("maxClaimsPerPlayer" -> 1)
          assertEquals(paths.viaDeposit(p, id).refusal, None)
          paths.active(p, id): Unit
          assertEquals(paths.viaDeposit(p, id).refusal, Some("MAX_CLAIMS_REACHED"))
    }

  test("CLAIM_WINDOW_EXPIRED: from registration on the selection and a code; from the selection on a deposit, which expires it"):
    declined("CLAIM_WINDOW_EXPIRED", Seq(Path.Select, Path.Code),
      p => { paths.registered(p, paths.ago(JDuration.ofDays(2))); Seq("claimWindowDays" -> 1) })
    val p  = paths.newPlayer("pa-p")
    val id = paths.depositMatch("claimWindowDays" -> 1)
    val r  = paths.target(p, id)
    assertEquals(paths.select(p, id).status, 200)
    kit.advance(JDuration.ofDays(2))
    paths.deposit(p, 3000L): Unit
    assertEquals(paths.until(r, "expired")(_.status == RewardStatus.Expired).terminationReason, Some("pending_expired"))

  test("BELOW_MIN_DEPOSIT and DEPOSIT_NUMBER_MISMATCH on a deposit; a code and a selection do not ask for the deposit"):
    declined("BELOW_MIN_DEPOSIT", Seq(Path.Deposit), _ => Seq("minDepositAmount" -> 2000L), deposit = 1000L)
    declined("DEPOSIT_NUMBER_MISMATCH", Seq(Path.Deposit), _ => Seq("depositNumberTarget" -> 2), ordinal = Some(1))
    val p    = paths.newPlayer("pa-p")
    val code = paths.unique("MIN")
    val id   = paths.depositMatch("minDepositAmount" -> 2000L, "depositNumberTarget" -> 2, paths.codes(code))
    assertEquals(paths.enterCode(p, code).status, 200)
    assertEquals(paths.rewardOf(p, id).status, RewardStatus.Pending)

  test("a code no live offer carries is invalid_code (422), never the evaluator's INVALID_CODE"):
    val p = paths.newPlayer("pa-p")
    val r = paths.enterCode(p, paths.unique("NOPE"))
    assertEquals((r.status, r.str("error")), (422, "invalid_code"), r.text)

  test("the operator's override applies rules 1 to 4 only (FR-006), on the evaluation the operator's award makes"):
    def claim(p: String, offerId: String) =
      kit.components.forEventSourcedEntity(EntityId(p)).call(PlayerRewards.claim)
        .invoke(ClaimOffer(paths.offerOf(offerId), operator = true, None, Source.OperatorManual, None, None, 0, Eur, kit.now())).outcome
    // Rules 5 to 13 do not refuse it: a conflict, an exclusion, the claims, a code, the window.
    val p    = paths.newPlayer("pa-p")
    val held = paths.holdingActive(p)
    paths.registered(p, paths.ago(JDuration.ofDays(5)))
    val lenient = paths.noDeposit("conflictsWith" -> Json.arr(held), "excludedOfferIds" -> Json.arr(held), "claimWindowDays" -> 1,
      paths.codes(paths.unique("OP")))
    assert(claim(p, lenient).isInstanceOf[Outcome.Claimed], claim(p, lenient).toString)
    // Rules 1 to 4 do.
    val q = paths.newPlayer("pa-q")
    paths.checked(q)
    paths.flagged(q)
    assertEquals(claim(q, paths.noDeposit()), Outcome.Declined(None, "SUSPECTED_DUPLICATE"))
    val r = paths.newPlayer("pa-r")
    paths.holdingActive(r): Unit
    assertEquals(claim(r, paths.noDeposit("maxConcurrentGrants" -> 1)), Outcome.Declined(None, "ACTIVE_BONUS_CONFLICT"))

  test("the campaign's award route answers each decline as 422 {error}, ELIGIBILITY_CHECK_UNAVAILABLE as 503 (task T059)"):
    def campaign(reason: String, setup: Setup, offer: Seq[(String, Any)] => String = paths.noDeposit(_*)): Unit =
      val p = paths.newPlayer("pa-c")
      val r = paths.viaCampaign(p, offer(setup(p))).reply.get
      val status = if reason == "ELIGIBILITY_CHECK_UNAVAILABLE" then 503 else 422
      assertEquals((r.status, r.json.toString), (status, s"""{"error":"$reason"}"""), reason)
    campaign("SUSPECTED_DUPLICATE", p => { paths.checked(p); paths.flagged(p); Seq.empty })
    campaign("NO_DEPOSIT_OFFER_BLOCKED", p => { paths.blocked(p); Seq.empty })
    campaign("ELIGIBILITY_CHECK_UNAVAILABLE", p => { paths.checkStarted(p, kit.now()); Seq.empty })
    campaign("ACTIVE_BONUS_CONFLICT", p => { paths.holdingActive(p); Seq("maxConcurrentGrants" -> 1) })
    campaign("CONFLICTING_GRANT", p => Seq("conflictsWith" -> Json.arr(paths.holdingActive(p))))
    campaign("EXCLUDED_BY_PRIOR_OFFER", p => Seq("excludedOfferIds" -> Json.arr(paths.holdingActive(p))))
    campaign("CLAIM_WINDOW_EXPIRED", p => { paths.registered(p, paths.ago(JDuration.ofDays(2))); Seq("claimWindowDays" -> 1) })
    campaign("BELOW_MIN_DEPOSIT", _ => Seq("minDepositAmount" -> 2000L), paths.depositMatch(_*))
    campaign("DEPOSIT_NUMBER_MISMATCH", p => {
      paths.deposit(p, 3000L, Some(1)): Unit
      paths.awaitRecord(p, "the deposit")(_.lastDeposit.isDefined): Unit
      Seq("depositNumberTarget" -> 2)
    }, paths.depositMatch(_*))
    // Rule 6 is not reached: a claim the player holds of the offer is answered alreadyProcessed first (`CW:174-183`).
    val p  = paths.newPlayer("pa-c")
    val id = paths.holdingActive(p, "maxClaimsPerPlayer" -> 1)
    val r  = paths.viaCampaign(p, id).reply.get
    assertEquals((r.status, r.str("alreadyProcessed")), (200, "true"), r.text)

  test("the operator's award answers each decline as 409 {status: ineligible, reason} and applies rules 1 to 4 (task T060)"):
    def operator(reason: String, setup: Setup): Unit =
      val p = paths.newPlayer("pa-o")
      val r = paths.viaOperator(p, paths.noDeposit(setup(p)*)).reply.get
      assertEquals((r.status, r.str("status"), r.str("reason")), (409, "ineligible", reason), r.text)
    operator("SUSPECTED_DUPLICATE", p => { paths.checked(p); paths.flagged(p); Seq.empty })
    operator("NO_DEPOSIT_OFFER_BLOCKED", p => { paths.blocked(p); Seq.empty })
    operator("ELIGIBILITY_CHECK_UNAVAILABLE", p => { paths.checkStarted(p, kit.now()); Seq.empty })
    operator("ACTIVE_BONUS_CONFLICT", p => { paths.holdingActive(p); Seq("maxConcurrentGrants" -> 1) })
    // Rules 5 to 13 are not the operator's: a conflict, an exclusion, the claims, a code, the window, the minimum.
    val p    = paths.newPlayer("pa-o")
    val held = paths.holdingActive(p, "maxClaimsPerPlayer" -> 1)
    paths.registered(p, paths.ago(JDuration.ofDays(5)))
    val lenient = paths.depositMatch("conflictsWith" -> Json.arr(held), "excludedOfferIds" -> Json.arr(held), "claimWindowDays" -> 1,
      "minDepositAmount" -> 5000L, "depositNumberTarget" -> 3, paths.codes(paths.unique("OP")))
    val r = paths.viaOperator(p, lenient, "depositAmount" -> 1000L).reply.get
    assertEquals((r.status, r.str("status"), r.json.get("bonusAmount").asLong), (200, "granted", 1000L), r.text)
    // An offer the player already claimed is awarded again: the operator's award has no repeat check (`BR:3198`).
    assertEquals(paths.viaOperator(p, held).reply.get.status, 200)

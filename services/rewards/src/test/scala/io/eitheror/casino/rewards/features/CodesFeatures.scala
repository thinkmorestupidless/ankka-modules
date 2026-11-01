package io.eitheror.casino.rewards.features

import io.eitheror.casino.contract.facts.{BonusCodeEntered, BonusGranted, CasinoRewards}
import io.eitheror.casino.rewards.Json.*
import io.eitheror.casino.rewards.it.ClaimPaths
import io.eitheror.casino.rewards.reward.{RewardStatus, Source}

/**
 * features/rewards/codes.feature (US3; FR-003, FR-019 to FR-021): a player enters a code through `/rewards`, the
 * brand's codes matched first, then the programme's code document as `codes-<slug>` last fetched it from
 * `FakeProgramme`. A code is the brand's for as long as an active offer carries it, so a scenario frees the code it
 * names first; the document is the brand's, published anew by each scenario.
 */
class CodesFeatures extends RewardsFeatures("codes.feature"):
  private def paths = ClaimPaths(kit)

  private var offerId = ""
  private var code    = ""
  private var tag     = ""

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    offerId = ""
    code = ""
    tag = ""

  /** The programme's document for the brand holding `entries`, once `codes-<slug>` has fetched it. */
  private def document(entries: (String, String, String)*): Unit =
    kit.programme.documentFor(kit.slug, System.nanoTime(), kit.now(), entries*)
    eventually("the document fetched") {
      val r = kit.request("GET", kit.offersPath("/code-document"), None, operator)
      Option.when(r.status == 200 && entries.forall((c, o, _) =>
        r.json.objects("entries").exists(e => e.text("code").contains(c.trim.toLowerCase) && e.text("offerId").contains(o))))(())
    }

  private def facts = kit.facts(CasinoRewards).filter(_.key == player)

  // ── steps ──────────────────────────────────────────────────────────────────────────────────

  Given("brand {string}") { (slug: String) => assertEquals(kit.slug, slug) }

  Given("an active offer carrying offer code {string}") { (c: String) =>
    paths.freeCode(c)
    code = c
    offerId = paths.depositMatch(paths.codes(c))
  }

  When("a player enters {string}") { (c: String) => last = paths.enterCode(player, c) }

  Then("the offer becomes visible to them and is selected, regardless of case") { () =>
    assertEquals(last.status, 200, last.text)
    assertEquals((last.str("offerId"), last.str("source")), (offerId, "code"))
    val r = paths.rewardOf(player, offerId)
    assertEquals((r.status, r.source, r.code), (RewardStatus.Pending, Source.PlayerCode, Some("welcome")))
    val state = paths.state(player)
    assertEquals((state.text("state"), state.get("selected").text("offerId")), (Some("pending"), Some(offerId)), state.toString)
    eventually("the code's fact")(facts.collectFirst { case f: BonusCodeEntered if f.outcome.contains("matched") => f }): Unit
  }

  Given("no live offer carrying the code {string} and no bonus code {string} in the programme's bonus code document") {
    (c: String, _: String) =>
      paths.freeCode(c)
      document()
  }

  Then("they are told {string}") { (error: String) =>
    assertEquals((last.status, last.str("error")), (422, error), last.text)
    assertEquals(paths.state(player).text("state"), Some("available"))
    eventually("the code's fact")(facts.collectFirst { case f: BonusCodeEntered if f.outcome.contains("invalid") => f }): Unit
  }

  Given("the programme's bonus code document for the brand holds {string} naming an offer") { (c: String) =>
    paths.freeCode(c)
    code = c
    tag = paths.unique("tag")
    offerId = paths.depositMatch()
    document((c, offerId, tag))
  }

  Then("the named offer is selected") { () =>
    assertEquals((last.status, last.str("offerId")), (200, offerId), last.text)
    assertEquals(paths.rewardOf(player, offerId).status, RewardStatus.Pending)
  }

  Then("the reward records the affiliate source") { () =>
    val r = paths.rewardOf(player, offerId)
    assertEquals((r.source, r.code, r.tag), (Source.AffiliateCode, Some(code), Some(tag)))
  }

  Then("the reward's facts carry the code and the programme's tag") { () =>
    val entered = eventually("the code's fact")(facts.collectFirst { case f: BonusCodeEntered => f })
    assertEquals((entered.code, entered.outcome), (code, Some("matched")))
    paths.deposit(player, 3000L): Unit
    val granted = eventually("bonus_granted")(facts.collectFirst { case f: BonusGranted if f.offerId == offerId => f })
    assertEquals((granted.code, granted.tag, granted.source), (Some(code), Some(tag), Some(Source.AffiliateCode.wire)))
  }

  Given("the programme's bonus code document for the brand holds {string}") { (c: String) =>
    paths.freeCode(c)
    document((c, paths.depositMatch(), paths.unique("tag")))
  }

  When("an operator creates a brand offer code {string}") { (c: String) =>
    createOffer("name" -> paths.unique("Brand"), "type" -> "NO_DEPOSIT", "noDepositRequired" -> true, "maxBonusValue" -> 1000L,
      "wageringMultiplier" -> 10L, "currency" -> "EUR", paths.codes(c)): Unit
  }

  Then("it is refused as {string}") { (error: String) => refusedWith(409, error): Unit }

  When("a player enters {string} as a preview") { (c: String) => last = paths.enterCode(player, c, preview = true) }

  Then("they are told the offer's terms") { () =>
    assertEquals(last.status, 200, last.text)
    val b = last.json
    assertEquals((b.bool("valid"), b.text("offerType"), b.bool("isAffiliate"), b.bool("firesImmediately")),
      (Some(true), Some("DEPOSIT_MATCH"), Some(false), Some(true)), last.text)
    assert(b.text("offerName").exists(_.nonEmpty), last.text)
    assert(b.text("preview").exists(_.contains("100% match up to 500.00 EUR")), last.text)
  }

  Then("nothing is selected") { () =>
    assertEquals(paths.state(player).text("state"), Some("available"))
    assertEquals(rows(), Vector.empty)
    assertEquals(paths.record(player).rewards, Map.empty)
  }


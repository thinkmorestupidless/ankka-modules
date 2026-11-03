package io.eitheror.casino.rewards.it

import io.eitheror.casino.contract.facts.{BonusCodeEntered, CasinoRewards}
import io.eitheror.casino.rewards.Json.*
import io.eitheror.casino.rewards.reward.{RewardStatus, Source}

import java.time.Instant

/**
 * The code routes over the whole service (FR-019 to FR-021; research R13): a code in any case and with spaces; a code
 * the programme's document holds whose offer is not live; the document unreachable, its last copy serving; and the
 * preview, which records nothing.
 */
class CodesSuite extends munit.FunSuite:
  private lazy val kit   = SharedKit.kit
  private lazy val paths = ClaimPaths(kit)
  override def munitTimeout = scala.concurrent.duration.Duration(180, "s")
  override def beforeEach(context: BeforeEach): Unit =
    kit.reset()
    io.eitheror.casino.rewards.consumers.Detection.forget()

  private def document(entries: (String, String, String)*): Unit =
    kit.programme.documentFor(kit.slug, System.nanoTime(), Instant.now(), entries*)
    kit.eventually("the document fetched") {
      val r = kit.request("GET", kit.offersPath("/code-document"), None, kit.asOperator())
      Option.when(r.status == 200 && entries.forall((c, o, _) =>
        r.json.objects("entries").exists(e => e.text("code").contains(c.trim.toLowerCase) && e.text("offerId").contains(o))))(())
    }

  private def entered(player: String): Vector[BonusCodeEntered] =
    kit.facts(CasinoRewards).filter(_.key == player).collect { case f: BonusCodeEntered => f }

  test("a brand code in any case and with spaces matches; the reward keeps it as entered, trimmed"):
    val code = paths.unique("Spring")
    val id   = paths.depositMatch(paths.codes(code))
    val p    = paths.newPlayer("cs-p")
    val r    = paths.enterCode(p, s"  ${code.toUpperCase}  ")
    assertEquals((r.status, r.str("offerId"), r.str("source")), (200, id, "code"), r.text)
    val reward = paths.rewardOf(p, id)
    assertEquals((reward.status, reward.source, reward.code), (RewardStatus.Pending, Source.PlayerCode, Some(code.toUpperCase)))
    val fact = kit.eventually("bonus_code_entered")(entered(p).headOption)
    assertEquals((fact.code, fact.outcome), (code.toUpperCase, Some("matched")))

  test("an affiliate code in any case matches the programme's entry"):
    val code = paths.unique("AFF")
    paths.freeCode(code)
    val id  = paths.depositMatch()
    val tag = paths.unique("tag")
    document((code, id, tag))
    val p = paths.newPlayer("cs-p")
    assertEquals(paths.enterCode(p, s" ${code.toLowerCase} ").status, 200)
    val reward = paths.rewardOf(p, id)
    assertEquals((reward.source, reward.tag), (Source.AffiliateCode, Some(tag)))

  test("a code in the programme's document whose offer is not live is invalid_code, on the entry and the preview"):
    val code = paths.unique("GONE")
    val id   = paths.depositMatch()
    assertEquals(kit.request("DELETE", kit.offersPath(s"/$id"), None, kit.asOperator()).status, 200)
    document((code, id, "tag-gone"))
    val p = paths.newPlayer("cs-p")
    val r = paths.enterCode(p, code)
    assertEquals((r.status, r.str("error")), (422, "invalid_code"), r.text)
    val pv = paths.enterCode(p, code, preview = true)
    assertEquals((pv.status, pv.json.bool("valid"), pv.str("error")), (200, Some(false), "code_not_found"), pv.text)
    assertEquals(kit.eventually("bonus_code_entered")(entered(p).headOption).outcome, Some("invalid"))

  test("the programme unreachable, its last copy serves the entry"):
    val code = paths.unique("KEPT")
    paths.freeCode(code)
    val id = paths.depositMatch()
    document((code, id, "tag-kept"))
    kit.programme.answerFor(kit.slug, Answer(503, """{"error":"down"}"""))
    val before = kit.programme.fetches(kit.slug).size
    kit.eventually("tried again")(Option.when(kit.programme.fetches(kit.slug).size > before)(())): Unit
    val p = paths.newPlayer("cs-p")
    val r = paths.enterCode(p, code)
    assertEquals((r.status, r.str("offerId")), (200, id), r.text)
    assertEquals(paths.rewardOf(p, id).source, Source.AffiliateCode)

  test("a preview of an affiliate's code is its offer's terms, isAffiliate, and records nothing"):
    val code = paths.unique("PREV")
    paths.freeCode(code)
    val id = paths.depositMatch("minDepositAmount" -> 2000L)
    document((code, id, "tag-prev"))
    val p = paths.newPlayer("cs-p")
    val r = paths.enterCode(p, code, preview = true)
    val b = r.json
    assertEquals((r.status, b.bool("valid"), b.bool("isAffiliate"), b.bool("firesImmediately")), (200, Some(true), Some(true), Some(false)),
      r.text)
    assertEquals(b.text("preview"), Some("100% match up to 500.00 EUR on your next deposit of at least 20.00 EUR"))
    assertEquals(paths.record(p).rewards, Map.empty)
    Thread.sleep(500)
    assertEquals(entered(p), Vector.empty)

  test("a code is required"):
    val p = paths.newPlayer("cs-p")
    for preview <- Seq(false, true) do
      val r = kit.json("POST", kit.rewardsPath(s"/$p/codes${if preview then "/preview" else ""}"),
        io.eitheror.casino.rewards.Json.obj("code" -> "  "), kit.asPlayer(p))
      assertEquals((r.status, r.str("error")), (400, "code is required"), r.text)

package io.eitheror.casino.kyc.it

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.kyc.Json

import java.util.UUID

/**
 * The gates as their callers read them: each answer decoded with a copy of `players`' `Kyc.read`
 * (`services/players/.../clients/Neighbours.scala`) and of `game-provider`'s `KycClient.playCheck`
 * (`services/game-provider/.../clients/Neighbours.scala`), so a drift in shape fails here. A refusal is an
 * answer, never read as an outage (R5).
 */
class CallersSuite extends munit.FunSuite with LogCapturing:
  override def munitTimeout = scala.concurrent.duration.Duration(180, "s")

  private lazy val kit = SharedKit.kit
  private var player   = ""

  override def beforeEach(context: BeforeEach): Unit =
    kit.reset()
    player = s"p-${UUID.randomUUID().toString.take(8)}"
    profile("CURACAO")

  // ── the callers' decoders, as they stand in their services ────────────────────────────────────

  /** `players`' `KycDecision`. */
  enum KycDecision:
    case Allowed
    case Refused(code: String, message: String)

  /** `players`' `Kyc.read`: a non-2xx is an outage; `allowed: false` needs a code. */
  private def playersRead(r: Reply): Either[String, KycDecision] =
    if r.status / 100 != 2 then Left(s"status ${r.status}")
    else
      Json.parse(r.text) match
        case Some(b) if bool(b, "allowed").contains(true) => Right(KycDecision.Allowed)
        case Some(b) => text(b, "code").map(c => KycDecision.Refused(c, text(b, "message").getOrElse(""))).toRight("refused without a code")
        case None    => Left("no body")

  /** `game-provider`'s `PlayCheck`. */
  enum PlayCheck:
    case Allowed
    case Blocked(kycStatus: String, message: String)

  /** `game-provider`'s `KycClient.playCheck`: a 2xx or 403 with `allowed` is an answer; anything else is the service unavailable. */
  private def gameProviderRead(r: Reply): Either[String, PlayCheck] =
    Json.parse(r.text) match
      case Some(b) if r.status / 100 == 2 || r.status == 403 =>
        bool(b, "allowed") match
          case Some(true)  => Right(PlayCheck.Allowed)
          case Some(false) => Right(PlayCheck.Blocked(text(b, "kycStatus").getOrElse("unknown"), text(b, "message").getOrElse("")))
          case None        => Left("the play check carried no answer")
      case _ => Left(s"play check answered ${r.status}: ${r.text.take(200)}")

  private def bool(b: JsonNode, f: String) = Option(b.get(f)).filter(_.isBoolean).map(_.asBoolean)
  private def text(b: JsonNode, f: String) = Option(b.get(f)).filter(_.isTextual).map(_.asText)

  // ── the calls ─────────────────────────────────────────────────────────────────────────────────

  private def profile(name: String): Unit =
    val r = kit.json("POST", kit.operatorPath("/policy/profile"), Json.obj("profile" -> name), kit.asOperator())
    assertEquals(r.status, 200, r.text)

  private def depositCheck(amount: Long, currency: Option[String]): Reply =
    val body = Json.obj(("playerId" -> player) +: ("amount" -> amount) +: currency.map("currency" -> _).toSeq*)
    kit.json("POST", kit.servicePath("/deposit-check"), body, kit.asService("players"))

  private def playCheck(): Reply = kit.request("GET", kit.servicePath(s"/$player/play-check"), None, kit.asService("game-provider"))

  // ── the answers ───────────────────────────────────────────────────────────────────────────────

  test("players reads an allowed deposit as allowed"):
    assertEquals(playersRead(depositCheck(5000, Some("EUR"))), Right(KycDecision.Allowed))

  test("players reads each refusal as a refusal with its code and message, never an outage"):
    profile("UKGC")
    val gate = playersRead(depositCheck(5000, Some("GBP")))
    assert(gate.exists { case KycDecision.Refused("DEPOSIT_BLOCKED_KYC", m) => m.nonEmpty; case _ => false }, gate.toString)
    profile("MGA")
    val currency = playersRead(depositCheck(5000, Some("GBP")))
    assert(currency.exists { case KycDecision.Refused("DEPOSIT_BLOCKED_KYC_CURRENCY", _) => true; case _ => false }, currency.toString)
    val threshold = playersRead(depositCheck(10_000_000, Some("EUR")))
    assert(threshold.exists { case KycDecision.Refused("DEPOSIT_BLOCKED_KYC_THRESHOLD", _) => true; case _ => false }, threshold.toString)

  test("a deposit check with no currency under a threshold is a refusal, not an outage"):
    profile("MGA")
    val r = playersRead(depositCheck(5000, None))
    assert(r.exists { case KycDecision.Refused("DEPOSIT_BLOCKED_KYC_CURRENCY", _) => true; case _ => false }, r.toString)

  test("game-provider reads an allowed play check as allowed"):
    assertEquals(gameProviderRead(playCheck()), Right(PlayCheck.Allowed))

  test("game-provider reads a refused play check as blocked, with the player's status and the message"):
    profile("UKGC")
    val r = gameProviderRead(playCheck())
    assert(r.exists { case PlayCheck.Blocked("unverified", m) => m.nonEmpty; case _ => false }, r.toString)

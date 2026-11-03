package io.eitheror.casino.players.it

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.contract.facts.*
import io.eitheror.casino.players.config.{BrandConfig, BrandConfigEntity}
import io.eitheror.casino.players.fraud.{FraudCaseEntity, FraudCasesView, FraudCaseStatus}
import io.eitheror.casino.players.player.{CheckOutcome, PlayerEntity, PlayerState}
import io.eitheror.casino.players.registration.{LookupTokens, RegistrationRuntime, RegistrationWorkflow}

import java.util.UUID
import scala.concurrent.duration.*
import scala.util.Random

/**
 * T062: duplicate detection at registration and the operator's review (FR-036, FR-037, SC-005); spec
 * 011's duplicate check, email variant and address (FR-034, FR-035).
 */
class DuplicatesSuite extends munit.FunSuite with LogCapturing:
  override val munitTimeout = 120.seconds
  private lazy val kit = SharedKit.kit

  override def beforeEach(context: BeforeEach): Unit = kit.reset()

  private def player(id: String): PlayerState = kit.components.forEventSourcedEntity(PlayerEntity.id(id)).call(PlayerEntity.get).invoke()
  private def unique = UUID.randomUUID().toString.take(8)

  /** Registers with the fields given; the player id. */
  private def registered(fields: (String, Any)*): String =
    val r = kit.register(fields*)
    assertEquals(r.status, 201, r.text)
    r.str("playerId")

  private def identity(first: String, last: String, dob: String = "1990-02-03") =
    Seq[(String, Any)]("firstName" -> first, "lastName" -> last, "dateOfBirth" -> dob)

  private def flagged(id: String) = kit.eventually(s"$id flagged", 10.seconds)(player(id).fraud)

  private def config(f: BrandConfig => BrandConfig): Unit =
    val ref = kit.components.forKeyValueEntity(EntityId(kit.slug))
    ref.call(BrandConfigEntity.set).invoke(f(ref.call(BrandConfigEntity.get).invoke())): Unit

  /** The index's entries: the key value entities `lookup-index` holds. */
  private def indexRows: Int =
    Kit.query(kit.testKit.jdbcUrl, "SELECT count(*) FROM durable_state WHERE persistence_id LIKE 'lookup-index%'").head.toInt

  private def op(method: String, path: String): Reply =
    kit.request(method, kit.operatorPath(path), if method == "GET" then None else Some("{}"), kit.asOperator(subject = "op-4"))

  test("one name and date of birth, differently cased and spaced: both flagged and linked, one case with identity, a fact each, within 10 s") {
    val n = unique
    val a = registered(identity(s"Ann$n", "Smith")*)
    val b = registered(identity(s"  ann$n ", "SMITH ")*)
    assertEquals(flagged(a).linkedPlayerIds, Set(b))
    assertEquals(flagged(b).linkedPlayerIds, Set(a))
    val c = kit.eventually("the case", 10.seconds)(FraudCasesView.ofPlayer(kit.views, b).headOption)
    assertEquals((c.caseId, c.playerIds.toSet, c.signals, c.status), (RegistrationWorkflow.caseOf(b), Set(a, b), Vector("identity"), FraudCaseStatus.Open))
    Seq(a, b).foreach(id => kit.eventually(s"player_fraud_flagged for $id")(kit.facts(CasinoPlayers).collectFirst {
      case f: PlayerFraudFlagged if f.playerId == id && f.fraudCaseId == c.caseId => f
    }))
  }

  test("one device fingerprint: the device signal; both signals at once name both in one case; a third links three") {
    val fp = s"fp-$unique"
    val a  = registered("deviceFingerprint" -> fp)
    val b  = registered("deviceFingerprint" -> fp)
    assertEquals(kit.eventually("the case", 10.seconds)(FraudCasesView.ofPlayer(kit.views, b).headOption).signals, Vector("device"))
    val n  = unique
    val c  = registered((identity(s"Bo$n", "Lane") :+ ("deviceFingerprint" -> s"fp2-$n"))*)
    val d  = registered((identity(s"Bo$n", "Lane") :+ ("deviceFingerprint" -> s"fp2-$n"))*)
    assertEquals(kit.eventually("the case", 10.seconds)(FraudCasesView.ofPlayer(kit.views, d).find(_.caseId == RegistrationWorkflow.caseOf(d))).signals,
      Vector("device", "identity"))
    val e = registered("deviceFingerprint" -> fp)
    assertEquals(kit.eventually("the third's case", 10.seconds)(FraudCasesView.ofPlayer(kit.views, e).find(_.caseId == RegistrationWorkflow.caseOf(e)))
      .playerIds.toSet, Set(a, b, e))
    assertEquals(flagged(a).caseIds.size, 2)
    assert(Seq(c, d).forall(id => player(id).fraud.nonEmpty))
  }

  test("detection off, or no names, date of birth or fingerprint: nothing compared, no case") {
    config(_.copy(multiAccountDetection = false))
    try
      val n = unique
      registered(identity(s"Cy$n", "Moss")*)
      val b = registered(identity(s"Cy$n", "Moss")*)
      assertEquals(ended(b).outcome, Some(CheckOutcome.Skipped))
      assertEquals((player(b).fraud, FraudCasesView.ofPlayer(kit.views, b)), (None, Vector.empty))
    finally config(_.copy(multiAccountDetection = true))
    val before = indexRows
    val bare   = registered()
    assertEquals(ended(bare).outcome, Some(CheckOutcome.Clear))
    assertEquals((player(bare).fraud, indexRows), (None, before))
  }

  // ── spec 011 FR-034, FR-035: the check, the email variant and the address ──────────────────────

  /** A local part of letters only, unique to the test, so its variants are its own. */
  private def letters = unique.map(c => if c.isDigit then ('g' + (c - '0')).toChar else c)
  private def address = s"10.${Random.nextInt(256)}.${Random.nextInt(256)}.${Random.nextInt(256)}"
  private def from(ip: String, email: String, fields: (String, Any)*): String =
    val r = kit.registerFrom(Some(ip), (("email" -> email) +: fields)*)
    assertEquals(r.status, 201, r.text)
    r.str("playerId")
  private def ended(id: String) =
    kit.eventually(s"$id's duplicate check ended", 30.seconds)(player(id).duplicateCheck.filter(!_.inProgress))

  test("the check begins before the match and ends with its outcome; nothing found is clear") {
    val id = registered()
    val c  = ended(id)
    assertEquals(c.outcome, Some(CheckOutcome.Clear))
    assert(!c.endedAt.get.isBefore(c.startedAt), c.toString)
    assertEquals(player(id).noDepositBlock, None)
  }

  test("an email variant from the same address within 24 hours: strong, both flagged, one case naming the variant and the address") {
    val (n, ip) = (letters, address)
    val a = from(ip, s"$n.lovelace@gmail.com")
    val b = from(ip, s"${n}lovelace7@gmail.com")
    assertEquals(ended(b).outcome, Some(CheckOutcome.Strong))
    assertEquals(flagged(a).linkedPlayerIds, Set(b))
    assertEquals(flagged(b).signals, Set(LookupTokens.EmailVariant, LookupTokens.Address))
    val c = kit.eventually("the case", 10.seconds)(FraudCasesView.ofPlayer(kit.views, b).headOption)
    assertEquals((c.caseId, c.playerIds.toSet), (RegistrationWorkflow.caseOf(b), Set(a, b)))
    assertEquals((player(a).noDepositBlock, player(b).noDepositBlock), (None, None))
  }

  test("an email variant alone, or from the same address a day later: weak, only the new player's no-deposit offers blocked") {
    val n = letters
    val a = registered("email" -> s"$n.lovelace@gmail.com")
    val b = registered("email" -> s"$n.lovelace+promo@gmail.com")
    assertEquals(ended(b).outcome, Some(CheckOutcome.Weak))
    assertEquals(player(b).noDepositBlock.map(_.signals), Some(Set(LookupTokens.EmailVariant)))
    assert(player(b).noDepositBlocked)
    assertEquals((player(a).fraud, player(b).fraud, player(a).noDepositBlock), (None, None, None))
    assertEquals(FraudCasesView.ofPlayer(kit.views, b), Vector.empty)

    val (m, ip) = (letters, address)
    from(ip, s"$m.hopper@gmail.com")
    kit.advance(java.time.Duration.ofHours(25))
    val d = from(ip, s"${m}hopper1@gmail.com")
    assertEquals(ended(d).outcome, Some(CheckOutcome.Weak), "the first registration from the address is older than 24 hours")
  }

  test("three registrations from one address and nothing else: clear; a variant from it then is strong by the address's rate") {
    val ip  = address
    val ids = Vector.fill(3)(from(ip, s"p-${Random.nextLong(1L << 52)}@example.test"))
    ids.foreach(id => assertEquals(ended(id).outcome, Some(CheckOutcome.Clear)))
    assert(ids.forall(id => player(id).fraud.isEmpty && player(id).noDepositBlock.isEmpty))
    val n = letters
    val a = from(address, s"$n.babbage@gmail.com")
    val b = from(ip, s"${n}babbage@gmail.com")
    assertEquals(ended(b).outcome, Some(CheckOutcome.Strong))
    assertEquals(flagged(b).signals, Set(LookupTokens.EmailVariant, LookupTokens.AddressRate))
    assertEquals(flagged(a).linkedPlayerIds, Set(b))
    assert(ids.forall(id => player(id).fraud.isEmpty), "the players who share only the address are not linked")
  }

  test("a comparison that fails after its retries: weak, check_failed blocking the new player's no-deposit offers") {
    RegistrationRuntime.lookup = (_, _, _) => throw IllegalStateException("the index is unavailable (suite)")
    val id = registered((identity(s"Fa$unique", "Ure") :+ ("email" -> s"$letters.fails@gmail.com"))*)
    assertEquals(ended(id).outcome, Some(CheckOutcome.Weak))
    assertEquals(player(id).noDepositBlock.map(_.signals), Some(Set(LookupTokens.CheckFailed)))
    assertEquals(player(id).fraud, None)
  }

  test("review: REVIEWED with the operator and the time; dismiss: every linked flag cleared and a fact each; the list by status") {
    val n = unique
    val a = registered(identity(s"Di$n", "Rae")*)
    val b = registered(identity(s"Di$n", "Rae")*)
    val caseId = RegistrationWorkflow.caseOf(b)
    kit.eventually("open listed", 10.seconds)(Option(op("GET", "/fraud-cases?status=OPEN").json.get("cases")).filter(cs =>
      (0 until cs.size).exists(i => cs.get(i).get("caseId").asText == caseId)))
    val r = op("PATCH", s"/fraud-cases/$caseId/review")
    assertEquals((r.status, r.str("status"), r.str("reviewedBy")), (200, "REVIEWED", "op-4"))
    assert(r.str("reviewedAt").nonEmpty)
    assertEquals(op("PATCH", "/fraud-cases/nope/review").status, 404)
    val d = kit.request("PATCH", kit.operatorPath(s"/fraud-cases/$caseId/dismiss"), Some("""{"notes":"siblings"}"""), kit.asOperator(subject = "op-4"))
    assertEquals((d.status, d.str("status"), d.str("reviewNotes")), (200, "DISMISSED", "siblings"))
    assertEquals((player(a).fraud, player(b).fraud), (None, None))
    Seq(a, b).foreach(id => kit.eventually(s"player_fraud_cleared for $id")(kit.facts(CasinoPlayers).collectFirst {
      case f: PlayerFraudCleared if f.playerId == id && f.fraudCaseId == caseId => f
    }))
    assertEquals(kit.components.forEventSourcedEntity(EntityId(caseId)).call(FraudCaseEntity.get).invoke().status, FraudCaseStatus.Dismissed)
  }

  test("clear-suspect-flag: the player's open cases dismissed and every linked flag cleared") {
    val n = unique
    val a = registered(identity(s"Ed$n", "Fay")*)
    val b = registered(identity(s"Ed$n", "Fay")*)
    flagged(a); kit.eventually("the case row", 10.seconds)(FraudCasesView.ofPlayer(kit.views, a).headOption)
    val r = kit.request("POST", kit.operatorPath(s"/players/$a/clear-suspect-flag"), Some("{}"), kit.asOperator())
    assertEquals((r.status, r.json.get("linkedCasesFound").asInt), (200, 1))
    assertEquals((player(a).fraud, player(b).fraud), (None, None))
  }

package io.eitheror.casino.players.it

import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.contract.facts.*
import io.eitheror.casino.players.Json
import io.eitheror.casino.players.config.{BrandConfig, BrandConfigEntity}
import io.eitheror.casino.players.fraud.{FraudCaseEntity, FraudCasesView, FraudCaseStatus}
import io.eitheror.casino.players.player.{PlayerEntity, PlayerState}
import io.eitheror.casino.players.registration.RegistrationWorkflow

import java.util.UUID
import scala.concurrent.duration.*

/** T062: duplicate detection at registration and the operator's review (FR-036, FR-037, SC-005). */
class DuplicatesSuite extends munit.FunSuite:
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
      Thread.sleep(1500)
      assertEquals((player(b).fraud, FraudCasesView.ofPlayer(kit.views, b)), (None, Vector.empty))
    finally config(_.copy(multiAccountDetection = true))
    val before = indexRows
    val bare   = registered()
    Thread.sleep(1500)
    assertEquals((player(bare).fraud, indexRows), (None, before))
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

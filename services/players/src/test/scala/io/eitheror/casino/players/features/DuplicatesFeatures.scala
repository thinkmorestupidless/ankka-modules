package io.eitheror.casino.players.features

import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.contract.facts.*
import io.eitheror.casino.players.config.{BrandConfig, BrandConfigEntity}
import io.eitheror.casino.players.fraud.{FraudCaseEntity, FraudCaseStatus, FraudCasesView}
import io.eitheror.casino.players.player.{PlayerEntity, PlayerState}
import io.eitheror.casino.players.registration.RegistrationWorkflow

import java.util.UUID
import scala.concurrent.duration.*

/** features/players/duplicates.feature (T063). */
class DuplicatesFeatures extends PlayersFeatures("duplicates.feature"):
  protected def pending = Map.empty

  private var first   = ""
  private var second  = ""
  private var caseId  = ""
  private var name    = ""
  private var fp      = ""
  private var indexAt = 0

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    first = ""; second = ""; caseId = ""; name = s"Nia${UUID.randomUUID().toString.take(8)}"; fp = s"fp-${UUID.randomUUID()}"; indexAt = 0

  override def afterEach(context: AfterEach): Unit =
    config(_.copy(multiAccountDetection = true))
    super.afterEach(context)

  private def player(id: String): PlayerState = kit.components.forEventSourcedEntity(PlayerEntity.id(id)).call(PlayerEntity.get).invoke()
  private def registered(fields: (String, Any)*): String =
    val r = kit.register(fields*)
    assertEquals(r.status, 201, r.text)
    r.str("playerId")
  private def identity = Seq[(String, Any)]("firstName" -> name, "lastName" -> "Okoro", "dateOfBirth" -> "1988-04-05")
  private def config(f: BrandConfig => BrandConfig): Unit =
    val ref = kit.components.forKeyValueEntity(EntityId(kit.slug))
    ref.call(BrandConfigEntity.set).invoke(f(ref.call(BrandConfigEntity.get).invoke())): Unit
  private def indexRows: Int =
    io.eitheror.casino.players.it.Kit.query(kit.testKit.jdbcUrl,
      "SELECT count(*) FROM durable_state WHERE persistence_id LIKE 'lookup-index%'").head.toInt
  private def theCase = kit.eventually("the case", 10.seconds)(FraudCasesView.ofPlayer(kit.views, second).find(_.caseId == caseId))
  private def opened(): Unit =
    first = registered(identity*); second = registered(identity*); caseId = RegistrationWorkflow.caseOf(second); theCase: Unit

  Given("brand {string}") { (slug: String) => assertEquals(kit.slug, slug) }

  Given("a player {string} with a name and date of birth") { (_: String) => first = registered(identity*) }
  When("{string} registers with the same name and date of birth") { (_: String) =>
    second = registered(identity*); caseId = RegistrationWorkflow.caseOf(second)
  }
  Given("a player with a device fingerprint") { () => first = registered("deviceFingerprint" -> fp) }
  When("another registers with the same device fingerprint") { () =>
    second = registered("deviceFingerprint" -> fp); caseId = RegistrationWorkflow.caseOf(second)
  }
  Then("both are flagged as suspected duplicates linked to each other") { () =>
    assertEquals(kit.eventually("the first flagged", 10.seconds)(player(first).fraud).linkedPlayerIds, Set(second))
    assertEquals(kit.eventually("the second flagged", 10.seconds)(player(second).fraud).linkedPlayerIds, Set(first))
  }
  Then("a fraud case opens naming the signal {string}") { (signal: String) =>
    assertEquals((theCase.signals, theCase.status), (Vector(signal), FraudCaseStatus.Open))
  }
  Then("a {string} fact is produced for each") { (name: String) =>
    assertEquals(name, "player_fraud_flagged")
    Seq(first, second).foreach(id => kit.eventually(s"$name for $id")(kit.facts(CasinoPlayers).collectFirst {
      case f: PlayerFraudFlagged if f.playerId == id && f.fraudCaseId == caseId => f
    }))
  }

  // the withholding is spec 011's: here the scenario reads the fact rewards consumes, and that it carries what 011 needs
  Given("a flagged player") { () => opened() }
  When("they claim a reward") { () => () }
  Then("the brand's rewards withhold it, from the flag they read on the player's facts") { () =>
    val f = kit.eventually("player_fraud_flagged")(kit.facts(CasinoPlayers).collectFirst {
      case f: PlayerFraudFlagged if f.playerId == second => f
    })
    assertEquals((f.fraudCaseId, f.signal), (caseId, "identity"))
  }

  Given("an open fraud case") { () => opened() }
  When("an operator dismisses it") { () =>
    assertEquals(kit.request("PATCH", kit.operatorPath(s"/fraud-cases/$caseId/dismiss"), Some("{}"), kit.asOperator(subject = "op-8")).status, 200)
  }
  Then("every player it links has the flag cleared") { () => assertEquals((player(first).fraud, player(second).fraud), (None, None)) }
  Then("a {string} fact is produced") { (name: String) =>
    assertEquals(name, "player_fraud_cleared")
    Seq(first, second).foreach(id => kit.eventually(s"$name for $id")(kit.facts(CasinoPlayers).collectFirst {
      case f: PlayerFraudCleared if f.playerId == id && f.fraudCaseId == caseId => f
    }))
  }
  When("an operator reviews it") { () =>
    assertEquals(kit.request("PATCH", kit.operatorPath(s"/fraud-cases/$caseId/review"), Some("{}"), kit.asOperator(subject = "op-8")).status, 200)
  }
  Then("the case is marked reviewed with the operator and the time") { () =>
    val s = kit.components.forEventSourcedEntity(EntityId(caseId)).call(FraudCaseEntity.get).invoke()
    assertEquals((s.status, s.reviewedBy), (FraudCaseStatus.Reviewed, Some("op-8")))
    assert(s.reviewedAt.isDefined)
  }

  Given("the brand's duplicate detection is turned off") { () => config(_.copy(multiAccountDetection = false)); first = registered(identity*) }
  When("a player registers") { () => indexAt = indexRows; second = registered(identity*) }
  Given("a registration without names or date of birth and without a device fingerprint") { () => indexAt = indexRows }
  When("it succeeds") { () => second = registered() }
  Then("nothing is compared") { () =>
    Thread.sleep(1500)
    assertEquals((indexRows, player(second).fraud), (indexAt, None))
  }
  Then("no fraud case opens") { () => assertEquals(FraudCasesView.ofPlayer(kit.views, second), Vector.empty) }

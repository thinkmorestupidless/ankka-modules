package io.eitheror.casino.players.features

import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.contract.facts.*
import io.eitheror.casino.players.config.{BrandConfig, BrandConfigEntity}
import io.eitheror.casino.players.fraud.{FraudCaseEntity, FraudCaseStatus, FraudCasesView}
import io.eitheror.casino.players.player.{CheckOutcome, PlayerEntity, PlayerState}
import io.eitheror.casino.players.registration.{RegistrationRuntime, RegistrationWorkflow}

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

  // ── spec 011 FR-034 to FR-036: the check, the email variant, the address, the no-deposit block ─────

  /**
   * The scenario's email made the test's own: a prefix of letters unique to it before the local part, so
   * its variants stay variants of each other and of nothing an earlier scenario registered.
   */
  private def own(email: String) = s"$prefix$email"
  private var prefix  = ""
  private var address = ""
  private var third   = Vector.empty[String]
  private def from(email: String): String =
    val r = kit.registerFrom(Some(address), "email" -> own(email))
    assertEquals(r.status, 201, r.text)
    r.str("playerId")
  private def ended(id: String) =
    kit.eventually(s"$id's duplicate check ended", 30.seconds)(player(id).duplicateCheck.filter(!_.inProgress))
  private def fact[A](what: String)(pf: PartialFunction[CasinoPlayersFact, A]): A =
    kit.eventually(what)(kit.facts(CasinoPlayers).collectFirst(pf))
  private def setUp(): Unit =
    prefix = UUID.randomUUID().toString.take(8).map(c => if c.isDigit then ('g' + (c - '0')).toChar else c)
    address = s"10.${scala.util.Random.nextInt(256)}.${scala.util.Random.nextInt(256)}.${scala.util.Random.nextInt(256)}"
    third = Vector.empty

  When("a player registers at a brand with duplicate detection on") { () => setUp(); second = registered() }
  Then("the player's duplicate check is in progress until the brand has compared them with its players") { () =>
    val c = ended(second)
    assertEquals(c.outcome, Some(CheckOutcome.Clear))
    assert(!c.endedAt.get.isBefore(c.startedAt), c.toString)
  }
  Then("the start and the end of the check are each a fact") { () =>
    val started = fact("player_duplicate_check_started") { case f: PlayerDuplicateCheckStarted if f.playerId == second => f }
    val done    = fact("player_duplicate_check_ended") { case f: PlayerDuplicateCheckEnded if f.playerId == second => f }
    assertEquals(started.registeredAt, player(second).registeredAt.get)
    assertEquals(done.outcome, CheckOutcome.Clear)
    assert(!done.occurredAt.isBefore(started.occurredAt))
  }

  Given("a player registered with the email {string} from an address") { (email: String) => setUp(); first = from(email) }
  When("another registers with {string} from the same address within a day") { (email: String) =>
    second = from(email); caseId = RegistrationWorkflow.caseOf(second)
  }
  Then("a fraud case opens") { () => assertEquals(theCase.status, FraudCaseStatus.Open) }

  Given("a player registered with the email {string}") { (email: String) => setUp(); first = registered("email" -> own(email)) }
  When("another registers with {string} and nothing else in common") { (email: String) => second = registered("email" -> own(email)) }
  Then("the new player's no-deposit offers are blocked") { () =>
    assert(kit.eventually("the block", 30.seconds)(Option.when(player(second).noDepositBlocked)(true)))
    fact("player_no_deposit_blocked") { case f: PlayerNoDepositBlocked if f.playerId == second => f }: Unit
    assertEquals(fact("player_duplicate_check_ended") { case f: PlayerDuplicateCheckEnded if f.playerId == second => f }.outcome,
      CheckOutcome.Weak)
  }
  Then("neither player is flagged as a suspected duplicate") { () =>
    ended(second): Unit
    assertEquals((player(first).fraud, player(second).fraud, player(first).noDepositBlock), (None, None, None))
  }

  Given("the comparison cannot be made when a player registers") { () =>
    setUp()
    RegistrationRuntime.lookup = (_, _, _) => throw IllegalStateException("the index is unavailable (feature)")
    second = registered((identity :+ ("email" -> own("cannot.compare@gmail.com")))*)
  }
  When("the duplicate check ends") { () => assertEquals(ended(second).outcome, Some(CheckOutcome.Weak)) }

  Given("three players registered from one address within a day, with nothing else in common") { () =>
    setUp()
    third = Vector.fill(3) {
      val r = kit.registerFrom(Some(address))
      assertEquals(r.status, 201, r.text)
      r.str("playerId")
    }
  }
  When("the duplicate check ends for the third") { () => assertEquals(ended(third.last).outcome, Some(CheckOutcome.Clear)) }
  Then("no player is flagged and no offer is blocked") { () =>
    third.foreach(id => assertEquals((ended(id).outcome, player(id).fraud, player(id).noDepositBlock), (Some(CheckOutcome.Clear), None, None)))
  }

  Given("a player whose no-deposit offers are blocked") { () =>
    setUp()
    first = registered("email" -> own("grace.hopper@gmail.com"))
    second = registered("email" -> own("gracehopper1@gmail.com"))
    kit.eventually("the block", 30.seconds)(Option.when(player(second).noDepositBlocked)(())): Unit
  }
  When("an operator with a fraud or compliance role clears the block") { () =>
    val r = kit.request("POST", kit.operatorPath(s"/players/$second/clear-no-deposit-block"), Some("{}"),
      kit.asOperator(roles = s"brand_${kit.slug},fraud_analyst", subject = "op-8"))
    assertEquals((r.status, r.json.get("blockCleared").asBoolean), (200, true), r.text)
  }
  Then("the block is lifted with the operator and the time recorded") { () =>
    val b = player(second).noDepositBlock.get
    assertEquals((b.active, b.clearedBy), (false, Some("op-8")))
    assert(b.clearedAt.isDefined)
  }
  Then("a fact says so") { () =>
    assertEquals(fact("player_no_deposit_block_cleared") { case f: PlayerNoDepositBlockCleared if f.playerId == second => f }.actorId, "op-8")
  }

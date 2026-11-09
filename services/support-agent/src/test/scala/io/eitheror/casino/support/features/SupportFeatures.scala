package io.eitheror.casino.support.features

import com.thinkmorestupidless.ankka.testkit.{GherkinSuite, LogCapturing}
import io.eitheror.casino.support.it.{FakeNeighbours, Kit, SharedKit, SseReader}

import java.util.UUID
import scala.concurrent.duration.*

/**
 * A feature file of features/support-agent bound against the whole service (constitution VII): the routes called as
 * the player and the operator, the model scripted per scenario, the five neighbours as the kit's fakes. Each scenario
 * has a player and a session of its own, since the database outlives scenarios. A step a story has not bound yet
 * fails, so each file is red until its story's tasks land.
 */
abstract class SupportFeatures(file: String) extends GherkinSuite(s"../../features/support-agent/$file") with LogCapturing:
  override val munitTimeout: Duration = 300.seconds

  protected def kit: Kit = SharedKit.kit

  protected var player  = ""
  protected var session = ""
  protected var stream: SseReader = null

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    kit.reset()
    player = s"p-${UUID.randomUUID().toString.take(8)}"
    session = s"s-${UUID.randomUUID().toString.take(8)}"
    stream = null

  protected def brand(slug: String): Unit = assertEquals(slug, FakeNeighbours.Brand)

  Given("brand {string}") { (slug: String) => brand(slug) }

  Given("a signed-in player of brand {string} whose support agent is on") { (slug: String) =>
    brand(slug)
    kit.configure(): Unit
  }

  Given("a signed-in player of brand {string} whose support agent is on, with a wallet in {string}") { (slug: String, currency: String) =>
    brand(slug)
    assertEquals(currency, "GBP", "the fake players answer GBP as the player's currency")
    kit.configure(): Unit
  }

/** features/support-agent/reads.feature (US1). */
class ReadsFeatures extends SupportFeatures("reads.feature")

/** features/support-agent/actions.feature (US2). */
class ActionsFeatures extends SupportFeatures("actions.feature")

/** features/support-agent/protections.feature (US3). */
class ProtectionsFeatures extends SupportFeatures("protections.feature")

/** features/support-agent/conversations.feature (US4). */
class ConversationsFeatures extends SupportFeatures("conversations.feature")

/** features/support-agent/guardrails.feature (US5). */
class GuardrailsFeatures extends SupportFeatures("guardrails.feature")

/** features/support-agent/operators.feature (US6). */
class OperatorsFeatures extends SupportFeatures("operators.feature")

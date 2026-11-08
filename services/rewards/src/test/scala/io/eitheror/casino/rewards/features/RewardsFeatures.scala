package io.eitheror.casino.rewards.features

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.testkit.{GherkinSuite, LogCapturing}
import io.eitheror.casino.rewards.Json
import io.eitheror.casino.rewards.it.{Kit, Reply, SharedKit}
import io.eitheror.casino.rewards.reads.{RewardRow, RewardsView}
import io.eitheror.casino.rewards.reward.{Reward, RewardState}

import java.util.UUID
import scala.concurrent.duration.*

/**
 * A feature file of features/rewards bound against the whole service (constitution VII): the operator's and the
 * player's calls through their routes, the wallet, the provider and players as the kit's fakes, and the wallet's facts
 * published on the kit's broker. Each scenario has a player and offers of its own, since the database outlives
 * scenarios. A scenario a later story binds is reported ignored naming its task (`pending`), never passed: the
 * story's task removes it from `pending` as it binds it.
 */
abstract class RewardsFeatures(file: String) extends GherkinSuite(s"../../features/rewards/$file") with LogCapturing:
  override val munitTimeout: Duration = 300.seconds

  protected def kit: Kit = SharedKit.kit

  protected var player      = ""
  protected var last: Reply = null

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    kit.reset()
    player = newPlayer()
    last = null

  protected def newPlayer(): String = s"p-${UUID.randomUUID().toString.take(8)}"
  protected def json(fields: (String, Any)*): JsonNode = Json.obj(fields*)

  protected def operator: Seq[(String, String)] = kit.asOperator("brand_admin")

  // ── the operator's offers ──────────────────────────────────────────────────────────────────

  protected def createOffer(fields: (String, Any)*): Reply =
    last = kit.json("POST", kit.offersPath(""), json(fields*), operator)
    last

  /** An offer created as an operator would, its id; refused, the scenario fails naming why. */
  protected def offer(fields: (String, Any)*): String =
    val r = createOffer(fields*)
    assertEquals(r.status, 201, r.text)
    r.str("id")

  protected def offers: Vector[JsonNode] =
    val r = kit.request("GET", kit.offersPath(""), None, operator)
    assertEquals(r.status, 200, r.text)
    Json.parse(r.text).toVector.flatMap(n => (0 until n.size).map(n.get))

  // ── the player's rewards ───────────────────────────────────────────────────────────────────

  protected def rows(of: String = player): Vector[RewardRow] = RewardsView.byPlayer(kit.views.forView(RewardsView), of)

  protected def reward(id: String): RewardState = kit.components.forEventSourcedEntity(EntityId(id)).call(Reward.get).invoke()

  /** The player's one reward of the offer, once the view has it. */
  protected def rewardOf(offerId: String, of: String = player): RewardState =
    val id = eventually(s"a reward of $offerId")(rows(of).find(_.offerId == offerId).map(_.rewardId))
    reward(id)

  protected def refusedWith(status: Int, code: String): JsonNode =
    assertEquals((last.status, last.str("error")), (status, code), last.text)
    last.json

  protected def eventually[A](what: String, within: FiniteDuration = 30.seconds)(f: => Option[A]): A = kit.eventually(what, within)(f)

object RewardsFeatures:
  /** Every scenario of `file` named, pending on `task`. */
  def pending(task: String, names: String*): Map[String, String] = names.map(_ -> s"$task (pending)").toMap

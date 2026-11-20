package io.eitheror.casino.rewards.port

import io.eitheror.casino.contract.port.{DescriptionCheck, Recording}
import io.eitheror.casino.contract.port.DescriptionCheck.OriginalRoute
import io.eitheror.casino.rewards.it.Kit
import io.eitheror.casino.rewards.port.WireTranslation.{Context, Ids, Translated}

import java.nio.file.Paths

/**
 * The kept description against the original (spec 031 FR-013). The original's player routes are
 * `/v1/casino/bonus/{brand}/…` and `/promo-code`; each recorded step is read through
 * [[WireTranslation]] onto the route the rebuild serves, with its recorded status. The original's
 * answers are in its own terms (major units, its ids), and the translation runs only from the
 * rebuild's answers to the original's, so here a step's status is checked and its body is not: the
 * bodies are checked where `ParitySuite` replays the same steps through the held kit (`Held`).
 */
class DescriptionSuite extends munit.FunSuite:

  private val root = Paths.get(sys.props.getOrElse("casino.root", "../.."))

  private def route(method: String, rest: String, op: String) =
    OriginalRoute(method, s"/v1/casino/brands/[^/]+/rewards/[^/]+$rest".r, None, s"${method.toLowerCase}_v1_casino_brands_the_brand_rewards_playerId_$op")

  private val routes = Vector(
    route("GET", "/state", "state"), route("POST", "/select", "select"), route("POST", "/deselect", "deselect"),
    route("POST", "/cancel", "cancel"), route("POST", "/switch", "switch"), route("GET", "/history", "history"),
    route("POST", "/codes", "codes"), route("POST", "/codes/preview", "codes_preview"),
    route("GET", "/free-rounds", "free_rounds"), route("GET", "/grants", "grants")
  )

  /** The step on the rebuild's route, with the original's status and no body; a step on no route of the table is skipped. */
  private def read(recording: Recording, step: Recording.Step): Option[Recording.Step] =
    // An operator's offer or grant body holds the recording's `{captures}` where amounts go, which only a replay
    // fills; its route is no player route, and the body steers only those, so it is translated by its path alone.
    def translate(body: Option[com.fasterxml.jackson.databind.JsonNode]) =
      WireTranslation.request(step.request.method, step.request.path, body, Context("spinvibe", "recorded", Ids()))
    scala.util.Try(translate(step.request.body)).getOrElse(translate(None)) match
      case Translated.Ours(r) =>
        Some(step.copy(request = step.request.copy(method = r.method, path = r.path), response = step.response.copy(body = None)))
      case _ => None

  private lazy val result = DescriptionCheck.run(Kit.description, DescriptionCheck.recordings(root), routes, read)

  test("every recorded call to a player route answers a status the description names") {
    assert(result.differences.isEmpty, result.differences.map(d => s"${d.recording} ${d.step} ${d.operationId}: ${d.what}").mkString("\n"))
  }

  test("the routes no recording calls are tests/port/unrecorded-player-routes/rewards.txt") {
    assertEquals(result.unrecorded, DescriptionCheck.unrecordedList(root, "rewards"))
  }

  test("the original's player calls are found") {
    assert(result.matched >= 40, s"matched ${result.matched}")
  }

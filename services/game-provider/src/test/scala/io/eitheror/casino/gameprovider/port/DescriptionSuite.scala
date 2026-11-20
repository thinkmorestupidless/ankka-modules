package io.eitheror.casino.gameprovider.port

import io.eitheror.casino.contract.port.DescriptionCheck
import io.eitheror.casino.contract.port.DescriptionCheck.OriginalRoute
import io.eitheror.casino.gameprovider.it.Kit

import java.nio.file.Paths

/**
 * The kept description against the original (spec 031 FR-013). The original's launch is the path
 * the rebuild keeps, served from its `players` and `casino` hosts, and recorded in five directories
 * (engagement, game-provider, kyc, players, public-api, reporting, rewards); every recording is read
 * as recorded, as `ParitySuite` reads them. The lobby's reads are not recorded.
 */
class DescriptionSuite extends munit.FunSuite:

  private val root = Paths.get(sys.props.getOrElse("casino.root", "../.."))

  private val routes = Vector(
    OriginalRoute("POST", "/v1/casino/sessions/[^/]+/launch".r, None, "post_v1_casino_sessions_brand_launch"),
    OriginalRoute("GET", "/v1/casino/catalog/games".r, None, "get_v1_casino_catalog_games"),
    OriginalRoute("GET", "/v1/casino/catalog/games/[^/]+".r, None, "get_v1_casino_catalog_games_gameCode"),
    OriginalRoute("GET", "/v1/casino/catalog/developers".r, None, "get_v1_casino_catalog_developers"),
    OriginalRoute("GET", "/v1/casino/catalog/[^/]+/games/popular".r, None, "get_v1_casino_catalog_brand_games_popular")
  )

  private lazy val result =
    DescriptionCheck.run(Kit.description, DescriptionCheck.recordings(root), routes, (_, step) => Some(step))

  test("every recorded call to a player route answers what the description names") {
    assert(result.differences.isEmpty, result.differences.map(d => s"${d.recording} ${d.step} ${d.operationId}: ${d.what}").mkString("\n"))
  }

  test("the routes no recording calls are tests/port/unrecorded-player-routes/game-provider.txt") {
    assertEquals(result.unrecorded, DescriptionCheck.unrecordedList(root, "game-provider"))
  }

  test("the original's launches are found") {
    assert(result.matched >= 37, s"matched ${result.matched}")
  }

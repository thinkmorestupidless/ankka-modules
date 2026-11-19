package io.eitheror.casino.rg.port

import io.eitheror.casino.contract.port.DescriptionCheck
import io.eitheror.casino.contract.port.DescriptionCheck.OriginalRoute
import io.eitheror.casino.rg.it.Kit

import java.nio.file.Paths

/**
 * The kept description against the original (spec 031 FR-013, V4). The service has no parity
 * package: the original served these routes from its `players` service (the host `players`, and
 * `casino` in reporting's recording), under the paths the rebuild keeps, and the recordings that
 * call them are engagement's and reporting's. Every recording under `tests/port/` is read as
 * recorded; a path under `/v1/casino/rg/<brand>/` names this service whatever its host.
 */
class DescriptionSuite extends munit.FunSuite:

  private val root = Paths.get(sys.props.getOrElse("casino.root", "../.."))

  private def route(method: String, rest: String, id: String) =
    OriginalRoute(method, s"/v1/casino/rg/[^/]+/[^/]+$rest".r, None, s"${method.toLowerCase}_v1_casino_rg_the_brand_player_$id")

  private val routes = Vector(
    route("GET", "/limits", "limits"),
    route("PUT", "/limits/[^/]+", "limits_type"),
    route("DELETE", "/limits/[^/]+", "limits_type"),
    route("PUT", "/cooling-off", "cooling_off"),
    route("PUT", "/permanent-exclusion", "permanent_exclusion"),
    route("POST", "/return", "return"),
    route("GET", "/history", "history"),
    route("PUT", "/reality-check", "reality_check"),
    route("DELETE", "/reality-check", "reality_check"),
    route("POST", "/reality-check/acknowledge", "reality_check_acknowledge")
  )

  private lazy val result =
    DescriptionCheck.run(Kit.description, DescriptionCheck.recordings(root), routes, (_, step) => Some(step))

  test("every recorded call to a player route answers what the description names") {
    assert(result.differences.isEmpty, result.differences.map(d => s"${d.recording} ${d.step} ${d.operationId}: ${d.what}").mkString("\n"))
  }

  test("the routes no recording calls are tests/port/unrecorded-player-routes/responsible-gambling.txt") {
    assertEquals(result.unrecorded, DescriptionCheck.unrecordedList(root, "responsible-gambling"))
  }

  test("the original's calls are found: engagement's and reporting's") {
    assert(result.matched >= 40, s"matched ${result.matched}")
  }

package io.eitheror.casino.engagement.port

import io.eitheror.casino.contract.port.DescriptionCheck
import io.eitheror.casino.contract.port.DescriptionCheck.OriginalRoute
import io.eitheror.casino.engagement.it.Kit

import java.nio.file.Paths

/**
 * The kept description against the original (spec 031 FR-013). The push connection is the original's
 * `/v1/stream` on its `push` host, the path the rebuild keeps; a stream's body is not read, so each
 * recorded opening is checked for its status only.
 */
class DescriptionSuite extends munit.FunSuite:

  private val root = Paths.get(sys.props.getOrElse("casino.root", "../..")).toAbsolutePath.normalize

  private val routes = Vector(OriginalRoute("GET", "/v1/stream/?".r, Some("push"), "get_v1_stream"))

  private lazy val result = DescriptionCheck.run(Kit.description, DescriptionCheck.recordings(root), routes,
    (_, step) => Some(step.copy(response = step.response.copy(body = None))))

  test("every recorded opening of the push connection answers a status the description names") {
    assert(result.differences.isEmpty, result.differences.map(d => s"${d.recording} ${d.step} ${d.operationId}: ${d.what}").mkString("\n"))
  }

  test("the routes no recording calls are tests/port/unrecorded-player-routes/engagement.txt") {
    assertEquals(result.unrecorded, DescriptionCheck.unrecordedList(root, "engagement"))
  }

  test("the original's openings are found") {
    assert(result.matched >= 6, s"matched ${result.matched}")
  }

package io.eitheror.casino.support.port

import io.eitheror.casino.contract.port.DescriptionCheck
import io.eitheror.casino.contract.port.DescriptionCheck.OriginalRoute
import io.eitheror.casino.support.it.Kit

import java.nio.file.Paths

/**
 * The kept description against the original (spec 031 FR-013). The original's agent served
 * `/run/chat_with_player` and `/resume/{session}` on its `agent` host; the rebuild serves them under
 * `/v1/casino/agent/<slug>`. Both are streams, so each recorded step is checked for its status only.
 */
class DescriptionSuite extends munit.FunSuite:

  private val root = Paths.get(sys.props.getOrElse("casino.root", "../..")).toAbsolutePath.normalize

  private val routes = Vector(
    OriginalRoute("POST", "/run/[^/]+".r, Some("agent"), "post_v1_casino_agent_the_brand_run_route"),
    OriginalRoute("POST", "/resume/[^/]+".r, Some("agent"), "post_v1_casino_agent_the_brand_resume_session")
  )

  private lazy val result = DescriptionCheck.run(Kit.description, DescriptionCheck.recordings(root), routes,
    (_, step) => Some(step.copy(response = step.response.copy(body = None))))

  test("every recorded turn and decision answers a status the description names") {
    assert(result.differences.isEmpty, result.differences.map(d => s"${d.recording} ${d.step} ${d.operationId}: ${d.what}").mkString("\n"))
  }

  test("the routes no recording calls are tests/port/unrecorded-player-routes/support-agent.txt") {
    assertEquals(result.unrecorded, DescriptionCheck.unrecordedList(root, "support-agent"))
  }

  test("the original's turns and decisions are found") {
    assert(result.matched >= 30, s"matched ${result.matched}")
  }

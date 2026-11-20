package io.eitheror.casino.kyc.port

import io.eitheror.casino.contract.port.{DescriptionCheck, Recording}
import io.eitheror.casino.contract.port.DescriptionCheck.OriginalRoute
import io.eitheror.casino.kyc.it.Kit
import io.eitheror.casino.kyc.port.WireTranslation.Context

import java.nio.file.Paths

/**
 * The kept description against the original (spec 031 FR-013). The original's player routes are
 * `/v1/casino/{brand}/players/{id}/kyc/…`; each recorded step is read through [[WireTranslation]]
 * onto the route the rebuild serves, with its recorded status. The original's answers are in its own
 * terms (`docType`, `kycStatus`), and the translation runs only from the rebuild's answers to the
 * original's, so here a step's status is checked and its body is not: the bodies are checked where
 * `ParitySuite` replays the same steps through the held kit (`Held`), the rebuild's own answers
 * against the description.
 */
class DescriptionSuite extends munit.FunSuite:

  private val root = Paths.get(sys.props.getOrElse("casino.root", "../.."))

  private val routes = Vector(
    OriginalRoute("GET", "/v1/casino/kyc/[^/]+/[^/]+/documents".r, None, "get_v1_casino_kyc_the_brand_playerId_documents"),
    OriginalRoute("POST", "/v1/casino/kyc/[^/]+/[^/]+/uploads".r, None, "post_v1_casino_kyc_the_brand_playerId_uploads"),
    OriginalRoute("POST", "/v1/casino/kyc/[^/]+/[^/]+/documents".r, None, "post_v1_casino_kyc_the_brand_playerId_documents"),
    OriginalRoute("GET", "/v1/casino/kyc/[^/]+/[^/]+/views/.+".r, None, "get_v1_casino_kyc_the_brand_playerId_views_documentId"),
    OriginalRoute("GET", "/v1/casino/kyc-requirements/[^/]+".r, None, "get_v1_casino_kyc_requirements_the_brand")
  )

  /** The step on the rebuild's route, with the original's status and no body; a step on no route of the table is skipped. */
  private def read(recording: Recording, step: Recording.Step): Option[Recording.Step] =
    val brand = step.request.path.split('/').lift(3).getOrElse("spinvibe")
    WireTranslation.request(step.request.method, step.request.path, step.request.body, Context(brand)).map { r =>
      step.copy(request = step.request.copy(method = r.method, path = r.path), response = step.response.copy(body = None))
    }

  private lazy val result = DescriptionCheck.run(Kit.description, DescriptionCheck.recordings(root), routes, read)

  test("every recorded call to a player route answers a status the description names") {
    assert(result.differences.isEmpty, result.differences.map(d => s"${d.recording} ${d.step} ${d.operationId}: ${d.what}").mkString("\n"))
  }

  test("the routes no recording calls are tests/port/unrecorded-player-routes/kyc.txt") {
    assertEquals(result.unrecorded, DescriptionCheck.unrecordedList(root, "kyc"))
  }

  test("the original's player calls are found") {
    assert(result.matched >= 100, s"matched ${result.matched}")
  }

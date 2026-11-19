package io.eitheror.casino.ingest.port

import io.eitheror.casino.contract.port.DescriptionCheck
import io.eitheror.casino.contract.port.DescriptionCheck.OriginalRoute
import io.eitheror.casino.ingest.it.Kit

import java.nio.file.Paths

/**
 * The kept description against the original (spec 031 FR-013). The original's `merchant` served the
 * ticket's routes under the paths ingest keeps (the host `merchant`), and its recordings are under
 * `tests/port/ingest/` and `tests/port/merchant/`; every recording is read as recorded, as
 * `ParitySuite` reads them (no translation: ingest passes merchant's answers through as sent).
 */
class DescriptionSuite extends munit.FunSuite:

  private val root = Paths.get(sys.props.getOrElse("casino.root", "../.."))

  private val routes = Vector(
    OriginalRoute("POST", "/v1/cashier/sessions/[^/]+/submit".r, Some("merchant"), "post_v1_cashier_sessions_ticket_submit"),
    OriginalRoute("POST", "/v1/cashier/sessions/[^/]+/abandon".r, Some("merchant"), "post_v1_cashier_sessions_ticket_abandon"),
    OriginalRoute("GET", "/v1/payments/[^/]+".r, Some("merchant"), "get_v1_payments_paymentId")
  )

  private lazy val result =
    DescriptionCheck.run(Kit.description, DescriptionCheck.recordings(root), routes, (_, step) => Some(step))

  test("every recorded call to a ticket's route answers what the description names") {
    assert(result.differences.isEmpty, result.differences.map(d => s"${d.recording} ${d.step} ${d.operationId}: ${d.what}").mkString("\n"))
  }

  test("the routes no recording calls are tests/port/unrecorded-player-routes/ingest.txt") {
    assertEquals(result.unrecorded, DescriptionCheck.unrecordedList(root, "ingest"))
  }

  test("the original's calls are found") {
    assert(result.matched >= 60, s"matched ${result.matched}")
  }

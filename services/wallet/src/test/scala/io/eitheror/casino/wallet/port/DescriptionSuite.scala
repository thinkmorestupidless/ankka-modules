package io.eitheror.casino.wallet.port

import io.eitheror.casino.contract.port.DescriptionCheck
import io.eitheror.casino.contract.port.DescriptionCheck.OriginalRoute
import io.eitheror.casino.wallet.it.Harness

import java.nio.file.Paths

/**
 * The wallet's kept description against the original (spec 031 FR-013, FR-016): every recording
 * under `tests/port/` is read, and a recorded call to a player route must answer what the
 * description names. `PortSuite` replays the original's wallet routes through `WireTranslation`;
 * none of those is a player route, and no recording calls one: the original's player site read
 * the balance, the restricted funds and the transactions through the twin API, which the rebuild
 * does not have. So the three routes are listed as unrecorded, and the description's answers are
 * held by the kit (`it.Harness`) instead.
 */
class DescriptionSuite extends munit.FunSuite:

  private val root = Paths.get(sys.props.getOrElse("casino.root", "../.."))

  /** The original's paths for the wallet's player routes: the rebuild's, under the host `wallet`. */
  private val routes = Vector(
    OriginalRoute("GET", "/v1/casino/players/[^/]+/wallets/[^/]+/balance".r, Some("wallet"),
      "get_v1_casino_players_the_brand_wallets_currency_balance"),
    OriginalRoute("GET", "/v1/casino/players/[^/]+/wallets/[^/]+/restricted-funds".r, Some("wallet"),
      "get_v1_casino_players_the_brand_wallets_currency_restricted_funds"),
    OriginalRoute("GET", "/v1/casino/players/[^/]+/wallets/[^/]+/transactions".r, Some("wallet"),
      "get_v1_casino_players_the_brand_wallets_currency_transactions")
  )

  private lazy val result =
    DescriptionCheck.run(Harness.description, DescriptionCheck.recordings(root), routes, (_, step) => Some(step))

  test("every recorded call to a player route answers what the description names") {
    assert(result.differences.isEmpty, result.differences.map(d => s"${d.recording} ${d.step} ${d.operationId}: ${d.what}").mkString("\n"))
  }

  test("the routes no recording calls are tests/port/unrecorded-player-routes/wallet.txt") {
    assertEquals(result.unrecorded, DescriptionCheck.unrecordedList(root, "wallet"))
  }

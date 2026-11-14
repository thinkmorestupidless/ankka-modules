package io.eitheror.casino.publicapi.port

import com.thinkmorestupidless.ankka.testkit.LogCapturing

/**
 * One suite per recording of contracts/recording.md (constitution I; T092), each replaying its recording through
 * `Parity` against the seed's world. A recording not yet made skips its suite and names it, so the service builds
 * before tests/port/public-api is recorded.
 */
abstract class RecordingParitySuite(recording: String) extends munit.FunSuite with LogCapturing:
  override val munitTimeout = scala.concurrent.duration.Duration(600, "s")

  test(s"$recording: the service answers as the original did") {
    assume(Parity.present(Parity.SeedName), s"${Parity.directory}/${Parity.SeedName}.json is not recorded (T091: just public-api-record)")
    assume(Parity.present(recording), s"${Parity.directory}/$recording.json is not recorded (T091: just public-api-record)")
    val (world, a, b) = Parity.shared.get
    val differences   = Parity.replay(Parity.load(recording), world, a, b, Parity.runStats)
    assert(differences.isEmpty, differences.map(_.message).mkString("\n"))
  }

class WalkParitySuite          extends RecordingParitySuite("walk-two-brands")
class IsolationParitySuite     extends RecordingParitySuite("other-brand-ids")
class PagingParitySuite        extends RecordingParitySuite("transactions-paged")
class BoundariesParitySuite    extends RecordingParitySuite("boundaries")
class FieldsParitySuite        extends RecordingParitySuite("identifying-fields")
class GamesParitySuite         extends RecordingParitySuite("games")
class GameStatsParitySuite     extends RecordingParitySuite("game-stats")
class PlayerRankingParitySuite extends RecordingParitySuite("player-ranking")
class PlayerStatsParitySuite   extends RecordingParitySuite("player-stats")
class QuotaParitySuite         extends RecordingParitySuite("quota")
class ContractParitySuite      extends RecordingParitySuite("contract")

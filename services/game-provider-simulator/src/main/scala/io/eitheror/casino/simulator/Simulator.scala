package io.eitheror.casino.simulator

import com.thinkmorestupidless.ankka.runtime.ViewClient
import com.thinkmorestupidless.ankka.sdk.ComponentClient
import io.eitheror.casino.simulator.brands.BrandTable
import io.eitheror.casino.simulator.core.Play
import io.eitheror.casino.simulator.st8.{Catalogue, Keys}

import java.util.concurrent.atomic.AtomicBoolean

/** What every endpoint of the simulator works with: the components, the brand table, the catalogue, the keys. */
final class Simulator(val client: ComponentClient, val views: ViewClient, val table: BrandTable, val catalogue: Catalogue, val keys: Keys):
  val play: Play = Play(client)

  /** Set once the key is held, the scenarios seeded and every original adapter told the key (research R8). */
  val ready: AtomicBoolean = AtomicBoolean(false)

package io.eitheror.casino.exchange.it

import io.eitheror.casino.exchange.ExchangeRuntime
import io.eitheror.casino.exchange.ports.{Gate, Neighbours}

/**
 * R3's open question (T017): the ports are built before the service starts and are called from the
 * placement's step thread, so they reach the clients the running service holds. A call through them
 * from outside any component's context is answered.
 */
class ServiceClientsSuite extends munit.FunSuite:
  private def kit = SharedKit.kit

  test("the running service's clients answer a call made outside a component's context") {
    kit.reset()
    kit.gates.status.put("eve", "blocked")
    assertEquals(Neighbours(ExchangeRuntime.services, kit.slug).account("eve"), Gate.Refused("blocked"))
    assertEquals(kit.gates.asked("eve"), Vector("account"))
  }

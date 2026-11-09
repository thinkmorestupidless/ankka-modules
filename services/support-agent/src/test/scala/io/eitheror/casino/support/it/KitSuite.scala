package io.eitheror.casino.support.it

/** The kit itself (T021): the service starts, answers its health, refuses with the agent off, and streams a turn. */
class KitSuite extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(120, "s")
  private lazy val kit = SharedKit.kit

  override def beforeEach(context: BeforeEach): Unit = kit.reset()

  test("the health route answers the project's services"):
    val r = kit.request("GET", "/v1/casino/agent-service/goldvibe/health", headers = kit.asService("players"))
    assertEquals((r.status, r.str("status")), (200, "ok"))

  test("the agent off is 503 agent_unavailable, before any model call"):
    kit.configure(_.copy(enabled = false)): Unit
    val s = kit.chat("p-1", "kit-off", "hello")
    assertEquals((s.status, s.refusal), (503, "agent_unavailable"))
    assertEquals(kit.model.callCount, 0)

  test("a turn streams the model's text as delta, then done, and loads the context from the fakes by identity, with no player token"):
    kit.configure(): Unit
    kit.model.expectText("Hi Ann.")
    val s = kit.chat("p-1", "kit-turn", "hello")
    assertEquals(s.status, 200)
    assertEquals(s.names().distinct, Vector("delta", "done"))
    assertEquals(s.text(), "Hi Ann.")
    assertEquals(kit.players.calls("/players/p-1/status").map(kit.players.bearer), Vector(None))
    assertEquals(kit.wallet.calls("/wallets/p-1/GBP/balance").size, 1)
    assertEquals(kit.conversation("kit-turn").playerId, "p-1")

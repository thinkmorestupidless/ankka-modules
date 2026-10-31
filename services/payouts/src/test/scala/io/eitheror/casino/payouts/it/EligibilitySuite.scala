package io.eitheror.casino.payouts.it

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.contract.money.Currency
import io.eitheror.casino.contract.personal.Personal
import io.eitheror.casino.payouts.Json.*
import io.eitheror.casino.payouts.clients.*
import io.eitheror.casino.payouts.player.*
import io.eitheror.casino.payouts.terminals.*

import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * The catalogue and the listing over the whole service (R5, SC-007). A catalogue of its own slug per test
 * keeps the brand's refresh timer out of the counts.
 */
class EligibilitySuite extends munit.FunSuite with LogCapturing:
  private lazy val kit = SharedKit.kit
  override def munitTimeout = scala.concurrent.duration.Duration(300, "s")
  override def beforeEach(context: BeforeEach): Unit = kit.reset()

  private def bank(id: String, name: String = "") = Gateway.terminal(FakeGateway.terminal(id, "unityfinance", name = name)).get

  /** The gateway's listing per currency, counting each read; nothing else is asked of it here. */
  private final class Listing(lists: Map[String, Vector[Terminal]]) extends Gateway:
    val reads = AtomicInteger()
    def terminals(currency: Currency): Either[String, Vector[Terminal]] =
      reads.incrementAndGet(): Unit
      lists.get(currency.code).toRight("no listing")
    def initiate(request: PayoutRequest): Initiation = throw UnsupportedOperationException("not asked here")
    def status(requestId: String): PayoutStatus      = throw UnsupportedOperationException("not asked here")

  private def slug() = s"cat-${UUID.randomUUID().toString.take(8)}"
  private def held(s: String) = kit.components.forKeyValueEntity(EntityId(s)).call(TerminalCatalogue.get).invoke()

  test("a cold catalogue reads the gateway once, then answers from what it holds"):
    val s       = slug()
    val gateway = Listing(Map("EUR" -> Vector(bank("t-1"))))
    val reads = (1 to 5).map(_ => Catalogue.read(kit.components, gateway, s, Currency.unsafe("EUR"), kit.now()))
    assertEquals(reads.map(_.map(_.id)).distinct, Vector(Vector("t-1")))
    assertEquals(gateway.reads.get, 1)
    assertEquals(held(s).of("EUR").map(_.map(_.id)), Some(Vector("t-1")))

  test("a cold catalogue the gateway cannot list answers none, and stores nothing"):
    val s = slug()
    assertEquals(Catalogue.read(kit.components, Listing(Map.empty), s, Currency.unsafe("EUR"), kit.now()), Vector.empty)
    assertEquals(held(s).of("EUR"), None)

  test("a refresh replaces one currency; one the gateway cannot list keeps its last"):
    val s      = slug()
    val entity = kit.components.forKeyValueEntity(EntityId(s))
    entity.call(TerminalCatalogue.refresh).invoke(Refresh("EUR", Vector(bank("t-1")), kit.now())): Unit
    entity.call(TerminalCatalogue.refresh).invoke(Refresh("GBP", Vector(bank("t-2")), kit.now())): Unit
    val gateway = Listing(Map("EUR" -> Vector(bank("t-3"))))
    Catalogue.refreshes(held(s), gateway.terminals, kit.now()).foreach(r => entity.call(TerminalCatalogue.refresh).invoke(r): Unit)
    assertEquals((held(s).of("EUR").map(_.map(_.id)), held(s).of("GBP").map(_.map(_.id))), (Some(Vector("t-3")), Some(Vector("t-2"))))

  test("SC-007: the listing's order is the same on 1,000 reads"):
    val p = s"el-${UUID.randomUUID().toString.take(8)}"
    val record = kit.components.forEventSourcedEntity(EntityId(p))
    record.call(PlayerPayouts.seeNames).invoke(SeeNames(Personal("Ana"), Personal("Silva"), kit.now())): Unit
    record.call(PlayerPayouts.seeDeposit).invoke(SeeDeposit("cozeni", Some("USDT"), kit.now())): Unit
    val rows = Vector(
      FakeGateway.terminal("t-b", "unityfinance", name = "Bank"),
      FakeGateway.terminal("t-a", "unityfinance", name = "Bank"),
      FakeGateway.terminal("t-c", "cozeni", name = "Zcoin", scheme = "USDT", coins = Vector("USDT")),
      FakeGateway.terminal("t-d", "paymentiq", name = "Alpha"))
    kit.gateway.terminals("EUR", rows*)
    kit.components.forKeyValueEntity(EntityId(kit.slug)).call(TerminalCatalogue.refresh)
      .invoke(Refresh("EUR", rows.flatMap(Gateway.terminal), kit.now())): Unit
    val orders = (1 to 1000).map { _ =>
      val r = kit.request("GET", kit.playerPath(s"/$p/withdrawal-terminals?currency=EUR"), None, kit.asPlayer(p))
      r.json.objects("terminals").flatMap(_.text("id"))
    }.distinct
    assertEquals(orders, Vector(Vector("t-c", "t-d", "t-a", "t-b")))

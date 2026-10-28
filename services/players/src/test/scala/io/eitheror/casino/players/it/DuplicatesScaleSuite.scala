package io.eitheror.casino.players.it

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.players.fraud.FraudCasesView

import java.util.UUID
import java.util.concurrent.{ConcurrentHashMap, Executors, TimeUnit}
import scala.concurrent.duration.*

/**
 * T062's scale case (SC-005): with many players already registered through the route, so the lookup
 * index holds their tokens, a matching registration is flagged within 10 s. The match reads the index
 * by token, never a scan, so the time does not grow with the brand.
 *
 * 500 players by default; 10,000 with `PLAYERS_PERF=1` (`just players-perf`). The figures are printed.
 */
class DuplicatesScaleSuite extends munit.FunSuite with LogCapturing:
  private val perf = sys.env.get("PLAYERS_PERF").contains("1")
  private val size = if perf then 10_000 else 500
  override val munitTimeout = (if perf then 30 else 3).minutes
  private lazy val kit = SharedKit.kit

  override def beforeEach(context: BeforeEach): Unit = kit.reset()

  private def indexRows: Int =
    Kit.query(kit.testKit.jdbcUrl, "SELECT count(*) FROM durable_state WHERE persistence_id LIKE 'lookup-index%'").head.toInt

  test(s"$size players registered through the route, then a matching registration: the case within 10 s") {
    val run     = UUID.randomUUID().toString.take(6)
    val before  = indexRows
    val ids     = ConcurrentHashMap[Int, String]()
    val pool    = Executors.newFixedThreadPool(8)
    val started = System.nanoTime()
    (0 until size).foreach { i =>
      pool.execute { () =>
        val r = kit.register("firstName" -> s"Bulk$run$i", "lastName" -> "Row", "dateOfBirth" -> "1970-01-01")
        if r.status == 201 then ids.put(i, r.str("playerId")): Unit
        else System.err.println(s"registration $i: ${r.status} ${r.text}")
      }
    }
    pool.shutdown()
    assert(pool.awaitTermination(munitTimeout.toMinutes, TimeUnit.MINUTES), "the registrations did not finish")
    val took = (System.nanoTime() - started) / 1_000_000
    val rows = kit.eventually("the index filled", 60.seconds)(Option.when(indexRows - before >= size)(indexRows - before))
    println(f"duplicates at scale: $size%,d registrations in $took%,d ms, $rows%,d index entries")

    val target = size / 2
    assertEquals(ids.size, size, "every registration answered 201")
    val first  = ids.get(target)
    val r      = kit.register("firstName" -> s" bulk$run$target ", "lastName" -> "ROW", "dateOfBirth" -> "1970-01-01")
    assertEquals(r.status, 201, r.text)
    val matched = System.nanoTime()
    val c = kit.eventually("the case", 10.seconds)(FraudCasesView.ofPlayer(kit.views, r.str("playerId")).headOption)
    println(f"duplicates at scale: the case ${(System.nanoTime() - matched) / 1_000_000}%,d ms after the matching registration")
    assertEquals((c.playerIds.toSet, c.signals), (Set(first, r.str("playerId")), Vector("identity")))
  }

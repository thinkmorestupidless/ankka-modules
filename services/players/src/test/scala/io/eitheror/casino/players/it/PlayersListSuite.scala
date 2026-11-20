package io.eitheror.casino.players.it

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.contract.personal.Personal
import io.eitheror.casino.players.player.{PlayerRow, Status}

import java.util.UUID
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * Spec 030 P2: the operator's player list as a view query. The paging's edges, a filter's bad value, and a
 * real player's sign-in and erasure reaching the filters through the view. 10,000 rows are
 * lists.feature's first scenario; here the time a page and the counts take at that size is printed.
 */
class PlayersListSuite extends munit.FunSuite with LogCapturing:
  override val munitTimeout = 3.minutes
  private lazy val kit = SharedKit.kit

  override def beforeEach(context: BeforeEach): Unit = kit.reset()

  private def list(q: String) = kit.request("GET", kit.operatorPath(s"/players?$q"), None, kit.asOperator())
  private def ids(r: Reply)   = r.json.get("items").elements().asScala.map(_.get("playerId").asText).toVector

  private def rows(tag: String, n: Int) =
    (0 until n).map(i => PlayerRow(UUID.randomUUID().toString, Status.Active, displayName = Personal(s"$tag $i"),
      country = Some("BR"), currency = "BRL", registeredAt = 1_700_000_000_000L + i)).toVector

  test("page and pageSize are answered back; out of range they are their defaults; past the last page is empty") {
    val tag = s"Edge${UUID.randomUUID().toString.take(8)}"
    Rows.players(kit, rows(tag, 30))
    val first = list(s"search=$tag&page=0&pageSize=500")
    assertEquals((first.status, first.json.get("page").asInt, first.json.get("pageSize").asInt, first.json.get("total").asInt), (200, 1, 25, 30))
    assertEquals(ids(first).size, 25)
    val second = list(s"search=$tag&page=2")
    assertEquals(ids(second).size, 5)
    assertEquals((ids(first) ++ ids(second)).distinct.size, 30)
    assertEquals(ids(list(s"search=$tag&page=3")), Vector.empty)
    // a value no filter takes is no filter
    assertEquals(list(s"search=$tag&identity=pending&online=maybe&birthdayWithinDays=400").json.get("total").asInt, 30)
    assert(!list(s"search=$tag").json.has("facets"))
  }

  test("a player who signs in is online and verified once kyc says so; erased, they match no birthday and are offline") {
    val (email, id) = kit.activePlayer("dateOfBirth" -> s"1990-${java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString.drop(5)}")
    def total(q: String) = list(s"search=$id&$q").json.get("total").asInt
    assertEquals(total("online=true"), 0)
    kit.signIn(email): Unit
    kit.eventually("online", 10.seconds)(Option.when(total("online=true") == 1)(()))
    assertEquals(total("birthdayWithinDays=0"), 1)
    assertEquals(total("identity=unverified"), 1)
    kit.components.forEventSourcedEntity(io.eitheror.casino.players.player.PlayerEntity.id(id))
      .call(io.eitheror.casino.players.player.PlayerEntity.kycVerified).invoke(io.eitheror.casino.players.player.At(kit.now())): Unit
    kit.eventually("verified", 10.seconds)(Option.when(total("identity=verified") == 1)(()))
  }

  test("10,000 players: a page, the total and the counts, each timed") {
    val tag = s"Scale${UUID.randomUUID().toString.take(8)}"
    Rows.players(kit, rows(tag, 10_000))
    def timed[A](what: String)(f: => A): A =
      val t = System.nanoTime(); val a = f
      println(f"players list at 10,000: $what in ${(System.nanoTime() - t) / 1_000_000}%,d ms"); a
    val last = timed("the last page")(list(s"search=$tag&page=50&pageSize=200"))
    assertEquals((last.json.get("total").asInt, ids(last).size), (10_000, 200))
    val counted = timed("a page with its counts")(list(s"search=$tag&pageSize=200&facets=true"))
    assertEquals(counted.json.get("facets").get("country").get("BR").asInt, 10_000)
    assertEquals(timed("a filtered page")(list(s"search=$tag&currency=BRL&country=BR&online=false")).json.get("total").asInt, 10_000)
  }

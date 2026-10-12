package io.eitheror.casino.gameprovider.it

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.gameprovider.{Json, Metrics}
import io.eitheror.casino.gameprovider.Json.*
import io.eitheror.casino.gameprovider.catalogue.*
import io.eitheror.casino.gameprovider.features.SharedKit
import io.eitheror.casino.gameprovider.provider.BonusStatus
import io.eitheror.casino.gameprovider.views.Unacknowledged

import java.time.{Duration as JDuration, LocalDate}
import java.util.UUID
import scala.collection.mutable
import scala.concurrent.duration.*

abstract class SharedKitSuite extends munit.FunSuite with LogCapturing:
  override val munitTimeout: Duration = 300.seconds
  protected def kit: Kit = SharedKit.kit
  protected var run      = ""
  override def beforeEach(context: BeforeEach): Unit =
    kit.reset()
    assertEquals(kit.configureSt8().status, 204)
    run = UUID.randomUUID().toString.take(8)

/** The unacknowledged view and its gauge (FR-020, FR-029). */
class UnacknowledgedViewSuite extends SharedKitSuite:

  test("two failed callbacks of one round are one row with two open entries; a clear leaves one; the gauge counts them") {
    val player = s"$run-u"
    kit.wallet.set(player, "EUR", 1000)
    val token = kit.sessionTicket(player)
    val round = s"$run-r"
    assertEquals(kit.callback("debit", Json.obj("token" -> token, "transaction_id" -> s"$round-d", "round" -> round, "amount" -> "1.00")).word, "ok")
    kit.wallet.down = true
    assertEquals(kit.callback("debit", Json.obj("token" -> token, "transaction_id" -> s"$round-d2", "round" -> round, "amount" -> "1.00")).word, "error")
    assertEquals(kit.callback("credit", Json.obj("token" -> token, "transaction_id" -> s"$round-c", "round" -> round, "amount" -> "2.00")).word, "error")
    kit.wallet.down = false
    val view = kit.testKit.service.viewClient.forView(Unacknowledged)
    val row  = kit.eventually("two open entries")(view.get(s"st8/$round").filter(_.openCount == 2))
    assertEquals(row.entries.map(_.callback).toSet, Set("debit", "credit"))
    assertEquals(row.playerId, player)

    def gauge() =
      val text = kit.request("GET", s"/v1/casino/backoffice/${kit.brand}/game-provider/metrics", None, kit.asOperator()).text
      text.linesIterator.find(_.startsWith(Metrics.Unacknowledged + "{")).map(_.split(' ').last.toLong).get
    val before = gauge()
    assert(before >= 2)

    assertEquals(kit.callback("credit", Json.obj("token" -> token, "transaction_id" -> s"$round-c", "round" -> round, "amount" -> "2.00")).word, "ok")
    kit.eventually("one entry left")(view.get(s"st8/$round").filter(_.openCount == 1))
    assertEquals(gauge(), before - 1)
  }

  test("the bets counter counts every bet placed, free rounds included") {
    val player = s"$run-b"
    kit.wallet.set(player, "EUR", 1000)
    val token  = kit.sessionTicket(player)
    val before = Metrics.betsPlaced(kit.brand)
    kit.callback("debit", Json.obj("token" -> token, "transaction_id" -> s"$run-1", "round" -> s"$run-r1", "amount" -> "1.00"))
    kit.callback("debit", Json.obj("token" -> token, "transaction_id" -> s"$run-2", "round" -> s"$run-r2", "amount" -> "0", "provider_kind" -> "free_debit"))
    assertEquals(Metrics.betsPlaced(kit.brand), before + 2)
  }

/** The player's catalogue reads over 300 games (FR-023), exactly as the original's filters read them. */
class CatalogueViewSuite extends SharedKitSuite:

  private def seed(n: Int): Vector[String] =
    val games = (1 to n).map { i =>
      Json.obj("code" -> f"$run-g$i%03d", "name" -> s"Game $i ${if i % 10 == 0 then "Dragon" else "Fruit"}",
        "category" -> (if i % 3 == 0 then "table" else "slots"), "developer" -> s"$run-dev-${i % 5}", "enabled" -> true, "fun_mode" -> true,
        "bonus_types" -> Vector(if i % 4 == 0 then "free_bets" else "bonus_buy"), "features" -> Vector(if i % 6 == 0 then "megaways" else "gamble"),
        "themes" -> Vector(if i % 7 == 0 then "egypt" else "fruit"), "restricted_territories" -> (if i % 11 == 0 then Vector("DE") else Vector.empty[String]),
        "prohibited_territories" -> (if i % 13 == 0 then Vector("US") else Vector.empty[String]),
        "deprecation_date" -> (if i % 17 == 0 then "2020-01-01" else "2099-01-01"))
    }
    kit.st8.gamesBody = Json.render(Json.obj("games" -> Json.arr(games*), "developers" -> Json.arr((0 until 5).map(d => Json.obj("developer_id" -> s"$run-dev-$d", "name" -> s"Dev $d"))*)))
    val configs = kit.settings.configurations(kit.testKit.secrets)
    assertEquals(CatalogueRefresher(kit.testKit.componentClient, kit.testKit.service.viewClient, kit.settings.adapter, configs, _ => (), () => kit.clock).run("st8").status, "succeeded")
    val codes = (1 to n).map(i => f"$run-g$i%03d").toVector
    kit.eventually("all games in the view")(Option.when(codes.forall(c => kit.testKit.service.viewClient.forView(Catalogue).get(s"st8/$c").isDefined))(()))
    CatalogueReads.shared(kit.testKit.service.viewClient, kit.brand, kit.testKit.componentClient).invalidate()
    codes

  private def read(query: String) =
    val r = kit.request("GET", s"/v1/casino/catalog/games?brand=${kit.brand}$query", None, kit.asPlayer("browser"))
    assertEquals(r.status, 200, r.text)
    r.json

  private def ours(query: String): Vector[String] = read(query).objects("games").flatMap(_.text("gameId")).filter(_.startsWith(run))

  test("every filter, the paging, the batch, one game and the developers") {
    val all = seed(300)
    def nums(codes: Vector[String]) = codes.map(_.takeRight(3).toInt).toSet
    val listed = (1 to 300).filterNot(_ % 17 == 0).toSet
    assertEquals(nums(ours("")), listed, "every game but those past their deprecation date")
    assertEquals(nums(ours("&category=table")), listed.filter(_ % 3 == 0))
    assertEquals(nums(ours("&themes=egypt")), listed.filter(_ % 7 == 0))
    assertEquals(nums(ours("&features=megaways")), listed.filter(_ % 6 == 0))
    assertEquals(nums(ours("&bonusTypes=free_bets")), listed.filter(_ % 4 == 0))
    assertEquals(nums(ours("&category=egypt")), listed.filter(_ % 7 == 0), "a dimension matches the game's category and tags together, as the original")
    assertEquals(nums(ours("&tag=megaways,egypt")), listed.filter(i => i % 6 == 0 || i % 7 == 0), "values within a dimension are ORed")
    assertEquals(nums(ours("&category=table&themes=egypt")), listed.filter(i => i % 3 == 0 && i % 7 == 0), "dimensions are ANDed")
    assertEquals(nums(ours(s"&developer=$run-dev-2")), listed.filter(_ % 5 == 2))
    assertEquals(nums(ours("&search=dragon")), listed.filter(_ % 10 == 0))
    assertEquals(nums(ours("&country=de")), listed.filterNot(_ % 11 == 0))
    assertEquals(nums(ours("&country=US")), listed.filterNot(_ % 13 == 0))

    val page = read(s"&developer=$run-dev-1&limit=20&offset=20")
    val total = listed.count(_ % 5 == 1)
    assertEquals(page.long("total"), Some(total.toLong))
    assertEquals(page.long("nextOffset"), Some(40L))
    assertEquals(page.objects("games").size, 20)
    assert(page.objects("games").flatMap(_.text("gameId")).sliding(2).forall(p => p.size < 2 || p(0) < p(1)), "ordered by code")
    assertEquals(read(s"&developer=$run-dev-1&limit=5000").objects("games").size, total, "a limit is at most 3,000")
    assertEquals(read(s"&developer=$run-dev-1&limit=100&offset=${total - 1}").long("nextOffset"), None)

    val batch = read(s"&ids=${all.take(60).mkString(",")}&category=table").objects("games")
    assertEquals(batch.size, 50, "a batch is at most 50 codes and ignores every other filter")

    val one = kit.request("GET", s"/v1/casino/catalog/games/${all.head}", None, kit.asPlayer("browser"))
    assertEquals((one.status, one.json.text("provider"), one.json.text("gameId")), (200, Some("st8"), Some(all.head)))
    assertEquals(kit.request("GET", s"/v1/casino/catalog/games/$run-nope", None, kit.asPlayer("browser")).status, 404)

    val devs = kit.request("GET", s"/v1/casino/catalog/developers?brand=${kit.brand}", None, kit.asPlayer("browser")).json.objects("developers")
    assertEquals(devs.flatMap(_.text("developerId")).filter(_.startsWith(run)).toSet, (0 until 5).map(d => s"$run-dev-$d").toSet)
  }

  test("another brand's catalogue is refused, and an anonymous reader is asked to sign in") {
    assertEquals(kit.request("GET", "/v1/casino/catalog/games?brand=spinvibe", None, kit.asPlayer("p")).status, 404)
    assertEquals(kit.request("GET", s"/v1/casino/catalog/games?brand=${kit.brand}").status, 401)
  }

/** The refresh's retries, its status, and the operator's refresh-now through the durable timer (FR-021, research R11). */
class CatalogueRefreshSuite extends SharedKitSuite:

  test("a provider that keeps failing is retried three times at 10, 30 and 60 seconds and the failure is on status") {
    kit.st8.gamesStatus = 500
    val slept   = mutable.ArrayBuffer.empty[JDuration]
    val configs = kit.settings.configurations(kit.testKit.secrets)
    val state = CatalogueRefresher(kit.testKit.componentClient, kit.testKit.service.viewClient, kit.settings.adapter, configs, d => slept += d, () => kit.clock)
      .run("st8")
    assertEquals(slept.toVector, Vector(JDuration.ofSeconds(10), JDuration.ofSeconds(30), JDuration.ofSeconds(60)))
    assertEquals(state.status, "failed")
    assertEquals(state.attempt, 4)
    assert(state.error.exists(_.contains("500")), state.toString)
    val status = kit.request("GET", s"/v1/casino/backoffice/${kit.brand}/game-provider/providers/st8/catalogue/refresh", None, kit.asOperator())
    assertEquals(status.json.text("status"), Some("failed"))
  }

  test("a provider that recovers on the second attempt succeeds") {
    var calls = 0
    val real = io.eitheror.casino.gameprovider.provider.st8.St8Adapter()
    val flaky = new io.eitheror.casino.gameprovider.provider.ProviderAdapter:
      def name = "st8"
      def games(c: io.eitheror.casino.gameprovider.config.ProviderConfiguration) =
        calls += 1
        if calls == 1 then Left("reset by peer") else real.games(c)
      def launch(c: io.eitheror.casino.gameprovider.config.ProviderConfiguration, r: io.eitheror.casino.gameprovider.provider.LaunchRequest) = real.launch(c, r)
      def createBonus(c: io.eitheror.casino.gameprovider.config.ProviderConfiguration, r: io.eitheror.casino.gameprovider.provider.BonusRequest) = real.createBonus(c, r)
      def bonus(c: io.eitheror.casino.gameprovider.config.ProviderConfiguration, g: String) = real.bonus(c, g)
      def cancelBonus(c: io.eitheror.casino.gameprovider.config.ProviderConfiguration, g: String, p: String) = real.cancelBonus(c, g, p)
      def spinValues(c: io.eitheror.casino.gameprovider.config.ProviderConfiguration, g: Vector[String], cur: String) = real.spinValues(c, g, cur)
    val configs = kit.settings.configurations(kit.testKit.secrets)
    val state = CatalogueRefresher(kit.testKit.componentClient, kit.testKit.service.viewClient, p => Option.when(p == "st8")(flaky), configs, _ => (), () => kit.clock)
      .run("st8")
    assertEquals((state.status, state.count), ("succeeded", Some(3)))
  }

  test("an operator's refresh-now answers 202 running and the durable timer runs it") {
    kit.st8.gamesBody = Json.render(Json.obj("games" -> Json.arr(Json.obj("code" -> s"$run-now", "name" -> "Now", "category" -> "slots",
      "developer" -> "d", "enabled" -> true))))
    val r = kit.request("POST", s"/v1/casino/backoffice/${kit.brand}/game-provider/providers/st8/catalogue/refresh", None, kit.asOperator())
    assertEquals((r.status, r.json.text("status")), (202, Some("running")))
    kit.eventually("the game refreshed in", 60.seconds)(
      kit.testKit.componentClient.forKeyValueEntity(EntityId(s"st8/$run-now")).call(Game.get).invoke().fields)
    val status = kit.eventually("succeeded", 60.seconds) {
      Option(kit.request("GET", s"/v1/casino/backoffice/${kit.brand}/game-provider/providers/st8/catalogue/refresh", None, kit.asOperator()).json)
        .filter(_.text("status").contains("succeeded"))
    }
    assertEquals(status.long("count"), Some(1L))
  }

  test("the next nightly refresh is at 03:00 UTC") {
    assertEquals(CatalogueRefresher.nextNightly(java.time.Instant.parse("2026-10-09T02:59:00Z")), java.time.Instant.parse("2026-10-09T03:00:00Z"))
    assertEquals(CatalogueRefresher.nextNightly(java.time.Instant.parse("2026-10-09T03:00:00Z")), java.time.Instant.parse("2026-10-10T03:00:00Z"))
  }

  test("a provider with no configuration is refused a refresh") {
    val r = kit.request("POST", s"/v1/casino/backoffice/${kit.brand}/game-provider/providers/nova/catalogue/refresh", None, kit.asOperator())
    assertEquals((r.status, r.json.text("error")), (404, Some("provider_not_configured")))
    assertEquals(kit.request("POST", s"/v1/casino/backoffice/${kit.brand}/game-provider/providers/nope/catalogue/refresh", None, kit.asOperator()).status, 404)
  }

/** The routes `rewards` calls (FR-025), as a service of the project with no token. */
class RewardsEndpointSuite extends SharedKitSuite:

  private def post(path: String, body: (String, Any)*) =
    kit.request("POST", s"/v1/casino/brands/${kit.brand}/game-provider/st8$path", Some(Json.render(Json.obj(body*))))

  private def create(grant: String) =
    post("/bonuses", "grantId" -> grant, "playerId" -> s"$run-p", "gameCodes" -> Vector("g1"), "count" -> 5L, "spinValue" -> "0.10", "currency" -> "EUR")

  test("the provider's status words map to the six statuses") {
    for (word, status) <- Seq("active" -> BonusStatus.Active, "accepted" -> BonusStatus.Active, "processing" -> BonusStatus.Processing,
        "rejected" -> BonusStatus.Rejected, "error" -> BonusStatus.Failed, "canceled" -> BonusStatus.Cancelled) do
      kit.st8.bonusStatus = word
      assertEquals(create(s"$run-$word").json.text("status"), Some(status), word)
  }

  test("a player not known is made known before the bonus is created, and the call is signed") {
    create(s"$run-g")
    assertEquals(kit.callback("player_profile", Json.obj("player" -> s"$run-p")).word, "ok")
    assert(kit.st8.badSignatures.isEmpty)
  }

  test("spin values: sorted, cached for the day, and provider_unavailable when the provider fails") {
    kit.st8.offers = None
    val failing = kit.request("GET", s"/v1/casino/brands/${kit.brand}/game-provider/st8/spin-values?gameCodes=$run-a&currency=EUR")
    assertEquals((failing.status, failing.json.text("error")), (503, Some("provider_unavailable")))
    kit.st8.offers = Some(Vector("1.00", "0.05"))
    val ok = kit.request("GET", s"/v1/casino/brands/${kit.brand}/game-provider/st8/spin-values?gameCodes=$run-a&gameCodes=$run-b&currency=EUR")
    assertEquals(ok.json.strings("values"), Vector("0.05", "1.00"))
    assertEquals(kit.st8.offerRequests.last.query.get("game_codes"), Some(s"$run-a,$run-b"))
  }

  test("make-known answers no content and the profile then answers") {
    val r = kit.request("POST", s"/v1/casino/brands/${kit.brand}/game-provider/st8/players/$run-k/known", Some("""{"currency":"EUR"}"""))
    assertEquals(r.status, 204, r.text)
    assertEquals(kit.callback("player_profile", Json.obj("player" -> s"$run-k")).word, "ok")
  }

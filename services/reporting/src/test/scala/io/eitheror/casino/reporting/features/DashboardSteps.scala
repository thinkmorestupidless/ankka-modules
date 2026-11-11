package io.eitheror.casino.reporting.features

import com.fasterxml.jackson.databind.JsonNode
import io.eitheror.casino.reporting.it.Dashboards

import scala.jdk.CollectionConverters.*

/**
 * features/reporting/dashboard.feature (US2; FR-013–FR-015), bound by T038 over `Dashboards`' facts, published once
 * for the feature on a today of its own: every figure is the whole brand's, held to every fact set the kit published.
 */
class DashboardFeatures extends ReportingFeatures("dashboard.feature"):
  private lazy val d: Dashboards =
    val x = Dashboards(kit, "dashf")
    x.publish()
    x

  private var json: JsonNode = null
  private var range: String  = null

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    json = null; range = null

  Given("brand {string}") { (slug: String) =>
    assertEquals(kit.brand, slug)
    d: Unit
  }

  // ── the KPIs ──────────────────────────────────────────────────────────────────────────────────

  Given("more than twenty players with activity today") { () =>
    assert(d.players.size > 20)
  }

  Given("players online and games in play") { () =>
    assert(kit.everything.open("player") >= 5 && kit.everything.open("game") >= 3)
  }

  When("the operator reads the dashboard") { () =>
    json = d.kpis()
  }

  Then("GGR, NGR, deposits and withdrawals today are the sums over every player") { () =>
    val want = d.kpisWanted(exclude = false)
    for k <- Vector("ggrToday", "ngrToday", "depositsToday", "withdrawalsToday") do
      assertEquals(d.kpisShown(json)(k).split('/').head, want(k).split('/').head, k)
  }

  Then("yesterday's figures and the trend arrow are computed the same way") { () =>
    assertEquals(d.kpisShown(json), d.kpisWanted(exclude = false))
  }

  Then("players online counts the open player sessions") { () =>
    assertEquals(json.path("playersOnline").path("value").asLong, kit.everything.open("player"))
  }

  Then("active games counts the open game sessions") { () =>
    assertEquals(json.path("activeGames").path("value").asLong, kit.everything.open("game"))
  }

  // ── payment health ────────────────────────────────────────────────────────────────────────────

  Given("the day's deposit attempts and outcomes") { () =>
    assert(kit.everything.providers(d.today, "EUR").nonEmpty)
  }

  When("the operator reads payment health") { () =>
    json = d.health
  }

  Then("the deposit success rate is completed deposits over attempts for the day, per payment provider") { () =>
    val (shown, want) = (d.healthShown(json), d.healthWanted)
    assertEquals(shown.providers, want.providers)
    assertEquals(shown.rate, want.rate)
  }

  Then("the average time to a withdrawal's completion is over the day's completed withdrawals") { () =>
    assertEquals(d.healthShown(json).hours, d.healthWanted.hours)
    assert(d.healthWanted.hours.isDefined)
  }

  Then("a day with no attempts shows no rate, never a constant") { () =>
    val b = json.path("pspBreakdown").elements.asScala.find(_.path("pspName").asText == "dashf-psp-b")
    assert(b.exists(_.path("successRatePct").isNull), json.toString)
  }

  // ── the trend ─────────────────────────────────────────────────────────────────────────────────

  Given("the trend asked for today") { () => range = "today" }
  Given("the trend asked for seven days") { () => range = "7d" }
  Given("the trend asked for thirty days") { () => range = "30d" }
  Given("the trend asked for ninety days") { () => range = "90d" }

  When("it is read") { () =>
    json = d.trend(range)
  }

  Then("it is divided into {word}, each holding the GGR and NGR of its span in the brand's reporting zone") { (unit: String) =>
    val points = d.points(json)
    assertEquals(points, d.trendWanted(range))
    val label = unit match
      case "hours" => "\\d\\d:00"
      case "days"  => "\\d{4}-\\d\\d-\\d\\d"
      case "weeks" => "\\d{4}-W\\d\\d"
      case other   => fail(s"no unit $other")
    assert(points.forall(_._1.matches(label)), points.toString)
  }

  // ── rankings, activity, bonus, geography ──────────────────────────────────────────────────────

  Given("the rankings for a range") { () => range = "7d" }

  When("they are read") { () =>
    json = d.rankings
  }

  Then("games are ranked by game sessions started in the range") { () =>
    assertEquals(d.rankingsShown(json)._1, d.rankingsWanted._1)
  }

  Then("players are ranked by GGR in the range, with the player's country and total wagered") { () =>
    assertEquals(d.rankingsShown(json)._2, d.rankingsWanted._2)
  }

  Given("today's registrations, awards, self-exclusions and suspensions") { () =>
    assert(Set("registration", "bonus_grant", "suspension", "self_exclusion").subsetOf(d.activityWanted.map(_._2).toSet))
  }

  When("the activity list is read") { () =>
    json = d.activity
  }

  Then("each appears as one entry with its time and the player, newest first") { () =>
    val shown = d.activityShown(json)
    assertEquals(shown, d.activityWanted)
    assertEquals(shown.map(_._1).distinct.size, shown.size)
    assertEquals(shown.map(_._5), shown.map(_._5).sortBy(-_.toEpochMilli))
  }

  Given("open restricted funds records") { () =>
    assert(kit.everything.openRecords("EUR").nonEmpty)
  }

  When("the bonus summary is read") { () =>
    json = d.bonus
  }

  Then("it shows the count of open records, their total restricted balance as the brand's liability, the average release progress and today's bonus cost") {
    () => assertEquals(d.bonusShown(json), d.bonusWanted)
  }

  Given("players by country") { () =>
    assert(d.geoWanted.size >= 3)
  }

  When("the geography is read") { () =>
    json = d.geo
  }

  Then("each country's count is over every player of the brand") { () =>
    assertEquals(d.geoShown(json), d.geoWanted)
  }

package io.eitheror.casino.engagement.features

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.contract.facts as f
import io.eitheror.casino.engagement.profile.{Attributes, DepositDay}
import io.eitheror.casino.engagement.reads.ProfilesView
import io.eitheror.casino.engagement.timers.DepositDayReset

import java.time.{Duration, Instant}

/**
 * features/engagement/profiles.feature (US2; FR-003, FR-008, FR-009, FR-010). The background's player is registered
 * with email consent on the profile itself; each scenario adds the facts it names.
 */
class ProfilesFeatures extends EngagementFeatures("profiles.feature"):
  private var fact: f.CasinoPlayersFact = null
  private var zero                      = ""

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    fact = null
    zero = ""

  override def afterEach(context: AfterEach): Unit =
    kit.setClock(Instant.now())
    super.afterEach(context)

  private def attrs(b: JsonNode)        = kit.crm.attributes(b)
  private def identifies(id: String)    = kit.crm.identifies(id).map(attrs)
  private val balances                  = Vector("real_money", "bonus_money", "total_balance")

  /** The identify made just before the player's `name` track, at the fake. */
  private def identifyBefore(name: String): Map[String, JsonNode] =
    eventually(s"a $name track") {
      val mine = kit.crm.calls.filter(_.raw.contains(player)).flatMap(_.body)
      val at   = mine.indexWhere(b => b.path("action").asText == "event" && b.path("name").asText == name)
      Option.when(at > 0)(mine.take(at).filter(_.path("action").asText == "identify").lastOption.map(attrs)).flatten
    }

  Given("a player of brand {string}") { (slug: String) =>
    assertEquals(kit.slug, slug)
    register()
  }

  // Scenario: the identify before any track carries every attribute whose source fact has been seen.

  Given("the facts of the player's life so far") { () =>
    players(f.PlayerLoggedIn(player, "s-1", Instant.now().minusSeconds(1200)))
    money(deposit(at = Instant.now().minusSeconds(900)))
  }

  When("any track is made") { () => publishMoney(deposit()) }

  Then("the identify that precedes it carries every attribute whose source fact has been seen") { () =>
    val seen = Set("id", "created_at", "email", "player_name", "brand_id", "currency", "country", "registration_date",
      "vip_tier", "last_login_at", "last_deposit_at", "total_deposit_amount", "has_rg_limits", "account_status",
      "deposit_count", "daily_deposit_count", "real_money", "bonus_money", "total_balance", "email_subscribed",
      "sms_subscribed", "email_consent_at", "consent_source", "pot_vip", "marketing_suppressed")
    assertEquals(seen -- identifyBefore("deposit_confirmed").keySet, Set.empty[String])
  }

  Then("it omits the rest") { () =>
    val unseen = Set("date_of_birth", "rg_limit_types", "is_cooling_off", "suspected_duplicate", "kyc_status", "btag",
      "sms_consent_at", "suppression_reason")
    assertEquals(identifyBefore("deposit_confirmed").keySet.intersect(unseen), Set.empty[String])
  }

  Then("the numeric attributes are numbers") { () =>
    for (k, v) <- identifyBefore("deposit_confirmed") do
      if Attributes.numeric(k) then assert(v.isNumber, s"$k: $v") else assert(v.isTextual, s"$k: $v")
  }

  // Scenario: the deposit day rolling over.

  Given("the player deposited twice today by the brand's deposit day") { () =>
    money(deposit())
    money(deposit())
    zero = newPlayer()
    register(zero)
  }

  When("the deposit day rolls over") { () =>
    val next = DepositDay.of(Instant.now()).plusDays(1)
    eventually("the deposits in the view") {
      ProfilesView.dailyCountAbove(kit.views.forView(ProfilesView), next).find(_.playerId == player).filter(_.dailyDepositCount == 2)
    }: Unit
    kit.crm.reset()
    kit.setClock(DepositDay.nextReset(Instant.now()).plus(Duration.ofMinutes(1)))
    kit.fireTimer(DepositDayReset.timerName(kit.slug))
  }

  Then("one identify with {string} of {string} is sent for the player") { (name: String, value: String) =>
    eventually(s"the identify with $name")(identifies(player).find(_.get(name).exists(_.asText == value))): Unit
    Thread.sleep(1000)
    assertEquals(identifies(player).count(_.get(name).exists(_.asText == value)), 1)
    assert(identifies(player).forall(_(name).isNumber))
  }

  Then("none is sent for a player whose count was already {string}") { (n: String) =>
    assertEquals(kit.components.forKeyValueEntity(EntityId(zero))
      .call(io.eitheror.casino.engagement.profile.Profile.get).invoke().dailyDepositCount.toString, n)
    assertEquals(identifies(zero), Vector.empty)
  }

  // Scenario: a tier change.

  Given("an operator changes the player's tier") { () =>
    fact = f.VipTierChanged(player, Some("bronze"), "gold", "op-1", Instant.now())
  }

  When("the fact arrives") { () => publishPlayers(fact) }

  Then("the player is identified with the new value") { () =>
    eventually("the identify with gold")(identifies(player).find(_.get("vip_tier").exists(_.asText == "gold"))): Unit
  }

  Then("no track is made") { () =>
    Thread.sleep(1500)
    assertEquals(kit.crm.tracks(player), Vector.empty)
  }

  // Scenario: no row in the balance view.

  Given("the balance view has no row for the player") { () =>
    assertEquals(kit.components.forKeyValueEntity(EntityId(player))
      .call(io.eitheror.casino.engagement.profile.Profile.get).invoke().realCents, None)
  }

  When("they are identified") { () => publishPlayers(f.VipTierChanged(player, Some("bronze"), "silver", "op-1", Instant.now())) }

  Then("the balance attributes are absent, not zero") { () =>
    val a = eventually("the identify")(identifies(player).find(_.get("vip_tier").exists(_.asText == "silver")))
    assertEquals(balances.filter(a.contains), Vector.empty)
  }

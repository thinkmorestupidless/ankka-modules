package io.eitheror.casino.contract.steps

import com.thinkmorestupidless.ankka.http.{Acl, Caller}
import com.thinkmorestupidless.ankka.runtime.RotatingTls
import com.thinkmorestupidless.ankka.testkit.GherkinSuite
import io.eitheror.casino.contract.access.Requests.*
import io.eitheror.casino.contract.access.Routes

/** features/brands/access.feature against the route recipes every casino service declares. */
class AccessFeatures extends GherkinSuite("features/brands/access.feature"):

  override protected def ranElsewhere: Map[String, String] = Map(
    "a payment provider's webhook is served only when its signature verifies" -> "the gateway's suites (spec 005): the route is public and each provider's adapter verifies its own signature",
    "a registration is reported to the brand's player records and to no other part of the brand" -> "the player records' suites (spec 008), with the realm template's audit listener"
  )

  private var brand     = "goldvibe"
  private var token     = Option.empty[String]
  private var tokens    = Vector.empty[String]
  private var outcomes  = Vector.empty[Outcome]
  private var problem   = Option.empty[String]

  private def at(slug: String, service: String = "wallet") = RotatingTls.Identity(slug, service)

  Given("a player of brand {string} signed in") { (slug: String) => token = Some(playerOf(slug)) }

  When("they read their own wallet at brand {string}") { (slug: String) =>
    outcomes = Vector(decide(Routes.player(slug, verified), Request(token = token), at(slug)))
  }

  Then("they are served") { () => assertEquals(outcomes, Vector.fill(outcomes.size)(Outcome.Served)) }

  Then("they are refused") { () =>
    assert(outcomes.nonEmpty && outcomes.forall(_.isInstanceOf[Outcome.Refused]), outcomes.toString)
  }

  Given("an operator whose roles name brand {string} only") { (slug: String) =>
    // signed in every way the casino issues a token: the operators' realm, and a player realm whose
    // token carries the role's name
    tokens = Vector(operatorWith(s"brand_$slug", "operator"), playerRealmAsStaff(slug), playerOf(slug))
  }

  When("they act as an operator at brand {string}") { (slug: String) =>
    outcomes = tokens.map(t => decide(Routes.operator(slug, verified), Request(token = Some(t)), at(slug)))
  }

  Then("they are refused, however they signed in") { () =>
    assertEquals(outcomes.size, 3)
    assert(outcomes.forall(_.isInstanceOf[Outcome.Refused]), outcomes.toString)
  }

  Given("brand {string}") { (slug: String) => brand = slug }

  When("its game provider places a bet with its wallet") { () =>
    outcomes = Vector(decide(Routes.service(), Request(caller = Caller.Service(brand, "game-provider")), at(brand)))
  }

  Then("it is admitted by its identity and never signs in") { () =>
    assertEquals(outcomes, Vector(Outcome.Served))
  }

  Given("brand {string} allows the shared payments to confirm deposits") { (slug: String) => brand = slug }

  private def depositRoute: Acl = Routes.granted("wallet").find(_._1.path.endsWith("/deposits")).get._2

  When("the shared payments confirms a deposit for a player of brand {string}") { (slug: String) =>
    outcomes = Vector(decide(depositRoute, Request(caller = Caller.Service("payments", "merchant")), at(slug)))
  }

  When("the shared payments reads the wallet of a player of brand {string}") { (slug: String) =>
    // reading a wallet is a player's, an operator's or the brand's own services' route: none granted
    val merchant = Request(caller = Caller.Service("payments", "merchant"))
    outcomes = Vector(
      decide(Routes.service(), merchant, at(slug)),
      decide(Routes.player(slug, verified), merchant, at(slug)),
      decide(Routes.operator(slug, verified), merchant, at(slug))
    )
  }

  Then("it is admitted") { () => assertEquals(outcomes, Vector(Outcome.Served)) }

  Then("it is refused") { () => assert(outcomes.forall(_.isInstanceOf[Outcome.Refused]), outcomes.toString) }

  When("a visitor registers without signing in") { () =>
    outcomes = Vector(decide(Routes.public, Request(), at(brand, "players")))
  }

  When("a visitor lands on an affiliate's link without signing in") { () =>
    outcomes = Vector(decide(Routes.public, Request(), at(brand, "attribution")))
  }

  When("it is asked about brand {string}") { (other: String) =>
    problem = Routes.brandSegment(brand, other).left.toOption.map(_.message)
  }

  Then("it refuses, naming {string} as the brand it serves") { (slug: String) =>
    assert(problem.exists(_.contains(slug)), problem.toString)
  }

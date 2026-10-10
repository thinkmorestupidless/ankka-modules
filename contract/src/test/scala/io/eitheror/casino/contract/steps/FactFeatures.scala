package io.eitheror.casino.contract.steps

import com.thinkmorestupidless.ankka.testkit.GherkinSuite
import io.eitheror.casino.contract.facts.*
import io.eitheror.casino.contract.money.{Currency, MinorUnits, SignedMinorUnits}

import java.time.Instant

/** features/brands/facts.feature: what the contract decides is bound here; what a service does is its own suite's. */
class FactFeatures extends GherkinSuite("features/brands/facts.feature"):

  override protected def ranElsewhere: Map[String, String] = Map(
    "a bet placed is published as a fact about the wallet it belongs to" -> "the wallet's suites (spec 002), with this contract's BetPlaced",
    "a view rebuilt from a brand's facts a year later sees every fact" -> "ApplyOnKindSuite once ankka 043 can declare a fact topic's retention",
    "a deposit captured by the shared payments credits the brand's wallet once however many times it is delivered" -> "the wallet's suites (spec 002), with KeyedOutcomes",
    "the shared audit keeps a brand's audit records naming the player by a pseudonym" -> "the shared audit's suites (spec 018)"
  )

  private var before: Vector[FamilyDef[?]] = Vector.empty
  private var after: Vector[FamilyDef[?]]  = Vector.empty

  private val v1 = Vector(FamilyDef.of[ExampleV1]("example_happened"))

  Given("a kind of fact at version {string} read by two brands") { (version: String) =>
    assertEquals(version, "1"); before = v1
  }

  When("a field is added to it") { () =>
    after = Vector(FamilyDef.of[ExampleWithField]("example_happened"))
  }

  Then("the kind stays at version {string} and both brands keep reading it") { (version: String) =>
    assertEquals(version, "1")
    assertEquals(Compatibility.compare(before, after), Compatibility.Verdict.SameVersion)
  }

  Given("a kind of fact at version {string} whose meaning changes") { (version: String) =>
    assertEquals(version, "1"); before = v1
  }

  When("the change is made") { () =>
    after = Vector(FamilyDef.of[ExampleRetyped]("example_happened"))
  }

  Then("it is a new version beside the old, and the old is produced until no brand reads it") { () =>
    Compatibility.compare(before, after) match
      case Compatibility.Verdict.NewVersion(reasons) =>
        assert(reasons.exists(_.contains("amount")), reasons.mkString("; "))
        assertEquals(Compatibility.nextVersion("example_happened"), "example_happened.v2")
        // beside the old: both families together are a topic the old readers still read
        val both = v1 ++ Vector(FamilyDef.of[ExampleRetyped]("example_happened.v2"))
        assertEquals(Compatibility.compare(v1, both), Compatibility.Verdict.SameVersion)
      case other => fail(s"a changed meaning kept its version: $other")
  }

final case class ExampleV1(playerId: String, amount: MinorUnits, occurredAt: Instant)
final case class ExampleWithField(playerId: String, amount: MinorUnits, currency: Option[Currency], occurredAt: Instant)
final case class ExampleRetyped(playerId: String, amount: SignedMinorUnits, occurredAt: Instant)

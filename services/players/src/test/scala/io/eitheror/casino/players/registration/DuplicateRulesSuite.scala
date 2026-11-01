package io.eitheror.casino.players.registration

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.players.player.CheckOutcome
import io.eitheror.casino.players.registration.LookupTokens.*

/**
 * The strong and weak rules (spec 011 FR-035, research R9), rule by rule, as the original's
 * `MultiAccountDetector.applyFlags`: what the index found per signal and the address's other
 * registrations of the last 24 hours in, the outcome and the pairs out.
 */
class DuplicateRulesSuite extends munit.FunSuite with LogCapturing:

  private def judge(found: (String, Set[String])*)(nearby: String*) = DuplicateRules.judge(found.toMap, nearby.toSet)

  test("the identity or the device signal alone is a strong pair") {
    assertEquals(judge(Identity -> Set("a"))(), Judgement(CheckOutcome.Strong, Map("a" -> Set(Identity))))
    assertEquals(judge(Device -> Set("a", "b"))(), Judgement(CheckOutcome.Strong, Map("a" -> Set(Device), "b" -> Set(Device))))
    assertEquals(judge(Identity -> Set("a"), Device -> Set("a"))().pairs, Map("a" -> Set(Identity, Device)))
  }

  test("an email variant from the same address within 24 hours is a strong pair") {
    assertEquals(judge(EmailVariant -> Set("a"))("a"), Judgement(CheckOutcome.Strong, Map("a" -> Set(EmailVariant, Address))))
  }

  test("an email variant with three or more registrations from the new player's address is a strong pair") {
    assertEquals(judge(EmailVariant -> Set("a"))("x", "y"), Judgement(CheckOutcome.Strong, Map("a" -> Set(EmailVariant, AddressRate))))
    assertEquals(judge(EmailVariant -> Set("a"))("a", "x").pairs, Map("a" -> Set(EmailVariant, Address, AddressRate)))
  }

  test("an email variant alone is weak: no pair, the variant named") {
    assertEquals(judge(EmailVariant -> Set("a"))(), Judgement(CheckOutcome.Weak, weak = Set(EmailVariant)))
    assertEquals(judge(EmailVariant -> Set("a"))("x"), Judgement(CheckOutcome.Weak, weak = Set(EmailVariant)), "two registrations is not the rate")
  }

  test("the address's rate alone, or nothing found, is clear") {
    assertEquals(judge()("x", "y", "z"), Judgement(CheckOutcome.Clear))
    assertEquals(judge(Identity -> Set.empty, EmailVariant -> Set.empty)(), Judgement(CheckOutcome.Clear))
  }

  test("a strong pair beside a variant that is not one: only the strong pair is linked") {
    assertEquals(judge(Identity -> Set("a"), EmailVariant -> Set("b"))(), Judgement(CheckOutcome.Strong, Map("a" -> Set(Identity))))
  }

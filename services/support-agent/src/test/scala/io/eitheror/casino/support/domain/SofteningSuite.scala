package io.eitheror.casino.support.domain

import org.scalacheck.{Gen, Prop}

/** contracts/tools.md's *Softening*: the phrase list, whole words in any case, only in a turn about a break or an exclusion. */
class SofteningSuite extends munit.ScalaCheckSuite:
  private val protective = Set("apply_cool_off")

  test("each phrase of the list is refused, in any case, in a turn that offers a protective tool"):
    Softening.Phrases.foreach { p =>
      assertEquals(Softening.refuses(protective, s"Your break is set. ${p.toUpperCase}, of course."), Some(p), p)
    }

  test("a phrase matches only as whole words"):
    assertEquals(Softening.refuses(protective, "Your break is set; it can be undone."), Some("can be undone"))
    assertEquals(Softening.refuses(protective, "Your break is set, and what you can changes nothing."), None)
    assertEquals(Softening.refuses(protective, "Your break is set; xreverse it."), None)

  test("a curly apostrophe is the same as a straight one"):
    assertEquals(Softening.refuses(protective, "Don’t worry, your break is set."), Some("don't worry"))

  test("a turn neither offering a protective tool nor naming a break or an exclusion is not checked"):
    assertEquals(Softening.refuses(Set("get_wallet_balance"), "Don't worry, your balance is 50.00."), None)

  test("a reply naming a break, a cool-off or an exclusion is checked whatever the turn's tools"):
    Seq("your break", "a cool-off", "the cooling-off period", "a cool off", "your self-exclusion", "the exclusion").foreach { t =>
      assertEquals(Softening.refuses(Set.empty, s"About $t: don't worry."), Some("don't worry"), t)
    }

  test("the original's plain statements pass"):
    assertEquals(Softening.refuses(Set("apply_self_exclusion"),
      "Your account is now permanently self-excluded. This is permanent and cannot be reversed."), None)
    assertEquals(Softening.refuses(protective, "Your cool-off is in effect until 2026-12-01. It cannot be shortened."), None)

  property("no reply without a listed phrase is refused"):
    Prop.forAll(Gen.listOf(Gen.oneOf("break", "set", "until", "December", "you", "can", "not", "be", "it", "."))) { words =>
      val text = words.mkString(" ")
      Softening.Phrases.exists(p => text.toLowerCase.contains(p)) || Softening.refuses(protective, text).isEmpty
    }

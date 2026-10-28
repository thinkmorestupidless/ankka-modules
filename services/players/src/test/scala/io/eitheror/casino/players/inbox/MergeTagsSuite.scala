package io.eitheror.casino.players.inbox

import io.eitheror.casino.players.inbox.MergeTags.Refused

/** FR-030: the original's merge tags, rendered in one pass. */
class MergeTagsSuite extends munit.FunSuite:

  test("every tag renders its value") {
    MergeTags.tags.foreach { tag =>
      assertEquals(MergeTags.render(s"{{${tag.name}}}", Map(tag.name -> "V")), Right("V"), tag.name)
    }
  }

  test("an unknown tag refuses the whole render, naming itself") {
    assertEquals(MergeTags.render("Hi {{first_name}} {{no_such_tag}}", Map("first_name" -> "Ada")), Left(Refused.UnknownTag("no_such_tag")))
    assertEquals(MergeTags.unresolved("{{nope}}", Map.empty), Left(Refused.UnknownTag("nope")))
  }

  test("a missing value takes its fallback, else nothing, and the space beside it collapses") {
    assertEquals(MergeTags.render("Hi {{first_name|there}}!", Map.empty), Right("Hi there!"))
    assertEquals(MergeTags.render("Hi {{first_name}}, welcome!", Map.empty), Right("Hi, welcome!"))
    assertEquals(MergeTags.render("Hello {{first_name}} friend", Map.empty), Right("Hello friend"))
  }

  test("a value is spliced in once and never scanned again") {
    assertEquals(MergeTags.render("Hi {{first_name}}", Map("first_name" -> "{{vip_status}}")), Right("Hi {{vip_status}}"))
  }

  test("the brand's links resolve with no value given, and the call to action follows the language") {
    assertEquals(MergeTags.render("{{vip_program_link}}", Map.empty), Right("internal://vip-program"))
    assertEquals(MergeTags.renderFallback("{{play_now_cta}}"), Right("Play now"))
    assertEquals(MergeTags.playNow(Some("fr-CA")), "Jouer maintenant")
  }

  test("unresolved names the tags no value resolves; the inbox's tags preview with their examples") {
    assertEquals(MergeTags.unresolved("{{first_name}} {{vip_status}} {{bonuses_section_link}}", Map("first_name" -> "Ada")),
      Right(Set("vip_status")))
    assertEquals(MergeTags.render("{{cash_balance}}", MergeTags.demo), Right("€42.50"))
  }

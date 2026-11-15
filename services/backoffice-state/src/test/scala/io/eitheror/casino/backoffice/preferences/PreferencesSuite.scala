package io.eitheror.casino.backoffice.preferences

import com.thinkmorestupidless.ankka.testkit.KeyValueEntityTestKit

import java.time.Instant

/** data-model.md `Preferences` and FR-038: four keys, nothing about a player, at most 20 jobs. */
class PreferencesSuite extends munit.FunSuite:

  private val t0 = Instant.parse("2026-11-15T10:00:00Z")

  private def kit = KeyValueEntityTestKit.of(Preferences, "8c1f-sub")

  private def parsed(text: String): PreferencesWrite =
    PreferencesRules.parse(text).fold(r => fail(s"refused: $r"), identity)

  private def refusal(text: String): PreferenceRefusal =
    PreferencesRules.parse(text).swap.getOrElse(fail(s"admitted: $text"))

  test("an operator with none written reads the defaults: dark, the kinds the roles admit, no notices, no jobs") {
    val s = kit.call(Preferences.get).replyValue
    assertEquals(s, PreferencesState())
    assertEquals(s.theme, "dark")
    assertEquals(s.worklistKinds, None)
    assert(!s.onlineNotices)
    assertEquals(s.jobs, Nil)
  }

  test("a write changes the keys it names and keeps the rest") {
    val k = kit
    k.call(Preferences.write)(parsed("""{"theme":"light","onlineNotices":true}""")).replyValue: Unit
    val s = k.call(Preferences.write)(parsed("""{"worklistKinds":["withdrawal_request","kyc_document"]}""")).replyValue
    assertEquals(s.theme, "light")
    assert(s.onlineNotices)
    assertEquals(s.worklistKinds, Some(Set("withdrawal_request", "kyc_document")))
    assertEquals(k.call(Preferences.get).replyValue, s)
  }

  test("a key not of the four is refused unknown_preference, naming it") {
    val r = refusal("""{"theme":"dark","player":"p-1"}""")
    assertEquals(r.code, PreferenceRefusal.Unknown)
    assert(r.message.contains("player"), r.message)
    assertEquals(refusal("""{"favouritePlayers":["p-1"]}""").code, PreferenceRefusal.Unknown)
  }

  test("a job naming a player is refused unknown_preference") {
    val r = refusal("""{"jobs":[{"reference":"exp-1","kind":"player_export","startedAt":"2026-11-15T10:00:00Z","playerId":"p-1"}]}""")
    assertEquals(r.code, PreferenceRefusal.Unknown)
    assert(r.message.contains("playerId"), r.message)
  }

  test("a value naming a player is refused unknown_preference") {
    assertEquals(refusal("""{"jobs":[{"reference":"player/p-1","kind":"export","startedAt":"2026-11-15T10:00:00Z"}]}""").code,
      PreferenceRefusal.Unknown)
    assertEquals(refusal("""{"worklistKinds":["player:p-1"]}""").code, PreferenceRefusal.Unknown)
  }

  test("a value of the wrong shape is refused invalid_preference") {
    assertEquals(refusal("""{"theme":"sepia"}""").code, PreferenceRefusal.Invalid)
    assertEquals(refusal("""{"onlineNotices":"yes"}""").code, PreferenceRefusal.Invalid)
    assertEquals(refusal("""{"jobs":[{"reference":"exp-1","kind":"export"}]}""").code, PreferenceRefusal.Invalid)
    assertEquals(refusal("""not json""").code, PreferenceRefusal.Invalid)
    assertEquals(refusal("""["theme"]""").code, PreferenceRefusal.Invalid)
  }

  test("the entity refuses what the route would, so no caller can store a player") {
    val bad = PreferencesWrite(jobs = Some(List(Job("player/p-1", "export", t0))))
    val r   = kit.call(Preferences.write)(bad)
    assert(r.isError)
    assert(r.errorMessage.contains(PreferenceRefusal.Unknown), r.errorMessage)
  }

  test("at most 20 jobs are kept, newest first") {
    val jobs = (1 to 25).map(n => Job(s"exp-$n", "export", t0.plusSeconds(n.toLong))).toList // oldest first, as given
    val s    = kit.call(Preferences.write)(PreferencesWrite(jobs = Some(jobs))).replyValue
    assertEquals(s.jobs.size, 20)
    assertEquals(s.jobs.head.reference, "exp-25")
    assertEquals(s.jobs.last.reference, "exp-6")
    assertEquals(s.jobs, s.jobs.sortBy(_.startedAt).reverse)
  }

  test("an erasure deletes the operator's preferences; the next read is the defaults") {
    val k = kit
    k.call(Preferences.write)(parsed("""{"theme":"light"}""")).replyValue: Unit
    assert(k.call(Preferences.erase).replyValue)
    assert(k.isDeleted)
    assertEquals(k.call(Preferences.get).replyValue, PreferencesState())
  }

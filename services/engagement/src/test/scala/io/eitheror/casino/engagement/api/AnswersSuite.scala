package io.eitheror.casino.engagement.api

import io.eitheror.casino.engagement.clients.Reply

import java.nio.charset.StandardCharsets.UTF_8

/** contracts/routes.md's refusals, the webhook's literal messages, and a neighbour's answer passed through. */
class AnswersSuite extends munit.FunSuite:
  private def text(a: Answers.Answer) = String(a.body.body, UTF_8)

  test("a refusal is {error, …fields} at its status; missing fields are named in order") {
    val a = Answers.missingFields(Seq("key", "to"))
    assertEquals((a.status, text(a)), (400, """{"error":"missing_fields","fields":["key","to"]}"""))
    assertEquals(text(Answers.refusal(409, "not_parked", "send" -> None)), """{"error":"not_parked"}""")
  }

  test("the webhook's missing-field refusals are the original's words") {
    assertEquals(text(Answers.Webhook.grantBonus), """{"error":"player_id and offer_id required"}""")
    assertEquals(text(Answers.Webhook.sendMessage), """{"error":"player_id required"}""")
    assertEquals(text(Answers.Webhook.targetOffer), """{"error":"player_id and role required"}""")
    assertEquals(Answers.Webhook.grantBonus.status, 422)
  }

  test("a neighbour's answer is passed through unchanged, and one that did not answer is 503 naming it") {
    val a = Answers.relayed(Reply.Answered(422, """{"error":"offer_not_active"}"""))
    assertEquals((a.status, text(a), a.body.contentType), (422, """{"error":"offer_not_active"}""", "application/json"))
    assertEquals(Answers.relayed(Reply.Answered(502, "bad gateway")).body.contentType, "text/plain")
    val u = Answers.relayed(Reply.Unreachable("rewards"))
    assertEquals(u.status, 503)
    assert(text(u).contains(""""service":"rewards""""), text(u))
  }

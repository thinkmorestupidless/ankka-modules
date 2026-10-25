package io.eitheror.casino.ingest

import com.thinkmorestupidless.ankka.core.Serializer
import io.eitheror.casino.ingest.keys.{KeyCodecs, KeyState}
import io.eitheror.casino.ingest.keys.KeyCodecs.given
import io.eitheror.casino.ingest.rate.RateCodecs.given
import io.eitheror.casino.ingest.rate.RateState
import io.eitheror.casino.ingest.requests.*
import io.eitheror.casino.ingest.requests.OwnershipCodecs.given
import io.eitheror.casino.ingest.requests.RecordCodecs.given
import io.eitheror.casino.ingest.requests.RequestCodecs.given

import java.time.Instant

/** Every stored type round-trips; an answer's body is kept as `merchant` sent it. */
class IngestCodecsSuite extends munit.FunSuite:
  private val at = Instant.parse("2026-10-22T12:00:15.123Z")

  private def roundTrip[A](a: A)(using s: Serializer[A]): Unit = assertEquals(s.fromBytes(s.toBytes(a)), a)

  test("the five entities' states round-trip") {
    roundTrip(KeyState("ab12", "sb-1", active = true, since = Some(at)))
    roundTrip(KeyState("ab12", "sb-1", deactivated = true, since = Some(at)))
    roundTrip(RateState(29_000_000L, 12, Some(6000)))
    roundTrip(RequestState("d1", 2, RequestState.Answered, Some(Answer(200, """{"session_token":"t"}""")), Vector("r1", "r2"), Some(at)))
    roundTrip(OwnershipState("sb-1", Some("t-1"), "r1", Some("tok"), Some("pay-1")))
    roundTrip(RecordState("r1", Some("sb-1"), Route.Create, Some(at), Some("t-1"), Some(Long.MaxValue), Some("EUR"), Some("GB"),
      Outcome.LostAnswer, None, Some("tok"), None, Some(1), Some(LostAnswer(at, Some("tok"))), seen = true))
  }

  test("an answer's body with nested available_terminals survives unchanged, byte for byte") {
    val body = """{"available_terminals":[{"terminalEntityId":"sim-eur","supportedCurrencies":["EUR","GBP"]}],"session_token":"t","resolvedFrom":{"level":"subBrand"}}"""
    val s    = summon[Serializer[RequestState]]
    val back = s.fromBytes(s.toBytes(RequestState(answer = Some(Answer(200, body)))))
    assertEquals(back.answer.map(_.body), Some(body))
  }

  test("minor units are a JSON number and a time is ISO-8601") {
    val text = String(summon[Serializer[RecordState]].toBytes(RecordState("r1", amount = Some(5000), receivedAt = Some(at), outcome = "served")), "UTF-8")
    assert(text.contains("\"amount\":5000"), text)
    assert(text.contains("\"receivedAt\":\"2026-10-22T12:00:15.123Z\""), text)
    assertEquals(KeyCodecs.given_Serializer_KeyState.manifest, "ingest.key-state")
  }

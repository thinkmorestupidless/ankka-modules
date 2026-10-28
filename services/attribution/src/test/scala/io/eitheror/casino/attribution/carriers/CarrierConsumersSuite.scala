package io.eitheror.casino.attribution.carriers

import com.thinkmorestupidless.ankka.core.Serializer
import com.thinkmorestupidless.ankka.core.effect.ConsumerEffect
import com.thinkmorestupidless.ankka.testkit.ConsumerTestKit
import io.eitheror.casino.attribution.{AttributionCodecs, Named, Recorded, Wiring}
import io.eitheror.casino.attribution.facts.Facts

import java.time.Instant

/** The feed appender and the webhook fanout (T025), over a client that records every call. */
class CarrierConsumersSuite extends munit.FunSuite:
  private val at   = Instant.parse("2026-10-25T10:00:00Z")
  private val fact = Facts.click("goldvibe", "c-1", Named("programme", "aff-1", "lnk-1", None, Vector.empty), at, None, None)

  private def recorded = Recorded(c => c.component match
    case "network-feed" => NetworkFeedCodecs.given_Serializer_AppendResult.toBytes(AppendResult.Added(1))
    case _              => AttributionCodecs.serializer[DeliveryState]("delivery").toBytes(DeliveryState()))

  test("FeedAppender appends a fact to its network's feed; an unreadable fact is skipped") {
    val r   = recorded
    val kit = ConsumerTestKit.of(FeedAppender, r.client)
    assertEquals(kit.onMessage(Some(fact)).effect, ConsumerEffect.Done)
    val call = r.calls.single
    assertEquals((call.component, call.entity, call.method), ("network-feed", "programme", "append"))
    assertEquals(call.as[FeedFact](using NetworkFeedCodecs.given_Serializer_FeedFact), FeedFact.of(fact))
    assertEquals(kit.onMessage(None).effect, ConsumerEffect.Ignore)
  }

  test("WebhookFanout starts one Delivery per active subscription of the network, none when there is none") {
    Wiring.activeSubscriptions.set {
      case "programme" => Vector("sub-1", "sub-2")
      case _           => Vector.empty
    }
    val r   = recorded
    val kit = ConsumerTestKit.of(WebhookFanout, r.client)
    val f   = FeedFact.of(fact)
    assertEquals(kit.onMessage(FeedEvent.Appended(1, f), subject = "programme").effect, ConsumerEffect.Done)
    assertEquals(r.calls.map(c => (c.component, c.entity, c.method)),
      Vector(("delivery", "sub-1/click/c-1", "start"), ("delivery", "sub-2/click/c-1", "start")))
    given Serializer[DeliveryStart] = Delivery.given_Serializer_DeliveryStart
    assertEquals(r.calls.head.as[DeliveryStart], DeliveryStart("sub-1", "programme", f))
    assertEquals(kit.onMessage(FeedEvent.Appended(1, f), subject = "other").effect, ConsumerEffect.Ignore)
    assertEquals(r.calls.size, 2)
  }

  extension [A](v: Vector[A]) private def single: A = { assertEquals(v.size, 1); v.head }

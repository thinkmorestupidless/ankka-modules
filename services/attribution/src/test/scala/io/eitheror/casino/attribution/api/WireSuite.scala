package io.eitheror.casino.attribution.api

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonValueCodec, readFromString, writeToString}
import io.eitheror.casino.attribution.api.Wire.{*, given}

import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant

/** Every shape of contracts/routes.md round-trips, and a refusal's body names its code and message. */
class WireSuite extends munit.FunSuite:

  private val at = Instant.parse("2026-10-25T12:00:00Z")

  private def roundTrips[A: JsonValueCodec](name: String, a: A)(using munit.Location): Unit =
    test(s"$name round-trips"):
      assertEquals(readFromString[A](writeToString(a)), a)

  roundTrips("Refusal", Refusal(Codes.InvalidRequest, "id is not a network id", Some("id")))
  roundTrips("ClickRequest", ClickRequest(btag = Some("t"), clickId = Some("c-1"), deviceType = Some("mobile"),
    country = Some("ZA"), utmSource = Some("s"), utmMedium = Some("m"), utmCampaign = Some("c"), utmContent = Some("x"),
    utmTerm = Some("y"), landingPage = Some("/"), referrer = Some("r"), brandId = Some("goldvibe")))
  roundTrips("ClickAnswer", ClickAnswer(ok = false, "c-1", Some("malformed")))
  roundTrips("ClickError", ClickError("btag is required"))
  roundTrips("ErasureAnswer", ErasureAnswer("p-1", erased = true, Vector("addressDigest")))
  roundTrips("Windows", Windows(30, 14))
  roundTrips("NetworkBody", NetworkBody(Some("n1"), Some("https://n1/keys"), Some("https://n1/revoked"), None, None,
    Some(true), Some("machine|org|n1")))
  private val network = NetworkAnswer("n1", "https://n1/keys", "https://n1/revoked", Some("https://n1/legacy"), None,
    roundStream = true, Some("machine|org|n1"), pseudonymKeyMade = true, None,
    Vector(DocumentStatus("keys", Some(at), Some(at.plusSeconds(60)), fresh = true, None)))
  roundTrips("NetworkAnswer", network)
  roundTrips("Networks", Networks(Vector(network)))
  roundTrips("PseudonymKeyAnswer", PseudonymKeyAnswer("n1", made = true))
  roundTrips("Status", Status(Vector(NetworkStatus("n1", reachable = true, Some(at), 3, 0)),
    Transition(Some("2026-10-01"), Some("2026-11-01"), None, None, 12), openDays = 4))
  roundTrips("RecordAnswer", RecordAnswer("p-1", Some(at),
    Some(BindingAnswer("n1", "a1", "l1", Some("autumn"), Vector("s1"), Some("c-1"), at, "tag")),
    None, Vector(TouchAnswer("code", "gold", at, "later")), Some(at), None, None))
  roundTrips("SigningKeys", SigningKeys(Vector(SigningKeyAnswer("k1", "MCow", at, None))))
  roundTrips("SubscriptionBody", SubscriptionBody(Some("n1"), Some("https://hooks.n1/in"), Some("s"), Some(true)))
  private val subscription = SubscriptionAnswer("w1", "n1", "https://hooks.n1/in", active = true, Some(at), None)
  roundTrips("SubscriptionAnswer", subscription)
  roundTrips("Subscriptions", Subscriptions(Vector(subscription)))
  roundTrips("Undelivered", Undelivered(Vector(UndeliveredAnswer("w1/click/c-1", "w1", "n1", "click/c-1", "click", 8,
    Some(503), None))))

  test("an absent value is left out, an empty list is written"):
    assertEquals(writeToString(ErasureAnswer("p-1", erased = false)), """{"player":"p-1","erased":false,"personalFields":[]}""")

  test("a refusal's body has its code, its message and, when invalid, the field"):
    val r = Wire.invalid("address", "the address is not https")
    assertEquals(r.status, 400)
    val body = String(r.body.body, UTF_8)
    assertEquals(readFromString[Refusal](body), Refusal("INVALID_REQUEST", "the address is not https", Some("address")))

  test("a route not yet built answers 501 naming it"):
    val r = Wire.notBuilt("GET …/attribution/keys")
    assertEquals(r.status, 501)
    assertEquals(readFromString[Refusal](String(r.body.body, UTF_8)), Refusal("not_built", "GET …/attribution/keys"))

  test("a body not of the route's shape is refused with a reason"):
    assert(Wire.read[Windows]("""{"attributionWindowDays":"thirty"}""").isLeft)
    assertEquals(Wire.read[Windows]("""{"attributionWindowDays":30,"bonusCodeWindowDays":14}"""), Right(Windows(30, 14)))

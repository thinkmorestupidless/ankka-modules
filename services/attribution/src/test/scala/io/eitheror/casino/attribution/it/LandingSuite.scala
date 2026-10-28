package io.eitheror.casino.attribution.it

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.attribution.Json
import io.eitheror.casino.attribution.Json.*
import io.eitheror.casino.attribution.clicks.{Click, ClickRecord}
import io.eitheror.casino.contract.facts.{AffiliatesAttribution, Click as ClickFact}

import java.util.UUID
import scala.concurrent.duration.*

/** The landing (US1, FR-006, FR-010; quickstart §4): one click per click id, its fact, and the refusals. */
class LandingSuite extends munit.FunSuite with LogCapturing:
  override val munitTimeout: Duration = 300.seconds

  private def kit = SharedKit.kit
  override def beforeEach(context: BeforeEach): Unit = kit.reset()

  private val Path = "/v1/track/click"

  private def click(fields: (String, Any)*)(headers: (String, String)*) =
    kit.request("POST", Path, Some(Json.render(Json.obj(fields*))), kit.fromInternet ++ headers)

  private def stored(id: String): Option[ClickRecord] =
    kit.components.forKeyValueEntity(EntityId(id)).call(Click.get).invoke().click

  private def clickFacts(id: String) =
    kit.attributionMessages.filter(_.message.metadata.get("ce-id").contains(s"click/$id"))

  test("a valid tag records the click with every field, answers its id, and emits one click fact keyed by the link") {
    val id   = kit.fresh("c")
    val link = kit.fresh("lnk")
    val tag  = kit.network.mint("aff-1", link, Some("autumn"), Vector("s1", "s2"))
    val r = click("btag" -> tag, "clickId" -> id, "deviceType" -> "mobile", "country" -> "ZA", "utmSource" -> "network",
      "utmMedium" -> "affiliate", "utmCampaign" -> "autumn", "utmContent" -> "banner-1", "utmTerm" -> "slots")(
      "X-Forwarded-For" -> "203.0.113.9, 10.0.0.1")
    assertEquals(r.status, 200, r.text)
    assertEquals(r.json, Json.parse(s"""{"ok":true,"clickId":"$id"}""").get)

    val c = stored(id).getOrElse(fail("no click"))
    assertEquals((c.network, c.affiliate, c.link, c.campaign, c.subIds), (Some("programme"), Some("aff-1"), Some(link), Some("autumn"),
      Vector("s1", "s2")))
    assertEquals((c.deviceType, c.country, c.refused), ("mobile", Some("ZA"), None))
    assertEquals(c.utm, Map("utm_source" -> "network", "utm_medium" -> "affiliate", "utm_campaign" -> "autumn",
      "utm_content" -> "banner-1", "utm_term" -> "slots"))
    assert(c.addressDigest.exists(d => d.nonEmpty && !d.contains("203.0.113.9")), c.addressDigest.toString)

    val message = kit.eventually("the click fact")(clickFacts(id).headOption)
    assertEquals(message.message.key, Some(link))
    AffiliatesAttribution.serializer.fromBytes(message.message.payload) match
      case f: ClickFact => assertEquals((f.country, f.deviceType, f.clickId), (Some("ZA"), Some("mobile"), Some(id)))
      case other        => fail(s"not a click: $other")

    val again = click("btag" -> tag, "clickId" -> id, "deviceType" -> "mobile")()
    assertEquals(again.json, r.json)
    Thread.sleep(1500)
    assertEquals(clickFacts(id).size, 1)
  }

  test("no clickId: a UUID is answered and is the fact's id") {
    val r = click("btag" -> kit.network.mint("aff-1", kit.fresh("lnk")))()
    assertEquals(r.status, 200)
    val id = r.json.textOr("clickId", "")
    assert(scala.util.Try(UUID.fromString(id)).isSuccess, id)
    kit.eventually("the click fact under the minted id")(clickFacts(id).headOption)
  }

  test("no btag is 400; a malformed tag is ok false with the reason and no fact") {
    val missing = click("clickId" -> kit.fresh("c"))()
    assertEquals((missing.status, missing.json.textOr("error", "")), (400, "btag is required"))
    val id = kit.fresh("c")
    val bad = click("btag" -> "nonsense", "clickId" -> id)()
    assertEquals(bad.status, 200)
    assertEquals(bad.json, Json.parse(s"""{"ok":false,"clickId":"$id","reason":"malformed"}""").get)
    assertEquals(stored(id).flatMap(_.refused), Some("malformed"))
    Thread.sleep(1500)
    assertEquals(clickFacts(id), Vector.empty)
  }

  test("the device from the user agent; the country from the gateway's header; neither, absent") {
    val tag = kit.network.mint("aff-1", kit.fresh("lnk"))
    val a = kit.fresh("c")
    click("btag" -> tag, "clickId" -> a)("User-Agent" -> "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X)", "CF-IPCountry" -> "GB")
    assertEquals(stored(a).map(c => (c.deviceType, c.country)), Some(("mobile", Some("GB"))))
    val b = kit.fresh("c")
    click("btag" -> tag, "clickId" -> b)()
    assertEquals(stored(b).flatMap(_.country), None)
  }

  test("no answer sets a cookie, and a click with no consent header or cookie is recorded like any other (FR-006)") {
    val id = kit.fresh("c")
    val r = click("btag" -> kit.network.mint("aff-1", kit.fresh("lnk")), "clickId" -> id)()
    assertEquals(r.header("Set-Cookie"), None)
    assert(stored(id).isDefined)
  }

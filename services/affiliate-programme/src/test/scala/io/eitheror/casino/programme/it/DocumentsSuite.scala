package io.eitheror.casino.programme.it

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.attribution.TagOutcome
import io.eitheror.casino.programme.accounts.{CloseAccount, Affiliate, Secrets}
import io.eitheror.casino.programme.brands.{BrandProgramme, Plan, UpdateBrand}
import io.eitheror.casino.programme.links.{AddKey, CreateLink, Link, RetireKey, SigningKeys}
import io.eitheror.casino.programme.tags.TagSigner

import java.util.UUID
import scala.jdk.CollectionConverters.*

/**
 * The documents a brand fetches (US5, contracts/routes.md, research R10). In a JVM of its own: it
 * rotates the signing key and withdraws the legacy tag document, which no other suite may see.
 */
class DocumentsSuite extends munit.FunSuite with LogCapturing:
  override val munitTimeout = scala.concurrent.duration.Duration(180, "s")
  private def kit   = SharedKit.kit
  private val brand = BrandSide(kit)

  override def beforeEach(context: BeforeEach): Unit = { kit.rewire(); kit.reset() }

  private def affiliate(): String = kit.affiliate(kit.fresh("aff"), "goldvibe", kit.plan("goldvibe", kit.fresh("p"), Plan.Cpa, cpa = 5_000))

  private def link(aff: String, body: String = """{"brandId":"goldvibe","campaign":"summer","subIds":["s1","s2"]}"""): (String, String) =
    val r = kit.request("POST", s"/v1/my/$aff/tracking-links", Some(body), kit.asAffiliate(aff))
    assertEquals(r.status, 201, r.text)
    (r.json.get("btag").asText, r.json.get("tag").asText)

  test("a link's tag verifies against the served key document, naming the programme, the affiliate, the link and the sub-ids") {
    val aff         = affiliate()
    val (id, tag)   = link(aff)
    brand.verify(tag, brand.keys) match
      case TagOutcome.Valid(named, _, _) =>
        assertEquals((named.network, named.affiliate, named.link, named.campaign, named.subIds), ("programme", aff, id, Some("summer"), Vector("s1", "s2")))
      case other => fail(s"refused: $other")
    val doc = kit.request("GET", "/v1/programme/documents/keys", headers = kit.fromInternet)
    assertEquals(doc.header("Cache-Control"), Some("max-age=3600"))
    assert(!doc.json.has("transition"), doc.text)
  }

  test("a revoked link, and a closed account, are on the revocation list within a refresh") {
    val aff       = affiliate()
    val (id, tag) = link(aff)
    kit.components.forKeyValueEntity(EntityId(id)).call(Link.revoke).invoke(kit.now): Unit
    val closed = affiliate()
    kit.components.forEventSourcedEntity(EntityId(closed)).call(Affiliate.close).invoke(CloseAccount("mgr-1", kit.now)): Unit
    val (links, affiliates) = kit.eventually("both revoked")(Option(brand.revocations).filter((l, a) => l(id) && a(closed)))
    assertEquals(brand.verify(tag, brand.keys, links, affiliates), TagOutcome.Refused(BrandSide.RevokedLink))
    assertEquals(kit.request("GET", "/v1/programme/documents/revocations", headers = kit.fromInternet).json.get("refreshSeconds").asInt, 300)
  }

  test("the code document lists live codes, and its version rises with each change") {
    val aff     = affiliate()
    val (id, _) = link(aff)
    val text    = s"C${UUID.randomUUID().toString.take(6)}"
    def doc     = kit.request("GET", "/v1/programme/documents/codes/goldvibe", headers = kit.fromInternet).json
    def listed  = doc.get("codes").elements().asScala.exists(_.get("code").asText == text.toUpperCase)
    val before  = doc.get("version").asInt
    val r = kit.request("POST", s"/v1/my/$aff/bonus-codes", Some(s"""{"brandId":"goldvibe","code":"$text","btag":"$id","offer":"offer-3"}"""),
      kit.asAffiliate(aff))
    assertEquals(r.status, 201, r.text)
    kit.eventually("listed")(Option(doc).filter(d => listed && d.get("version").asInt == before + 1)): Unit
    val entry = doc.get("codes").elements().asScala.find(_.get("code").asText == text.toUpperCase).get
    assertEquals(entry.get("offer").asText, "offer-3")
    assert(brand.verify(entry.get("tag").asText, brand.keys).isInstanceOf[TagOutcome.Valid])
    assertEquals(kit.request("DELETE", s"/v1/my/$aff/bonus-codes/$text?brand=goldvibe", headers = kit.asAffiliate(aff)).status, 200)
    kit.eventually("gone")(Option(doc).filter(d => !listed && d.get("version").asInt == before + 2)): Unit
    assertEquals(kit.request("GET", "/v1/programme/documents/codes/nowhere", headers = kit.fromInternet).status, 404)
  }

  test("a key rotated in keeps the old one verifying until it is retired") {
    val aff        = affiliate()
    val (_, old)   = link(aff)
    val keys       = kit.components.forKeyValueEntity(EntityId(SigningKeys.Id))
    val oldKid     = keys.call(SigningKeys.get).invoke().signing.get.kid
    val (x, priv8) = TagSigner.generate()
    val kid        = s"k-${UUID.randomUUID().toString.take(8)}"
    kit.secrets.put(Secrets.signingKey(kid), priv8)
    keys.call(SigningKeys.add).invoke(AddKey(kid, x, kit.now.plusSeconds(1))): Unit
    val (_, fresh) = link(aff)
    assertEquals(brand.keys.keySet, Set(oldKid, kid))
    assert(brand.verify(old, brand.keys).isInstanceOf[TagOutcome.Valid])
    assertEquals(brand.verify(fresh, brand.keys).asInstanceOf[TagOutcome.Valid].kid, kid)
    keys.call(SigningKeys.retire).invoke(RetireKey(oldKid, kit.now)): Unit
    assertEquals(brand.verify(old, brand.keys), TagOutcome.Refused(BrandSide.WithdrawnKey))
  }

  test("the legacy tag document maps imported tags until every brand that had them ends its transition") {
    val aff  = affiliate()
    val uuid = UUID.randomUUID().toString
    val id   = Link.newId()
    kit.components.forKeyValueEntity(EntityId(id)).call(Link.createLink)
      .invoke(CreateLink(id, aff, "goldvibe", None, None, Map.empty, Vector.empty, None, Some(uuid.toUpperCase), "import", kit.now)): Unit
    def doc = kit.request("GET", "/v1/programme/documents/legacy-tags", headers = kit.fromInternet)
    val mapped = kit.eventually("imported")(Option(doc.json.get("tags").get(uuid)))
    assertEquals((mapped.get("affiliate").asText, mapped.get("link").asText), (aff, id))
    val keyDoc = BrandKeys()
    try
      kit.components.forKeyValueEntity(EntityId("goldvibe")).call(BrandProgramme.update)
        .invoke(UpdateBrand(keyDocumentUrl = Some(keyDoc.url("goldvibe")))): Unit
      val refused = kit.request("POST", "/v1/programme/documents/legacy-tags/withdraw", headers = kit.asPlatformAdmin())
      assertEquals((refused.status, refused.json.get("error").asText, refused.json.get("message").asText), (409, "transition_not_ended", "goldvibe"))
      assertEquals(doc.status, 200)
      keyDoc.endedOn = Some("2027-01-15")
      assertEquals(kit.request("POST", "/v1/programme/documents/legacy-tags/withdraw", headers = kit.asPlatformAdmin()).status, 200)
      assertEquals(doc.status, 404)
    finally keyDoc.stop()
  }

  test("a manager makes a link for an affiliate of the brand, signed as the affiliate's own, and lists the brand's") {
    val aff = affiliate()
    val r = kit.request("POST", "/v1/affiliates/goldvibe/tracking-links", Some(s"""{"affiliateId":"$aff","campaign":"launch","landingPath":"/slots"}"""),
      kit.asManager())
    assertEquals(r.status, 201, r.text)
    val id = r.json.get("btag").asText
    assert(r.json.get("landingUrl").asText.startsWith(s"/slots?btag=${r.json.get("tag").asText}"), r.text)
    assert(brand.verify(r.json.get("tag").asText, brand.keys).isInstanceOf[TagOutcome.Valid])
    kit.eventually("listed")(kit.request("GET", "/v1/affiliates/goldvibe/tracking-links", headers = kit.asManager()).json.elements().asScala
      .find(_.get("btag").asText == id)): Unit
    assertEquals(kit.request("POST", "/v1/affiliates/goldvibe/tracking-links", Some("""{"affiliateId":"nobody"}"""), kit.asManager()).status, 404)
  }

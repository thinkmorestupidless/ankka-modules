package io.eitheror.casino.kyc.port

import com.fasterxml.jackson.databind.JsonNode
import io.eitheror.casino.kyc.Json
import io.eitheror.casino.kyc.port.WireTranslation.*

/** contracts/wire.md: one test per row, and a path the table does not name is not translated. */
class WireTranslationSuite extends munit.FunSuite:

  private def json(s: String): JsonNode = Json.parse(s).get
  private val key   = "subjects/p-1/kyc/identity-1700000000000.png"
  private val offer = Offer("d-1", "http://garage/kyc/signed", "image/png")
  private val ctx   = Context("goldvibe", Some("p-1"), Map(key -> offer))
  private val base  = "/v1/casino/spinvibe/players/p-1/kyc"

  private def req(method: String, path: String, body: String = "", operator: Boolean = false): Request =
    WireTranslation.request(method, path, Option.when(body.nonEmpty)(json(body)), ctx, operator)
      .getOrElse(fail(s"$method $path is not translated"))

  private def answered(r: Request, status: Int, observed: String, recorded: String = "", body: String = ""): (Int, Option[String]) =
    val (s, a) = WireTranslation.answer(r, status, Option.when(observed.nonEmpty)(json(observed)),
      Option.when(recorded.nonEmpty)(json(recorded)), ctx, Option.when(body.nonEmpty)(json(body)))
    (s, a.map(Json.render))

  test("upload-url is an upload offered, its object name answered as the key and the document id captured beside it"):
    val r = req("POST", s"$base/upload-url", """{"docType":"identity","contentType":"image/png"}""")
    assertEquals((r.method, r.path, r.body.map(Json.render)),
      ("POST", "/v1/casino/kyc/goldvibe/p-1/uploads", Some("""{"category":"identity","contentType":"image/png"}""")))
    val ours = s"""{"documentId":"d-1","url":"u","objectName":"$key","contentType":"image/png","expiresAt":"2026-10-30T12:00:00Z"}"""
    assertEquals(answered(r, 200, ours), (200, Some(s"""{"url":"u","key":"$key","contentType":"image/png"}""")))
    assertEquals(WireTranslation.offered(json(ours)), Some(key -> Offer("d-1", "u", "image/png")))

  test("a document recorded is the signed PUT of the offered type, then a confirmation of the document id"):
    val r = req("POST", s"$base/documents", s"""{"key":"$key","docType":"identity"}""")
    assertEquals((r.method, r.path, r.body.map(Json.render), r.put),
      ("POST", "/v1/casino/kyc/goldvibe/p-1/documents", Some("""{"documentId":"d-1"}"""), Some(offer)))
    assertEquals(answered(r, 200, """{"document":{"documentId":"d-1"},"status":"pending"}"""), (200, Some("""{"status":"recorded"}""")))

  test("the player's documents are the original's list: key, type, review status and reason, required types, status"):
    val r = req("GET", s"$base/documents")
    assertEquals(r.path, "/v1/casino/kyc/goldvibe/p-1/documents")
    val ours =
      """{"status":"pending","mode":"automatic","missingProfileFields":[],"other":[],"categories":[
        |{"category":"identity","requiredByPolicy":true,"requiredOfPlayer":false,
        | "document":{"documentId":"d-1","category":"identity","status":"rejected","uploadedAt":"t1","reviewedAt":"t2","reasonCode":"blurry"}},
        |{"category":"proof_of_address","requiredByPolicy":false,"requiredOfPlayer":false}]}""".stripMargin
    assertEquals(answered(r, 200, ours)._2, Some(
      s"""{"documents":[{"key":"$key","docType":"identity","uploadedAt":"t1","reviewStatus":"rejected","status":"REJECTED",""" +
        """"rejectReason":"blurry","rejectionReason":"blurry","reviewedAt":"t2"}],"requiredDocTypes":[],"kycStatus":"pending"}"""))

  test("the required types are those required of the player, never the policy's, as the original's kyc_required_doc_types"):
    val ours = """{"status":"unverified","categories":[{"category":"identity","requiredByPolicy":true,"requiredOfPlayer":false},
      |{"category":"proof_of_address","requiredByPolicy":false,"requiredOfPlayer":true}]}""".stripMargin
    assertEquals(answered(req("GET", s"$base/documents"), 200, ours)._2,
      Some("""{"documents":[],"requiredDocTypes":["proof_of_address"],"kycStatus":"unverified"}"""))

  test("view-url is the player's view, or the operator's when the step was an operator's; the answer is unchanged"):
    assertEquals(req("GET", s"$base/documents/$key/view-url").path, "/v1/casino/kyc/goldvibe/p-1/views/d-1")
    val op = req("GET", s"$base/documents/$key/view-url", operator = true)
    assertEquals(op.path, "/v1/casino/backoffice/goldvibe/kyc/p-1/views/d-1")
    assertEquals(answered(op, 200, """{"url":"u","expiresAt":"t"}"""), (200, Some("""{"url":"u","expiresAt":"t"}""")))

  test("approve is an approved review, answered with the document's key and status and the record's"):
    val r = req("PATCH", s"$base/documents/$key/approve")
    assertEquals((r.method, r.path, r.body.map(Json.render)),
      ("POST", "/v1/casino/backoffice/goldvibe/kyc/p-1/reviews/d-1", Some("""{"outcome":"approved"}""")))
    assertEquals(answered(r, 200, """{"document":{"documentId":"d-1","status":"approved"},"status":"verified"}""")._2,
      Some(s"""{"docKey":"$key","status":"APPROVED","kycStatus":"verified"}"""))

  test("reject is a rejected review with the reason as its code"):
    val r = req("PATCH", s"$base/documents/$key/reject", """{"reason":"blurry"}""")
    assertEquals(r.body.map(Json.render), Some("""{"outcome":"rejected","reasonCode":"blurry"}"""))
    assertEquals(answered(r, 200, """{"document":{"documentId":"d-1","status":"rejected"},"status":"rejected"}""")._2,
      Some(s"""{"docKey":"$key","status":"REJECTED","kycStatus":"rejected"}"""))

  test("verification-mode is the mode"):
    val r = req("PUT", s"$base/verification-mode", """{"mode":"manual"}""")
    assertEquals((r.method, r.path), ("PUT", "/v1/casino/backoffice/goldvibe/kyc/p-1/mode"))
    assertEquals(answered(r, 200, """{"mode":"manual","status":"pending","readyAt":null}""")._2, Some("""{"mode":"manual"}"""))

  test("status is the status, answered as kycStatus"):
    val r = req("PUT", s"$base/status", """{"status":"pending"}""")
    assertEquals((r.path, r.body.map(Json.render)), ("/v1/casino/backoffice/goldvibe/kyc/p-1/status", Some("""{"status":"pending"}""")))
    assertEquals(answered(r, 200, """{"status":"pending"}""")._2, Some("""{"kycStatus":"pending"}"""))

  test("required-doc-types is the categories required, answered with the mode and status"):
    val r = req("PUT", s"$base/required-doc-types", """{"docTypes":["identity","proof_of_address"]}""")
    assertEquals((r.path, r.body.map(Json.render)),
      ("/v1/casino/backoffice/goldvibe/kyc/p-1/required", Some("""{"categories":["identity","proof_of_address"]}""")))
    assertEquals(answered(r, 200, """{"categories":["identity"],"mode":"manual","status":"pending"}""")._2,
      Some("""{"docTypes":["identity"],"verificationMode":"manual","kycStatus":"pending"}"""))

  test("verify and reject are overrides with the reason"):
    val v = req("PATCH", s"$base/verify", """{"reason":"checked by phone"}""")
    assertEquals((v.method, v.path, v.body.map(Json.render)),
      ("POST", "/v1/casino/backoffice/goldvibe/kyc/p-1/override", Some("""{"status":"verified","reason":"checked by phone"}""")))
    assertEquals(req("PATCH", s"$base/reject", """{"reason":"fraud"}""").body.map(Json.render), Some("""{"status":"rejected","reason":"fraud"}"""))
    assertEquals(answered(v, 200, """{"status":"verified","by":"op-1","at":"t"}""")._2, Some("""{"kycStatus":"verified"}"""))

  test("the backoffice approve is an override to verified, answered with who, when and why"):
    val body = """{"reason":"documents checked"}"""
    val r    = req("POST", "/v1/casino/backoffice/spinvibe/p-1/kyc/approve", body)
    assertEquals((r.path, r.body.map(Json.render)),
      ("/v1/casino/backoffice/goldvibe/kyc/p-1/override", Some("""{"status":"verified","reason":"documents checked"}""")))
    assertEquals(answered(r, 200, """{"status":"verified","by":"op-1","at":"t"}""", body = body)._2,
      Some("""{"playerId":"p-1","kycStatus":"verified","approvedBy":"op-1","approvedAt":"t","reason":"documents checked"}"""))

  test("the policy's three routes move under the kyc prefix, answered with null fields left out"):
    assertEquals(req("GET", "/v1/casino/backoffice/spinvibe/kyc-policy").path, "/v1/casino/backoffice/goldvibe/kyc/policy")
    val put = req("PUT", "/v1/casino/backoffice/spinvibe/kyc-policy", """{"kycWindowDays":3}""")
    assertEquals((put.method, put.path, put.body.map(Json.render)), ("PUT", "/v1/casino/backoffice/goldvibe/kyc/policy", Some("""{"kycWindowDays":3}""")))
    val profile = req("POST", "/v1/casino/backoffice/spinvibe/kyc-policy/profile", """{"profile":"UKGC"}""")
    assertEquals(profile.path, "/v1/casino/backoffice/goldvibe/kyc/policy/profile")
    assertEquals(answered(profile, 200, """{"profile":"UKGC","kycWindowDays":null,"depositThreshold":200000}""")._2,
      Some("""{"profile":"UKGC","depositThreshold":200000}"""))

  test("the requirements are the public route, unchanged"):
    val r = req("GET", "/v1/casino/players/spinvibe/kyc/requirements")
    assertEquals(r.path, "/v1/casino/kyc-requirements/goldvibe")
    assertEquals(answered(r, 200, """{"requiredDocTypes":["identity"]}""")._2, Some("""{"requiredDocTypes":["identity"]}"""))

  test("the deposit notification is the deposit check, as players, in minor units; a refusal is the original's 403"):
    val r = req("POST", "/internal/v1/casino/spinvibe/notify/deposit", """{"playerId":"p-1","amount":25.5,"currency":"GBP"}""")
    assertEquals((r.method, r.path, r.caller, r.body.map(Json.render)), ("POST", "/v1/casino/brands/goldvibe/kyc/deposit-check",
      Caller.Players, Some("""{"playerId":"p-1","amount":2550,"currency":"GBP"}""")))
    assertEquals(answered(r, 200, """{"allowed":false,"code":"DEPOSIT_BLOCKED_KYC","message":"m","kycStatus":"pending"}"""),
      (403, Some("""{"error":"DEPOSIT_BLOCKED_KYC","kycStatus":"pending","message":"m"}""")))
    assertEquals(answered(r, 200, """{"allowed":true}""", recorded = """{"ok":true}"""), (200, Some("""{"ok":true}""")))

  test("the launch is the play check, as game-provider, for the player signed in; a refusal is the original's 403"):
    val r = req("POST", "/v1/casino/sessions/spinvibe/launch", """{"gameId":"g-1"}""")
    assertEquals((r.method, r.path, r.caller), ("GET", "/v1/casino/brands/goldvibe/kyc/p-1/play-check", Caller.GameProvider))
    assertEquals(answered(r, 200, """{"allowed":false,"error":"PLAY_BLOCKED_KYC","code":"PLAY_BLOCKED_KYC","kycStatus":"unverified","message":"m"}"""),
      (403, Some("""{"error":"PLAY_BLOCKED_KYC","kycStatus":"unverified","message":"m"}""")))

  test("a refusal passes through, keeping only the fields the original answered"):
    val r = req("POST", s"$base/upload-url", """{"docType":"selfie","contentType":"image/png"}""")
    assertEquals(answered(r, 400, """{"error":"invalid_category","message":"m","category":"selfie"}""", recorded = """{"error":"x"}"""),
      (400, Some("""{"error":"invalid_category"}""")))

  test("a path the table does not name is not translated"):
    Seq("/v1/casino/players/spinvibe/register", "/v1/casino/spinvibe/players/p-1/suspend", "/health")
      .foreach(p => assertEquals(WireTranslation.request("POST", p, None, ctx), None))

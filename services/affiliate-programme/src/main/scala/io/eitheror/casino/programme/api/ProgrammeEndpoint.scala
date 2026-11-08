package io.eitheror.casino.programme.api

import com.thinkmorestupidless.ankka.http.*
import io.eitheror.casino.programme.api.HostingAnswers.{BrandBody, BrandPatch, given}
import io.eitheror.casino.programme.api.Wire.*
import io.eitheror.casino.programme.imports.Bundle

/**
 * The programme's own surface (contracts/routes.md `/v1/programme`): the documents and the inbound
 * webhooks, public (the webhook checks its own signature); hosted brands, the legacy withdrawal, the
 * usage report and a brand's import for a platform administrator.
 */
final class ProgrammeEndpoint(api: Api) extends HttpEndpoint("/v1/programme"):
  val acl: Acl = Acl.DenyAll

  /** The webhook's body as it arrived: its signature is over these bytes. */
  private given rawBody: FromBody[Array[Byte]] = new FromBody[Array[Byte]]:
    val contentType                                          = "application/json"
    def read(bytes: Array[Byte]): Either[String, Array[Byte]] = Right(bytes)

  private val documents = Documents(api)
  private val inbound   = Inbound(api)
  private val hosting   = Hosting(api)
  private val imports   = Imports(api)

  withAcl(Access.public) {
    get("/documents/keys") { () => attempt(documents.keys) }
    get("/documents/revocations") { () => attempt(documents.revocations) }
    get("/documents/legacy-tags") { () => attempt(documents.legacyTags) }
    get("/documents/codes/{brand}") { (brand: String) => attempt(documents.codes(brand)) }
    // A brand elsewhere or hosted: its facts, signed with its webhook secret (contracts/inbound.md).
    postBody("/inbound/{brand}") { (brand: String, body: Array[Byte]) =>
      attempt(inbound.receive(brand, body, request.header))
    }
  }

  withAcl(api.access.platformAdmin) {
    post("/documents/legacy-tags/withdraw") { () => attempt(documents.withdraw(principal.subject)) }
    get("/brands") { () => attempt(hosting.list) }
    postBody("/brands") { (body: String) => attempt(withBody[BrandBody](body)(hosting.add(_, principal.subject))) }
    patchBody("/brands/{brand}") { (brand: String, body: String) =>
      attempt(withBody[BrandPatch](body)(hosting.patch(brand, _, principal.subject)))
    }
    postBody("/imports/{brand}") { (brand: String, body: String) =>
      attempt(withBody[Bundle](body)(imports.start(brand, _, body, principal.subject)))
    }
    get("/brands/{brand}/usage") { (brand: String) => attempt(hosting.usage(brand, query.raw("month").map(_.trim))) }
  }

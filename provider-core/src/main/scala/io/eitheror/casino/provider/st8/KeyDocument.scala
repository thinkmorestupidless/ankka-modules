package io.eitheror.casino.provider.st8

import com.thinkmorestupidless.ankka.http.*
import io.eitheror.casino.contract.access.Routes

/** The key document (spec 020 FR-011; contracts/st8-wire.md): what a test brand names as its provider key. */
final class KeyDocument(keys: Keys) extends HttpEndpoint("/.well-known"):
  val acl: Acl = Routes.public
  get("/st8-keys")(() => Http.json(200, keys.document()))

/** The stand-in's health (`main.go:717-727`): ready once the service says it is. */
final class Health(ready: () => Boolean) extends HttpEndpoint("/health"):
  val acl: Acl = Routes.public
  get("/") { () =>
    if ready() then Http.raw(200, "application/json", """{"status":"ready"}""")
    else Http.raw(503, "application/json", """{"status":"starting"}""")
  }

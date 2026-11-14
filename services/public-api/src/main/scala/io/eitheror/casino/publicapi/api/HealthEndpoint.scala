package io.eitheror.casino.publicapi.api

import com.thinkmorestupidless.ankka.http.{Acl, Bytes, HttpEndpoint, Respond}

import java.nio.charset.StandardCharsets.UTF_8

/** `GET /health` → `{"status":"ok"}`, no key (original `server.ts:117-120`). */
final class HealthEndpoint extends HttpEndpoint("/health"):
  val acl: Acl = Acl.AllowAll
  get("") { () => Respond(Bytes("application/json", """{"status":"ok"}""".getBytes(UTF_8)), 200) }

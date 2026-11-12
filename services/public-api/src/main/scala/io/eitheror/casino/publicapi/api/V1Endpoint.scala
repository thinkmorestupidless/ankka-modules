package io.eitheror.casino.publicapi.api

import com.thinkmorestupidless.ankka.http.{Acl, Bytes, HttpEndpoint, Html, Respond}
import io.eitheror.casino.publicapi.contract.{Contract, Operation, Refusals}

import java.nio.charset.StandardCharsets.UTF_8

/**
 * `/v1`: the public contract (contracts/routes.md, research R3). The ACL admits every caller, so every refusal is the
 * contract's `{error}` JSON, shaped here. Each operation of the contract file is declared for GET, by its number of
 * path ids, and for POST, PUT, PATCH and DELETE answering `405 invalid_request`. Paths the contract does not hold are
 * caught one and two segments deep, and three deep under each of its collections, and answered as the original answers
 * an unknown path: `401` without a key, `404` with one (original `router.ts:41-64`).
 *
 * `serve` answers an operation; until a story binds one it is `501`.
 */
final class V1Endpoint(contract: Contract, serve: (Operation, Call) => Respond[Bytes], unknown: Call => Respond[Bytes])
    extends HttpEndpoint("/v1"):
  val acl: Acl = Acl.AllowAll

  private def call(op: Option[Operation], names: Vector[String], values: Vector[String]): Call =
    Call(op, names.zip(values), request.query.toSeq.toVector, request.header("Authorization"), request.path)

  private val Public = Set("/openapi.json", "/docs")

  get("/openapi.json") { () => Respond(Bytes("application/json", contract.json.getBytes(UTF_8)), 200) }
  get("/docs") { () => Html(contract.reference) }

  contract.operations.filterNot(o => Public(o.template)).foreach { op =>
    val names = "\\{([A-Za-z_][A-Za-z0-9_]*)\\}".r.findAllMatchIn(op.template).map(_.group(1)).toVector
    names.size match
      case 0 => get(op.template) { () => serve(op, call(Some(op), names, Vector.empty)) }
      case 1 => get(op.template) { (a: String) => serve(op, call(Some(op), names, Vector(a))) }
      case _ => get(op.template) { (a: String, b: String) => serve(op, call(Some(op), names, Vector(a, b))) }
    onlyGet(op.template, names.size)
  }
  onlyGet("/openapi.json", 0)
  onlyGet("/docs", 0)

  private def onlyGet(template: String, arity: Int): Unit = arity match
    case 0 =>
      post(template) { () => Refusals.onlyGet.respond }; put(template) { () => Refusals.onlyGet.respond }
      patch(template) { () => Refusals.onlyGet.respond }; delete(template) { () => Refusals.onlyGet.respond }
    case 1 =>
      post(template) { (_: String) => Refusals.onlyGet.respond }; put(template) { (_: String) => Refusals.onlyGet.respond }
      patch(template) { (_: String) => Refusals.onlyGet.respond }; delete(template) { (_: String) => Refusals.onlyGet.respond }
    case _ =>
      post(template) { (_: String, _: String) => Refusals.onlyGet.respond }; put(template) { (_: String, _: String) => Refusals.onlyGet.respond }
      patch(template) { (_: String, _: String) => Refusals.onlyGet.respond }; delete(template) { (_: String, _: String) => Refusals.onlyGet.respond }

  // Paths the contract does not hold (literal segments outrank these in dispatch, so a contract path is never caught).
  get("/{a}") { (_: String) => unknown(call(None, Vector.empty, Vector.empty)) }
  get("/{a}/{b}") { (_: String, _: String) => unknown(call(None, Vector.empty, Vector.empty)) }
  contract.operations.map(_.template.split('/').filter(_.nonEmpty).head).distinct.filterNot(s => s.startsWith("{") || s.contains('.'))
    .foreach(first => get(s"/$first/{a}/{b}") { (_: String, _: String) => unknown(call(None, Vector.empty, Vector.empty)) })

object V1Endpoint:
  /** Until a story binds an operation. */
  val notYet: Respond[Bytes] = Refusals.json(501, Refusals.body("not_implemented", None))

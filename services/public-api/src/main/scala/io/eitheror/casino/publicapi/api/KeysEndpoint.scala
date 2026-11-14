package io.eitheror.casino.publicapi.api

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import com.fasterxml.jackson.databind.node.ObjectNode
import com.thinkmorestupidless.ankka.core.{CommandError, EntityId, ErrorCode}
import com.thinkmorestupidless.ankka.http.{Acl, Bytes, EndpointClients, HttpEndpoint, Respond}
import io.eitheror.casino.contract.access.Routes
import io.eitheror.casino.publicapi.contract.Refusals
import io.eitheror.casino.publicapi.keys.*
import io.eitheror.casino.publicapi.wire.Times

import java.time.Instant
import scala.jdk.CollectionConverters.*
import scala.util.Try

/**
 * `/access-keys/{brand}`: the brand's keys, for an operator holding the access keys permission or `platform_admin`
 * (contracts/routes.md). Another brand's segment is `404`. The secret is in the creation's answer only; every change
 * carries the operator's subject as `by`.
 */
final class KeysEndpoint(slug: String, clients: EndpointClients, authenticate: () => Acl, now: () => Instant)
    extends HttpEndpoint("/access-keys"):
  import KeysEndpoint.*

  val acl: Acl = Routes.accessKeys(slug, authenticate())

  private def entity(keyId: String) = clients.componentClient.forEventSourcedEntity(EntityId(keyId))
  private def by: String            = principal.subject

  get("/{brand}") { (brand: String) =>
    own(brand) {
      val deleted = query.raw("deleted").contains("true")
      val rows    = clients.viewClient.forView(AccessKeys).ask(AccessKeys.listed, 1000, "deleted" -> deleted.toString)
      val keys    = mapper.createArrayNode()
      rows.foreach(r => keys.add(used(keyOfRow(r), r.keyId)))
      ok(200, mapper.createObjectNode().set("keys", keys))
    }
  }

  postBody("/{brand}") { (brand: String, body: String) =>
    own(brand) {
      parse(body).flatMap(creation(_, now())) match
        case Left((field, message)) => invalid(field, message)
        case Right(c) =>
          val (keyId, whole) = RawKeys.mint()
          val state = entity(keyId).call(AccessKey.create).invoke(c.copy(digest = RawKeys.digest(whole), by = by))
          ok(201, mapper.createObjectNode().set[ObjectNode]("key", keyOf(state)).put("secret", whole))
    }
  }

  get("/{brand}/{keyId}") { (brand: String, keyId: String) =>
    own(brand)(guarded(ok(200, used(keyOf(entity(keyId).call(AccessKey.get).invoke()), keyId))))
  }

  patchBody("/{brand}/{keyId}") { (brand: String, keyId: String, body: String) =>
    own(brand) {
      parse(body).flatMap(change(_, now(), by)) match
        case Left((field, message)) => invalid(field, message)
        case Right(c)               => guarded(ok(200, keyOf(entity(keyId).call(AccessKey.change).invoke(c))))
    }
  }

  post("/{brand}/{keyId}/disable") { (brand: String, keyId: String) =>
    own(brand)(guarded(ok(200, keyOf(entity(keyId).call(AccessKey.disable).invoke(By(by, now()))))))
  }

  post("/{brand}/{keyId}/enable") { (brand: String, keyId: String) =>
    own(brand)(guarded(ok(200, keyOf(entity(keyId).call(AccessKey.enable).invoke(By(by, now()))))))
  }

  delete("/{brand}/{keyId}") { (brand: String, keyId: String) =>
    own(brand)(guarded { entity(keyId).call(AccessKey.delete).invoke(By(by, now())): Unit; Respond(Bytes("application/json", Array.emptyByteArray), 204) })
  }

  /** `lastUsedAt` from the key's window, as of the `KeyUse` view (eventually consistent, FR-005). */
  private def used(n: ObjectNode, keyId: String): ObjectNode =
    clients.viewClient.forView(KeyUse).get(keyId).flatMap(_.lastUsedAt).foreach(x => n.put("lastUsedAt", Times.of(x)))
    n

  private def own(brand: String)(answer: => Respond[Bytes]): Respond[Bytes] =
    Routes.brandSegment(slug, brand).fold(p => throw p, _ => answer)

object KeysEndpoint:
  private val mapper = new ObjectMapper()

  def ok(status: Int, node: JsonNode): Respond[Bytes] = Refusals.json(status, mapper.writeValueAsString(node))

  private def refusal(status: Int, code: String, field: Option[String] = None, message: Option[String] = None): Respond[Bytes] =
    val n = mapper.createObjectNode().put("error", code)
    field.foreach(n.put("field", _))
    message.foreach(n.put("message", _))
    ok(status, n)

  def invalid(field: String, message: String): Respond[Bytes] = refusal(400, "invalid_request", Some(field), Some(message))

  /** An entity's refusal as the routes answer it: `404 not_found`, `409 key_deleted`. */
  def guarded(f: => Respond[Bytes]): Respond[Bytes] =
    try f
    catch
      case e: CommandError if e.code == ErrorCode.NotFound                              => refusal(404, "not_found")
      case e: CommandError if e.code == ErrorCode.Conflict && e.message == AccessKey.Deleted => refusal(409, AccessKey.Deleted)

  def keyOf(s: KeyState): ObjectNode = keyOfRow(KeyRow.of(s))

  def keyOfRow(r: KeyRow): ObjectNode =
    val n = mapper.createObjectNode()
      .put("id", r.keyId).put("name", r.name).put("prefix", RawKeys.prefix(r.keyId)).put("enabled", r.enabled).put("deleted", r.deleted)
    r.expiresAt.foreach(x => n.put("expiresAt", Times.of(x)))
    val fields = n.putArray("identifyingFields")
    r.identifyingFields.foreach(fields.add)
    n.put("requestsPerMinute", r.requestsPerMinute).put("createdBy", r.createdBy)
    r.createdAt.foreach(x => n.put("createdAt", Times.of(x)))
    n

  type Problem = (String, String)

  def parse(body: String): Either[Problem, JsonNode] =
    Try(mapper.readTree(body)).toOption.filter(n => n != null && n.isObject).toRight("body" -> "the body is not a JSON object")

  private def name(n: JsonNode): Either[Problem, Option[String]] =
    Option(n.get("name")).filterNot(_.isNull).fold(Right(None)) { v =>
      Either.cond(v.isTextual && v.asText().trim.length >= 1 && v.asText().trim.length <= 100, Some(v.asText().trim),
        "name" -> "a name is 1 to 100 characters")
    }

  /** `None` when absent, `Some(None)` for `null` (clear it), `Some(Some(at))` for a time in the future. */
  private def expiry(n: JsonNode, at: Instant): Either[Problem, Option[Option[Instant]]] =
    Option(n.get("expiresAt")).fold(Right(None)) { v =>
      if v.isNull then Right(Some(None))
      else
        Try(Instant.parse(v.asText())).toOption.filter(v.isTextual && _.isAfter(at))
          .map(x => Some(Some(x))).toRight("expiresAt" -> "expiresAt is an ISO 8601 time in the future")
    }

  private def fields(n: JsonNode): Either[Problem, Option[Set[String]]] =
    Option(n.get("identifyingFields")).fold(Right(None)) { v =>
      val names = if v.isArray then v.elements().asScala.toVector.map(e => Option.when(e.isTextual)(e.asText())) else Vector(None)
      Either.cond(names.forall(_.exists(IdentifyingFields.Names.contains)), Some(names.flatten.toSet),
        "identifyingFields" -> s"identifyingFields holds only ${IdentifyingFields.Names.mkString(", ")}")
    }

  private def rate(n: JsonNode): Either[Problem, Option[Int]] =
    Option(n.get("requestsPerMinute")).fold(Right(None)) { v =>
      Either.cond(v.isIntegralNumber && v.canConvertToInt && v.asInt() >= IdentifyingFields.MinRate && v.asInt() <= IdentifyingFields.MaxRate,
        Some(v.asInt()), "requestsPerMinute" -> s"requestsPerMinute is from ${IdentifyingFields.MinRate} to ${IdentifyingFields.MaxRate}")
    }

  /** A creation's settings; the digest and the operator are filled in by the route. */
  def creation(n: JsonNode, at: Instant): Either[Problem, Create] =
    for
      nm <- name(n).flatMap(_.toRight("name" -> "a name is 1 to 100 characters"))
      x  <- expiry(n, at)
      f  <- fields(n)
      r  <- rate(n)
    yield Create(nm, "", x.flatten, f.getOrElse(Set.empty), r.getOrElse(IdentifyingFields.Default), "", at)

  def change(n: JsonNode, at: Instant, by: String): Either[Problem, Change] =
    for
      nm <- name(n)
      x  <- expiry(n, at)
      f  <- fields(n)
      r  <- rate(n)
    yield Change(nm, x.flatten, x.contains(None), f, r, by, at)

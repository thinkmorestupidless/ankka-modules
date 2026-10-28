package io.eitheror.casino.attribution.it

import io.eitheror.casino.attribution.Json
import io.eitheror.casino.attribution.tag.{Signing, Tag}

import java.time.Instant
import scala.collection.mutable

/**
 * A network as the brand sees one (contracts/protocol.md): its key document, revocation list and, as the
 * programme, its legacy tag and code documents, from documents the test sets; `/tag` mints a token under
 * one of its keys, as the network's tracking link would. `fail(kind)` makes a document answer 503;
 * `rotate()` adds a key and withdraws the oldest. Every request is in `received`.
 */
final class FakeNetwork(val id: String = "programme", bindPort: Int = 0) extends FakeServer(bindPort):
  final case class Key(kid: String, x: String, privateKey: String)

  private val lock = Object()
  private val keys = mutable.ArrayBuffer.empty[Key]
  private var serial = 0
  @volatile var cacheSeconds: Long           = 60
  @volatile var revokedLinks: Set[String]    = Set.empty
  @volatile var revokedAffiliates: Set[String] = Set.empty
  @volatile var legacy: Map[String, (String, String)] = Map.empty
  @volatile var codes: Map[String, (String, String)]  = Map.empty
  @volatile var codesVersion: Long = 1
  private val failing = mutable.Set.empty[String]

  rotate(withdraw = false)

  def keyDocumentUrl: String  = s"$address/keys"
  def revocationsUrl: String  = s"$address/revocations"
  def legacyUrl: String       = s"$address/legacy"
  def codesUrl(brand: String) = s"$address/codes/$brand"

  /** The newest key, which `/tag` mints under unless told another. */
  def current: Key = lock.synchronized(keys.last)

  /** Adds a key; withdraws the oldest unless told not to. Answers the new key. */
  def rotate(withdraw: Boolean = true): Key = lock.synchronized {
    serial += 1
    val (x, priv) = Signing.generate()
    val k = Key(s"$id-k$serial", x, priv)
    keys += k
    if withdraw && keys.size > 1 then keys.remove(0): Unit
    k
  }

  /** Withdraws the key named, whatever else is held; a new key first when it is the only one. */
  def withdraw(kid: String): Unit = lock.synchronized {
    if keys.forall(_.kid == kid) then rotate(withdraw = false): Unit
    keys.filterInPlace(_.kid != kid)
  }

  def fail(kind: String): Unit    = lock.synchronized(failing += kind): Unit
  def recover(kind: String): Unit = lock.synchronized(failing -= kind): Unit

  /** A token for the fields, under the current key or the one named. */
  def mint(affiliate: String, link: String, campaign: Option[String] = None, subIds: Vector[String] = Vector.empty,
      iat: Instant = Instant.now(), kid: Option[String] = None): String =
    val key = lock.synchronized(kid.flatMap(k => keys.find(_.kid == k)).getOrElse(keys.last))
    Tag.mint(Tag.Fields(id, affiliate, link, campaign, subIds, iat), key.kid, key.privateKey)

  def reset(): Unit = lock.synchronized {
    failing.clear(); revokedLinks = Set.empty; revokedAffiliates = Set.empty; legacy = Map.empty; codes = Map.empty
    cacheSeconds = 60; clearReceived()
  }

  private def failed(kind: String) = lock.synchronized(failing.contains(kind))

  private def document(kind: String)(body: => String): Answer =
    if failed(kind) then Answer(503, """{"error":"unavailable"}""") else Answer(200, body)

  protected def route(r: Received): Answer = (r.method, r.path.split('/').toList.drop(1)) match
    case ("GET", "keys" :: Nil) =>
      document("keys") {
        val ks = lock.synchronized(keys.toVector).map(k => Json.obj("kid" -> k.kid, "kty" -> "OKP", "crv" -> "Ed25519", "x" -> k.x))
        Json.render(Json.obj("keys" -> Json.arr(ks*), "cacheSeconds" -> cacheSeconds))
      }
    case ("GET", "revocations" :: Nil) =>
      document("revocations")(Json.render(Json.obj("refreshSeconds" -> cacheSeconds,
        "links" -> Json.arr(revokedLinks.toVector*), "affiliates" -> Json.arr(revokedAffiliates.toVector*))))
    case ("GET", "legacy" :: Nil) =>
      document("legacy") {
        val tags = Json.obj(legacy.toVector.map((uuid, al) => uuid -> Json.obj("affiliate" -> al._1, "link" -> al._2))*)
        Json.render(Json.obj("version" -> 1L, "cacheSeconds" -> cacheSeconds, "tags" -> tags))
      }
    case ("GET", "codes" :: brand :: Nil) =>
      document("codes") {
        val cs = codes.toVector.map((code, ot) => Json.obj("code" -> code, "offer" -> ot._1, "tag" -> ot._2))
        Json.render(Json.obj("brand" -> brand, "version" -> codesVersion, "cacheSeconds" -> cacheSeconds, "codes" -> Json.arr(cs*)))
      }
    case ("GET", "tag" :: Nil) =>
      val q = r.query
      Answer(200, mint(q("aff"), q("lnk"), q.get("cmp").filter(_.nonEmpty), q.get("sub").filter(_.nonEmpty).toVector.flatMap(_.split(',')),
        q.get("iat").map(s => Instant.ofEpochSecond(s.toLong)).getOrElse(Instant.now()), q.get("kid")), "text/plain")
    case _ => Answer(404, """{"error":"not_found"}""")

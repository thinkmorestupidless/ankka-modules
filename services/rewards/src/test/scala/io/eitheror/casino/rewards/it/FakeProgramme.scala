package io.eitheror.casino.rewards.it

import com.fasterxml.jackson.databind.node.ObjectNode
import io.eitheror.casino.rewards.Json

import java.time.Instant
import scala.collection.concurrent.TrieMap

/**
 * The programme's public code document per brand (spec 013 FR-023a, in spec 012 contracts/protocol.md's shape), at
 * `url(brand)`. A brand with no document set answers `404`; `documentFor` sets one, each publication its version.
 */
final class FakeProgramme extends FakeServer():
  private val documents = TrieMap.empty[String, Answer]

  def url(brand: String): String = s"$address/codes/$brand.json"

  /** Publishes the brand's document: `(code, offerId, tag)` per entry. */
  def documentFor(brand: String, version: Long, at: Instant, codes: (String, String, String)*): Unit =
    documents.update(brand, Answer.ok(FakeProgramme.document(brand, version, at, codes*)))
  def answerFor(brand: String, answer: Answer): Unit = documents.update(brand, answer)
  def reset(): Unit =
    documents.clear()
    resetServer()

  def fetches(brand: String): Vector[Received] = received.filter(r => r.method == "GET" && r.path == s"/codes/$brand.json")

  protected def route(r: Received): Answer =
    r.path match
      case s"/codes/$brand.json" if r.method == "GET" => documents.getOrElse(brand, Answer(404, "", "text/plain"))
      case _                                          => Answer(404, "", "text/plain")

object FakeProgramme:
  def document(brand: String, version: Long, at: Instant, codes: (String, String, String)*): ObjectNode =
    Json.obj("brand" -> brand, "version" -> version, "at" -> at.toString, "cacheSeconds" -> 300,
      "codes" -> Json.arr(codes.map((code, offer, tag) => Json.obj("code" -> code, "offer" -> offer, "tag" -> tag))*))

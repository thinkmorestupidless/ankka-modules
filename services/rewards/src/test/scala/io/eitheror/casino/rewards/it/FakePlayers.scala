package io.eitheror.casino.rewards.it

import io.eitheror.casino.rewards.Json

/**
 * `players`' configuration read (spec 008 `GET /config/{name}`): the brand configuration with its duplicate
 * detector's switch, on unless set off; another configuration name is `404`. `brandConfigAnswer` replaces the
 * whole answer.
 */
final class FakePlayers(brand: String) extends FakeServer():
  @volatile var multiAccountDetection: Boolean      = true
  @volatile var brandConfigAnswer: Option[Answer] = None

  def reset(): Unit =
    multiAccountDetection = true
    brandConfigAnswer = None
    resetServer()

  private val BrandConfig = s"/v1/casino/brands/$brand/players/config/brand-config"

  def brandConfigReads: Vector[Received] = received.filter(r => r.method == "GET" && r.path == BrandConfig)

  protected def route(r: Received): Answer = (r.method, r.path) match
    case ("GET", BrandConfig) =>
      brandConfigAnswer.getOrElse(Answer.ok(Json.obj("name" -> brand, "reportingZone" -> "UTC", "singleSession" -> false,
        "multiAccountDetection" -> multiAccountDetection, "features" -> Json.obj())))
    case _ => Answer(404, """{"error":"not_found"}""")

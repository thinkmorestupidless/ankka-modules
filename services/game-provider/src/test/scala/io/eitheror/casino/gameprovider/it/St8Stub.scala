package io.eitheror.casino.gameprovider.it

import io.eitheror.casino.gameprovider.Json
import io.eitheror.casino.gameprovider.Json.*
import io.eitheror.casino.gameprovider.TestKeys
import io.eitheror.casino.gameprovider.provider.st8.St8Signature

import java.nio.file.Files
import java.security.PublicKey
import scala.collection.mutable

/**
 * ST8's operator API as `fake-st8` serves it (contracts/provider-api.md): launch, the game list, the
 * bonus routes, spin values, and a key document. It verifies each request's signature under the
 * brand's operator public key it is told, and keeps what it received.
 */
final class St8Stub(operatorPublic: PublicKey = TestKeys.testPublic) extends FakeServer:

  @volatile var gameUrl: Option[String]       = Some("https://st8.example/game?t=1")
  private val fixtureGames                     = Files.readString(TestKeys.root.resolve("tests/fixtures/specs/003/games.json"))
  @volatile var gamesBody: String              = fixtureGames
  @volatile var gamesStatus: Int               = 200
  @volatile var documentKeys: Vector[String]   = Vector(TestKeys.testPublicDer)
  @volatile var bonusStatus: String            = "active"
  @volatile var offers: Option[Vector[String]] = Some(Vector("0.20", "0.10", "0.50"))
  val badSignatures                            = mutable.ArrayBuffer.empty[String]

  def launches: Vector[Received]      = received.filter(_.path == "/api/operator/v1/launch")
  def gameLists: Vector[Received]     = received.filter(_.path == "/api/operator/v1/games")
  def bonusCreates: Vector[Received]  = received.filter(_.path == "/api/operator/v1/bonus/create")
  def bonusCancels: Vector[Received]  = received.filter(_.path == "/api/operator/v1/bonus/cancel")
  def offerRequests: Vector[Received] = received.filter(_.path == "/api/operator/v1/bonus/offers")

  def reset(): Unit = synchronized {
    gameUrl = Some("https://st8.example/game?t=1"); gamesBody = fixtureGames; gamesStatus = 200; bonusStatus = "active"
    offers = Some(Vector("0.20", "0.10", "0.50")); documentKeys = Vector(TestKeys.testPublicDer); badSignatures.clear(); clearReceived()
  }

  /** A POST is signed over its body, a GET over its query string (contracts/provider-api.md). */
  private def check(r: Received): Unit =
    val signed = if r.method == "GET" then r.rawQuery else r.body
    if !St8Signature.verify(signed, r.headers.get("x-st8-sign"), Seq(operatorPublic)) then badSignatures += r.path

  private def bonus(status: String) =
    Answer.ok(Json.obj("status" -> "ok", "bonus" -> Json.obj("instances" -> Json.arr(Json.obj("status" -> status)))))

  protected def route(r: Received): Answer =
    if r.path != "/keys" then check(r)
    r.path match
      case "/keys" => Answer.ok(Json.obj("keys" -> Json.arr(documentKeys.zipWithIndex.map((k, i) => Json.obj("kid" -> s"k$i", "publicKey" -> k))*)))
      case "/api/operator/v1/launch" =>
        gameUrl match
          case Some(u) => Answer.ok(Json.obj("status" -> "ok", "game_url" -> u))
          case None    => Answer.ok(Json.obj("status" -> "ok"))
      case "/api/operator/v1/games"        => Answer(gamesStatus, gamesBody)
      case "/api/operator/v1/bonus/create" => bonus(bonusStatus)
      case "/api/operator/v1/bonus/get"    => bonus(bonusStatus)
      case "/api/operator/v1/bonus/cancel" => bonus("canceled")
      case "/api/operator/v1/bonus/offers" =>
        offers match
          case Some(vs) => Answer.ok(Json.obj("status" -> "ok", "offers" -> Json.arr(vs.map(v => Json.obj("value" -> v))*)))
          case None     => Answer(500, """{"status":"error"}""")
      case other => Answer(404, s"""{"status":"error","path":"$other"}""")

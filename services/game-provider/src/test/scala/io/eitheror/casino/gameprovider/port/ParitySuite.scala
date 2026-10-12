package io.eitheror.casino.gameprovider.port

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.contract.port.{Recording, Replay}
import io.eitheror.casino.gameprovider.{Json, TestKeys}
import io.eitheror.casino.gameprovider.Json.*
import io.eitheror.casino.gameprovider.features.SharedKit
import io.eitheror.casino.gameprovider.it.Reply
import io.eitheror.casino.gameprovider.provider.st8.St8Signature

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/**
 * Replays one recording against the shared kit and answers where the service differed from the original
 * (research R15). A callback's body is replayed unchanged and signed with the fixture key, since the original's
 * key was minted for its run (tests/fixtures/specs/003/README.md); the recorder keeps no signature header, so a
 * callback the original refused `auth_failed` is signed with another key and every other with the fixture's. A
 * test-control step, on the adapter's `/test/` or `/st8/test/`, is replayed as the kit setup it stands for.
 *
 * What is not this service's is answered as recorded: a game-session step (`players`', spec 008), `/health`, and
 * every step the recorder put under another service (registration, the realm, offers and grants, the substrate,
 * the wallet), except the launch, which is this service's. A recorded grant is made the fake `rewards`' eligible
 * grant for the offer's game. `fake-st8`'s own steps are stood in for with the callbacks it makes: `GET /game` is
 * its `check`, `/test/free-spin` its zero `free_debit` and `credit` on one new round, answered with ours.
 */
object Parity:
  private val Callbacks = Set("check", "balance", "player_profile", "debit", "credit", "cancel", "buyin", "payout")
  private val Launch    = """/v1/casino/sessions/[^/]+/launch""".r
  private val Grant     = """/v1/casino/bonus/grant/[^/]+/([^/]+)""".r

  private def isControl(path: String) = path.startsWith("/st8/test/") || (path.startsWith("/test/") && !Simulator(path))
  private def Simulator(path: String) = path == "/test/free-spin" || path.startsWith("/test/bonus/")

  /**
   * Where the service answers differently from the original on purpose: the scenario, the answer's field, and
   * the divergence of plan.md that says why. A difference there is reported in the test's name, not failed.
   */
  val divergences: Map[String, (String, String)] = Map(
    "TestScenario15_St8Wallet_NotEnoughMoney" -> ("$.steps[3].response.body.balance",
      "a refused debit answers the player's balance, the original 0.00 (plan.md Divergences)")
  )

  /** What plan.md's divergences add to a recording, checked after it is replayed, named in the test. */
  val extensions: Map[String, (String, Recording => Unit)] =
    val noOpenBet = ("the wallet holds no open bet after the credit (plan.md Divergences, FR-014)",
      (_: Recording) => assertNoOpenBet())
    Map(
      // The recording leaves its round open after two debits; the extension credits it and finds both settled.
      "TestScenario15_St8Wallet_BigRoundMultipleDebits" -> ("a credit to the round settles both its debits (plan.md Divergences, FR-014)",
        creditSettlesEveryDebit),
      "TestScenario15_St8Wallet_BigRoundMultipleCredits" -> noOpenBet,
      "TestScenario15_St8Wallet_IdempotentCredit" -> ("a repeat with another amount is answered as the first and recorded (FR-011)",
        conflictingRepeat)
    )

  private def assertNoOpenBet(): Unit =
    val open = SharedKit.kit.wallet.allOpenBets
    if open.nonEmpty then throw AssertionError(s"the wallet holds open bets after the round: $open")

  private def creditSettlesEveryDebit(recording: Recording): Unit =
    val kit   = SharedKit.kit
    val debit = recording.steps.findLast(_.name == "POST /debit").getOrElse(throw AssertionError("the recording has no debit"))
    val body  = debit.request.body.get.deepCopy[com.fasterxml.jackson.databind.node.ObjectNode]()
    body.put("transaction_id", java.util.UUID.randomUUID.toString)
    body.put("amount", "20.00")
    val text  = Json.mapper.writeValueAsString(body)
    val reply = kit.request("POST", "/credit", Some(text), Seq(St8Signature.Header -> St8Signature.sign(text, TestKeys.testPrivate)))
    if !Json.parse(reply.text).flatMap(_.text("status")).contains("ok") then throw AssertionError(s"the credit was refused: ${reply.text}")
    assertNoOpenBet()

  private def conflictingRepeat(recording: Recording): Unit =
    val kit     = SharedKit.kit
    val credits = recording.steps.filter(_.name == "POST /credit")
    val first   = credits.headOption.getOrElse(throw AssertionError("the recording has no credit"))
    val body    = first.request.body.get.deepCopy[com.fasterxml.jackson.databind.node.ObjectNode]()
    body.put("amount", "99.00")
    val text    = Json.mapper.writeValueAsString(body)
    val reply   = kit.request("POST", first.request.path, Some(text), Seq(St8Signature.Header -> St8Signature.sign(text, TestKeys.testPrivate)))
    val answer  = Json.parse(reply.text)
    if answer.flatMap(_.text("balance")) != first.response.body.flatMap(_.text("balance")) then
      throw AssertionError(s"a repeat with another amount was not answered as the first: ${reply.text}")
    val round = body.textOr("round", body.textOr("transaction_id", ""))
    if kit.round(round).conflictingRepeats.isEmpty then throw AssertionError(s"round $round recorded no conflicting repeat")

  def divergent(recording: Recording, difference: Replay.Difference): Boolean =
    divergences.get(recording.scenario).exists((at, _) => difference.at.endsWith(at))

  def recordings(directory: Path): Vector[Path] =
    if !Files.isDirectory(directory) then Vector.empty
    else Files.list(directory).iterator.asScala.filter(p => p.toString.endsWith(".json") && !p.getFileName.toString.startsWith("suite-result")).toVector.sorted

  def blocked(recording: Recording): Vector[String] =
    recording.steps.filter(s => s.request.path.contains("/sessions/") && !s.request.path.endsWith("/launch")).map(_.name)

  def replay(recording: Recording): Vector[Replay.Difference] =
    val kit = SharedKit.kit
    kit.reset()
    assert(kit.configureSt8().status == 204)
    val control = TestControl(kit)
    val skipped = blocked(recording).toSet
    var step    = 0
    var game    = "" // the offer's game, for the grant that follows it
    val wallets = scala.collection.mutable.Map.empty[String, String] // launch token -> the token `check` answered

    // The original's suite shares one player across its tests, so a recording starts from whatever balance the
    // tests before it left. Before a player's first money callback the kit's wallet is set to the balance the
    // original's answer implies the player had then: the answer's balance less a payout or credit, plus a debit.
    val seeded = scala.collection.mutable.Set.empty[String]
    def seed(kind: String, body: com.fasterxml.jackson.databind.JsonNode, answer: Option[com.fasterxml.jackson.databind.JsonNode]): Unit =
      for
        player   <- body.text("player")
        if !seeded(player)
        answered <- answer.filter(_.text("status").contains("ok"))
        currency <- answered.text("currency")
        balance  <- answered.text("balance")
      do
        val c      = io.eitheror.casino.contract.money.Currency.unsafe(currency)
        def minor(s: String) = io.eitheror.casino.gameprovider.money.Amounts.parse(s, c).map(_.value).getOrElse(0L)
        val amount = body.text("amount").map(minor).getOrElse(0L)
        val before = kind match
          case "payout" | "credit" => minor(balance) - amount
          case "debit" | "buyin"   => minor(balance) + amount
          case _                   => minor(balance)
        if kind != "cancel" then
          seeded += player
          kit.wallet.set(player, currency, before)

    // The recordings are of the original's brand; the kit runs as its own.
    val recordedBrand = recording.original.getOrElse("brand", "spinvibe")
    def ownBrand(path: String) = path.split('/').map(s => if s == recordedBrand then kit.brand else s).mkString("/")

    def callback(path: String, text: String, refused: Boolean): Reply =
      val key = if control.signatureValid && !refused then TestKeys.testPrivate else TestKeys.otherPrivate
      kit.request("POST", path, Some(text), Seq(St8Signature.Header -> St8Signature.sign(text, key)))

    Replay.run(recording, request => {
      val current = recording.steps(step)
      step += 1
      val path = request.path.takeWhile(_ != '?')
      def query(name: String) = request.path.dropWhile(_ != '?').drop(1).split('&').collectFirst {
        case kv if kv.startsWith(name + "=") => kv.drop(name.length + 1)
      }
      if isControl(path) then
        control(request.copy(path = path.replaceFirst("^/test/", "/st8/test/"))).fold(reason => throw AssertionError(s"${recording.scenario}: $reason"), identity)
        current.response
      else if path == "/game" then
        val token = query("token").getOrElse("")
        val reply = callback("/st8/check", Json.obj("token" -> token).toString, refused = false)
        Json.parse(reply.text).flatMap(_.text("token")).foreach(wallets(token) = _)
        current.response
      else if path == "/test/free-spin" && current.response.status != 200 then
        current.response // fake-st8 refused it itself (no free rounds left): the adapter never saw a callback
      else if path == "/test/free-spin" then
        val body     = request.body.getOrElse(Json.obj())
        val launch   = body.textOr("launchToken", "")
        val wallet   = wallets.getOrElse(launch, launch)
        val round    = java.util.UUID.randomUUID.toString
        def spin(kind: String, amount: String) = Json.parse(callback(s"/st8/$kind", Json.obj(
          "token" -> wallet, "transaction_id" -> java.util.UUID.randomUUID.toString, "round" -> round, "currency" -> "EUR",
          "amount" -> amount, "provider_kind" -> (if kind == "debit" then "free_debit" else "credit")).toString, refused = false).text).orNull
        val debit  = spin("debit", "0.00")
        val credit = spin("credit", body.textOr("winAmount", "0.00"))
        val recorded = current.response.body.getOrElse(Json.obj())
        Recording.Response(200, Some(Json.obj("roundId" -> round, "remaining" -> recorded.get("remaining"), "debit" -> debit, "credit" -> credit)))
      else if skipped(current.name) || path == "/health" || Simulator(path) then current.response
      else if current.request.service.exists(_ != "game-provider") && Launch.unapplySeq(path).isEmpty then
        request.body.flatMap(_.text("gameId")).foreach(game = _)
        path match
          case Grant(player) => current.response.body.flatMap(_.text("grantId")).foreach(g => kit.neighbours.grants(player -> game) = g)
          case _             => ()
        current.response
      else
        val last = path.split('/').last
        val text = request.body.map(Json.mapper.writeValueAsString).getOrElse("")
        val reply =
          if request.method == "POST" && Callbacks(last) then
            request.body.foreach(seed(last, _, current.response.body))
            callback(request.path, text, refused = current.response.body.flatMap(_.text("status")).contains("auth_failed"))
          else
            val player = request.body.flatMap(_.text("playerId")).getOrElse("recorded-player")
            kit.request(request.method, ownBrand(request.path), request.body.map(_ => text), kit.asPlayer(player))
        Recording.Response(reply.status, Json.parse(reply.text))
    })

/**
 * One test per recording in tests/port/game-provider (constitution I, SC-001): each step replayed against the
 * whole service and its answer compared field by field with the original's.
 */
class ParitySuite extends munit.FunSuite with LogCapturing:
  override val munitTimeout = scala.concurrent.duration.Duration(300, "s")

  private val found = Parity.recordings(TestKeys.root.resolve("tests/port/game-provider"))

  if found.isEmpty then
    // Never passed: the recordings come from the original's Go suite (T015), which needs its full stack running.
    test("every recording of tests/port/game-provider replays as the original answered (T015 has not recorded them)".ignore) {}
  else
    found.foreach { path =>
      val recording = Recording.load(path).fold(e => throw AssertionError(e), identity)
      val blocked   = Parity.blocked(recording)
      val diverges  = Parity.divergences.get(recording.scenario)
      val name = recording.scenario +
        (if blocked.isEmpty then "" else s" (game-session steps answered as recorded, spec 008: ${blocked.mkString(", ")})") +
        diverges.fold("")((at, why) => s" (diverges at $at: $why)") +
        Parity.extensions.get(recording.scenario).fold("")((what, _) => s" (extended: $what)")
      test(name) {
        val (marked, differences) = Parity.replay(recording).partition(Parity.divergent(recording, _))
        assert(differences.isEmpty, differences.map(_.message).mkString("\n"))
        // A marked divergence is demonstrated, not only tolerated: the service answers differently there.
        assertEquals(marked.size, diverges.size, s"${recording.scenario}: the marked divergence no longer differs from the original")
        Parity.extensions.get(recording.scenario).foreach((_, check) => check(recording))
      }
    }

/**
 * The replay machinery against hand-made recordings in tests/fixtures/port/game-provider, so the parity suite
 * is known to work before the first real recording: control steps stood in for, bodies re-signed, a call the
 * original made unsigned refused, and a difference reported.
 */
class ParityFixtureSuite extends munit.FunSuite with LogCapturing:
  override val munitTimeout = scala.concurrent.duration.Duration(300, "s")

  private val fixtures = Parity.recordings(TestKeys.root.resolve("tests/fixtures/port/game-provider"))

  test("the fixtures exist") { assert(fixtures.nonEmpty) }

  fixtures.foreach { path =>
    val recording = Recording.load(path).fold(e => throw AssertionError(e), identity)

    test(s"${recording.scenario} replays with no difference") {
      val differences = Parity.replay(recording)
      assert(differences.isEmpty, differences.map(_.message).mkString("\n"))
    }

    test(s"${recording.scenario}: a changed expectation is reported as a difference") {
      // The same recording under ids of its own, since the database outlives a replay.
      val renamed = Recording.parse(path, Json.mapper.readTree(Files.readString(path).replace("fixture-", "changed-fixture-")))
        .fold(e => throw AssertionError(e), identity)
      val at      = renamed.steps.indexWhere(_.name == "debit")
      val changed = renamed.copy(steps = renamed.steps.updated(at, renamed.steps(at).copy(
        response = Recording.Response(200, Json.parse("""{"status":"ok","balance":"1.00","currency":"EUR"}""")))))
      val differences = Parity.replay(changed)
      assert(differences.exists(d => d.at.endsWith("body.balance") && d.expected.contains("1.00")), differences.map(_.message).mkString("\n"))
    }
  }

package io.eitheror.casino.support.port

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.agent.{ChatMessage, Json as AgentJson}
import io.eitheror.casino.support.Json
import io.eitheror.casino.support.Json.*
import io.eitheror.casino.support.it.{Answer, Frame, Kit, SharedKit}

import java.nio.file.{Files, Path, Paths}
import java.time.temporal.ChronoUnit
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

/**
 * One test per recording in tests/port/support-agent (T056; research R13), named after its file. Each step on the
 * original's agent is sent to this service as its own route takes it (the steps on casino-service and the wallet are
 * the original's setup and are not sent): the scripted message `script:{…}` becomes the same tool call or text on the
 * kit's model, a write's chat and its resume are read as one stream, and the player is the recording's own with the
 * fakes answering in its currency. Held exact: the frames' names (consecutive `delta`s as one, since this service
 * streams the text as it is produced), `confirm_action`'s `action_name` and `summary`, `action_result`'s
 * `action_name`, `success` and `detail`, each status, `Session already active`, and the keys of what a read tool told
 * the model. A difference plan.md's *Divergences* names is excepted, and must still be there.
 */
class ParitySuite extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(300, "s")
  private lazy val kit = SharedKit.kit
  import ParitySuite.*

  override def beforeEach(context: BeforeEach): Unit =
    kit.reset()
    kit.configure(): Unit

  if recordings.isEmpty then test("tests/port/support-agent holds the recordings T055 makes".ignore) {}

  recordings.zipWithIndex.foreach { (path, i) =>
    val recording = Json.parse(Files.readString(path)).getOrElse(throw AssertionError(s"$path is not JSON"))
    val scenario  = recording.textOr("scenario", path.getFileName.toString)
    val diverges  = Divergences.filter(_.holds(scenario))
    test(path.getFileName.toString.stripSuffix(".json") + diverges.map(d => s" (diverges: ${d.why})").mkString) {
      val (marked, differences) = replay(recording, s"p-par-$i", s"par-$i").partition(d => diverges.exists(_.covers(d)))
      assert(differences.isEmpty, differences.map(_.message).mkString("\n"))
      diverges.foreach(d => assert(marked.exists(d.covers), s"$scenario: no longer differs from the original where ${d.why}"))
    }
  }

  /** What the service answered to one step: its status, and its frames or its body. */
  private final case class Ours(status: Int, frames: Vector[Frame], body: String)

  private def replay(recording: JsonNode, player: String, session: String): Vector[Difference] =
    kit.players.answer("GET", s"/players/$player/status",
      Answer.ok(Json.obj("status" -> "ACTIVE", "verification" -> "VERIFIED", "displayName" -> "Ann", "currency" -> "EUR")))
    // A cool-off instant the original was given is three days after its run; this run's is three days after its clock.
    val until    = kit.clock.instant().plus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS).toString
    val recorded = recording.path("steps").elements.asScala.toVector.filterNot(_.textOr("name", "").startsWith("setup:"))
      .map(s => Json.parse(Json.render(s).replaceAll("""\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ""", until)).get)
    val answers = recorded.map(step => step -> send(step, player, session))
    val differences = mutable.ArrayBuffer.empty[Difference]
    answers.zipWithIndex.foreach { case ((step, ours), n) =>
      val name     = step.textOr("name", "")
      val response = step.path("response")
      if response.path("status").asInt != ours.status then
        differences += Difference(s"$name.status", s"$name: ${ours.status}, where the original answered ${response.path("status").asInt}")
      if response.path("body").isArray then
        // A write's stream on the original carried the decision's frames too: here they are the next resume's.
        val resumed = if !name.startsWith("chat") then Vector.empty
          else answers.drop(n + 1).find((s, _) => s.textOr("name", "").startsWith("resume: ")).map(_._2.frames).getOrElse(Vector.empty)
        differences ++= frames(name, response.path("body").elements.asScala.toVector, ours.frames, resumed)
        if recording.textOr("scenario", "").startsWith("a read tool") && name.startsWith("chat: ") then
          differences ++= told(name, response.path("body"))
      else if response.path("status").asInt >= 400 then
        val theirs = response.path("body").path("detail")
        if !(theirs.isTextual && ours.body.contains(theirs.asText)) then
          differences += Difference(s"$name.body", s"$name: ${ours.body}, where the original answered ${Json.render(response.path("body"))}")
    }
    differences.toVector

  /** The step sent to this service's route. */
  private def send(step: JsonNode, player: String, session: String): Ours =
    val name = step.textOr("name", "")
    val body = step.path("request").path("body")
    def streamed(s: io.eitheror.casino.support.it.SseReader) =
      val got = if s.status == 200 then s.frames() else Vector.empty
      Ours(s.status, got, if s.status == 200 then "" else s.body)
    def plain(r: io.eitheror.casino.support.it.Reply) = Ours(r.status, Vector.empty, r.text)
    name match
      case "chat with no token" =>
        plain(kit.request("POST", kit.chatPath("/run/chat_with_player"), Some(Json.render(body)), kit.asPlayer(player).take(1)))
      case "chat with a token signed by another secret" =>
        plain(kit.request("POST", kit.chatPath("/run/chat_with_player"), Some(Json.render(body)),
          kit.asPlayer(player).take(1) :+ ("Authorization" -> "Bearer not-a-token")))
      case "chat again while awaiting" =>
        streamed(kit.chat(player, session, "again"))
      case "resume with another session's token" =>
        streamed(kit.resume(s"$player-other", session, body.textOr("action", "")))
      case n if n.startsWith("resume") =>
        kit.model.expectText("ok")
        streamed(kit.resume(player, session, body.textOr("action", ""), body.text("feedback")))
      case _ =>
        val script = Json.parse(body.textOr("message", "").stripPrefix("script:")).getOrElse(Json.obj())
        script.text("tool") match
          case Some(tool) =>
            kit.model.expectToolCall(tool.stripPrefix("tool_"), arguments(script.path("args")), "call-par")
            kit.model.expectText("ok")
          case None => kit.model.expectText(script.textOr("text", ""))
        // The arguments are the model's, not the player's: a game session's id in the message would be refused as
        // another player's (FR-021), where the original's model read it from get_session_history.
        streamed(kit.chat(player, session, script.text("tool").fold(script.textOr("text", ""))(t => s"script:$t")))

  private def arguments(args: JsonNode): AgentJson =
    AgentJson.obj(args.fields.asScala.toSeq.map { e =>
      e.getKey -> (if e.getValue.isNumber then AgentJson.num(e.getValue.asDouble) else AgentJson.str(e.getValue.asText))
    }*)

  /**
   * The original's frames against this service's: the names, then the fields held exact. `resumed` is the decision's
   * stream, read after this one's `done`.
   */
  private def frames(step: String, theirs: Vector[JsonNode], ours: Vector[Frame], resumed: Vector[Frame]): Vector[Difference] =
    val got   = mutable.ArrayBuffer.empty[Difference]
    val names = theirs.map(_.textOr("event", ""))
    val alone = collapse(ours.map(_.name))
    if collapse(names) != alone then got += Difference(s"$step.frames", s"$step: frames $alone, where the original sent ${collapse(names)}")
    val joined = collapse(ours.map(_.name).dropRight(if resumed.nonEmpty then 1 else 0) ++ resumed.map(_.name))
    if resumed.nonEmpty && collapse(names) != joined then
      got += Difference(s"$step.frames.joined", s"$step: with the resume's, frames $joined, where the original sent ${collapse(names)}")
    val all = ours ++ resumed
    def held(event: String, fields: String*): Unit =
      theirs.find(_.textOr("event", "") == event).foreach { f =>
        val mine = all.find(_.name == event)
        fields.foreach { field =>
          val want = f.path("data").path(field)
          val have = mine.map(_.field(field)).getOrElse("")
          if want.asText != have then
            got += Difference(s"$step.$event.$field", s"$step: $event.$field '$have', where the original sent '${want.asText}'", Some(want.asText -> have))
        }
      }
    held("confirm_action", "action_name", "summary")
    held("action_result", "action_name", "success", "detail")
    got.toVector

  /** The keys of what the read tool told the model, against the original's (its model's `result: …` reply). */
  private def told(step: String, theirs: JsonNode): Vector[Difference] =
    val original = theirs.elements.asScala.find(_.textOr("event", "") == "delta").map(_.path("data").textOr("token", ""))
      .filter(_.startsWith("result: ")).flatMap(t => Json.parse(t.stripPrefix("result: ")))
    val ours = kit.model.requests.flatMap(_.messages).collect { case ChatMessage.ToolResults(rs) => rs }.flatten
      .lastOption.flatMap(r => Json.parse(r.content))
    (original.map(shape), ours.map(shape)) match
      case (a, b) if a == b => Vector.empty
      case (a, b) => Vector(Difference(s"$step.told", s"$step: the model was told ${b.getOrElse("nothing")}, where the original's was told ${a.getOrElse("nothing")}"))

object ParitySuite:
  val root: Path      = Paths.get(sys.props.getOrElse("casino.root", "../..")).toAbsolutePath.normalize
  val directory: Path = root.resolve("tests/port/support-agent")

  def recordings: Vector[Path] =
    if !Files.isDirectory(directory) then Vector.empty
    else Files.list(directory).iterator.asScala.filter(_.toString.endsWith(".json")).toVector.sorted

  /** Where the service answers differently; `values` the original's and this service's, for a held field. */
  final case class Difference(at: String, message: String, values: Option[(String, String)] = None)

  /** A row of plan.md's *Divergences*: the recordings it holds in, and the differences it covers. */
  final case class Divergence(why: String, holds: String => Boolean, covers: Difference => Boolean)

  /** Consecutive frames of one name as one. */
  def collapse(names: Vector[String]): Vector[String] = names.foldLeft(Vector.empty[String])((acc, n) => if acc.lastOption.contains(n) then acc else acc :+ n)

  /** A JSON value's shape: an object's keys, sorted; an array's first element's, or `[]`. */
  def shape(node: JsonNode): String =
    if node.isObject then node.fieldNames.asScala.toVector.sorted.mkString("{", ",", "}")
    else if node.isArray then node.elements.asScala.toVector.headOption.map(shape).fold("[]")(s => s"[$s]")
    else node.getNodeType.toString

  /** `ours` is `theirs` with a value where theirs had none: one contiguous run of text added. */
  def filled(theirs: String, ours: String): Boolean =
    ours.length > theirs.length && (0 to theirs.length).exists(i => ours.startsWith(theirs.take(i)) && ours.endsWith(theirs.drop(i)))

  private def tool(names: String*)(scenario: String) = names.exists(n => scenario.contains(s"[$n"))
  private def writes(scenario: String)                = scenario.startsWith("a write tool") || scenario.startsWith("what the routes answer")
  private def approved(scenario: String) =
    scenario.endsWith("approve]") && tool("request_withdrawal", "set_deposit_limit", "apply_self_exclusion")(scenario)

  val Divergences: Vector[Divergence] = Vector(
    Divergence("the chat's stream ends at confirm_action and done; the resume streams the rest (R3)", writes,
      d => d.at.endsWith(".frames") || (d.at.startsWith("resume") && d.at.endsWith(".body"))),
    Divergence("get_player_state answers status, verification, displayName and currency (FR-018)",
      tool("get_player_state"), _.at.endsWith(".told")),
    Divergence("the original cannot read the wallet: its internal call is refused 403", tool("get_wallet_balance", "get_transaction_history"),
      d => d.at.endsWith(".frames") || d.at.endsWith(".told")),
    Divergence("a list's tool answers an object naming the list (contracts/tools.md)", tool("get_active_bonuses", "get_session_history"),
      _.at.endsWith(".told")),
    Divergence("get_rg_status answers the brand's limits, pending changes and restrictions (FR-010)", tool("get_rg_status"), _.at.endsWith(".told")),
    Divergence("action_result.detail names what the owning service answered, where the original's value was empty",
      approved, d => d.at.endsWith("action_result.detail") && d.values.exists((t, o) => filled(t, o))),
    Divergence("a deposit limit's summary says whether it applies at once or after 24 hours, as responsible gambling answers (FR-013)",
      tool("set_deposit_limit"), _.at.endsWith("confirm_action.summary")),
    Divergence("the routes' refusals are 403 session_not_yours, 409 already_decided and 422 invalid_action (FR-004, FR-017)",
      _.startsWith("what the routes answer"), d => d.at.endsWith(".body") || d.at == "resume after the decision.status"),
    Divergence("the player is the realm token's; the original's hand-off token is not taken", _.startsWith("the routes refuse"),
      _.at.endsWith(".body"))
  )

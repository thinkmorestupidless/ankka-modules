package io.eitheror.casino.simulator.core

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import com.fasterxml.jackson.databind.node.ObjectNode
import com.networknt.schema.{JsonSchemaFactory, SpecVersion}

import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters.*
import scala.util.Try

/**
 * A named script the simulator follows for a session or a brand (FR-016 to FR-019), read from the
 * data of `contracts/scenario.schema.json`. The core interprets it without naming any protocol's
 * route, field or status word (FR-006): a callback kind and a status word are strings the scenario
 * and the protocol agree on.
 */
final case class Scenario(
    name: String,
    version: Int,
    description: String,
    source: String,
    shape: String,
    schedule: Option[ScenarioSchedule],
    steps: Vector[Step],
    json: String
):
  /** A scenario every one of whose sends waits for an instruction: what a load profile may play. */
  def instructionDriven: Boolean = steps.forall {
    case s: Step.Send => s.on == "instruction"
    case Step.PassThrough => true
    case _: Step.Expect   => true
    case _                => false
  }

  def passThrough: Boolean = steps == Vector(Step.PassThrough)

/** The schedule as written in data; the protocol supplies `retryOn` when the data does not. */
final case class ScenarioSchedule(
    answerWait: FiniteDuration,
    intervals: Vector[Schedule.Interval],
    cap: FiniteDuration,
    retryOn: Option[Set[String]]
):
  def resolve(defaultRetryOn: Set[String]): Schedule = Schedule(answerWait, intervals, cap, retryOn.getOrElse(defaultRetryOn))

enum Step:
  case PassThrough
  case Send(
      callback: String,
      on: String,
      amount: Option[Amount],
      providerKind: Option[String],
      round: String,
      transaction: String,
      token: String,
      gameCode: Option[String],
      key: String,
      malformed: Boolean,
      extra: Map[String, JsonNode],
      schedule: Option[ScenarioSchedule]
  )
  case Expect(
      of: Int,
      status: Vector[String],
      httpStatus: Option[Int],
      balance: String,
      exact: Option[String],
      sameAnswerAs: Option[Int],
      minAttempts: Option[Int],
      maxAttempts: Option[Int],
      roundSettledAs: Option[String]
  )
  case Wait(fixed: Option[FiniteDuration], range: Option[(FiniteDuration, FiniteDuration)], until: Option[String])
  case SettleOpenRounds
  case RotateKey(withdrawOld: Boolean)

enum Amount:
  case Fixed(value: String)
  case Range(min: String, max: String)

  def draw(seeded: Seeded, step: Int): String = this match
    case Fixed(v)        => v
    case Range(min, max) => seeded.amount(step, min, max)

object Scenario:

  private val mapper = ObjectMapper()

  /** The schema, shipped on the classpath beside the scenarios (the spec's contract, copied at build). */
  private lazy val schema =
    val factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
    val stream = Option(getClass.getResourceAsStream("/scenario.schema.json"))
      .getOrElse(throw IllegalStateException("scenario.schema.json is not on the classpath"))
    try factory.getSchema(stream)
    finally stream.close()

  /** The problems with a scenario document, the first of them first, each naming its path. */
  def problems(text: String): Vector[String] =
    Try(mapper.readTree(text)).toEither match
      case Left(e) => Vector(s"$$: not JSON: ${e.getMessage}")
      case Right(node) =>
        val schemaProblems = schema.validate(node).asScala.toVector.map(m => s"${m.getInstanceLocation}: ${m.getMessage}")
        if schemaProblems.nonEmpty then schemaProblems.sorted
        else parse(text).left.toOption.toVector

  def parse(text: String): Either[String, Scenario] =
    Try(mapper.readTree(text)).toEither.left.map(e => s"not JSON: ${e.getMessage}").flatMap { root =>
      Try {
        def str(n: JsonNode, f: String): Option[String] = Option(n.get(f)).filter(!_.isNull).map(_.asText())
        def int(n: JsonNode, f: String): Option[Int]    = Option(n.get(f)).filter(_.isNumber).map(_.asInt())
        val steps = Option(root.get("steps")).map(_.elements().asScala.toVector).getOrElse(Vector.empty).map(step)
        steps.zipWithIndex.collect { case (e: Step.Expect, i) => (e, i) }.foreach { (e, i) =>
          steps.lift(e.of) match
            case Some(_: Step.Send) =>
            case _ => throw IllegalArgumentException(s"$$.steps[$i].of: step ${e.of} is not a send")
        }
        Scenario(
          name = str(root, "name").getOrElse(throw IllegalArgumentException("$.name is missing")),
          version = int(root, "version").getOrElse(1),
          description = str(root, "description").getOrElse(""),
          source = str(root, "source").getOrElse(""),
          shape = str(root, "shape").getOrElse("stub"),
          schedule = Option(root.get("schedule")).map(schedule),
          steps = steps,
          json = mapper.writeValueAsString(root)
        )
      }.toEither.left.map(_.getMessage)
    }

  private def schedule(n: JsonNode): ScenarioSchedule =
    ScenarioSchedule(
      answerWait = Durations.unsafe(n.get("wait").asText()),
      intervals = n.get("intervals").elements().asScala.toVector.map { i =>
        Schedule.Interval(Durations.unsafe(i.get("every").asText()), Option(i.get("for")).map(f => Durations.unsafe(f.asText())))
      },
      cap = Durations.unsafe(n.get("cap").asText()),
      retryOn = Option(n.get("retryOn")).map(_.elements().asScala.map(_.asText()).toSet)
    )

  private def amount(n: JsonNode): Amount =
    if n.isTextual then Amount.Fixed(n.asText()) else Amount.Range(n.get("min").asText(), n.get("max").asText())

  private def step(n: JsonNode): Step =
    def str(f: String): Option[String] = Option(n.get(f)).filter(!_.isNull).map(_.asText())
    def int(f: String): Option[Int]    = Option(n.get(f)).filter(_.isNumber).map(_.asInt())
    str("kind") match
      case Some("pass-through") => Step.PassThrough
      case Some("send") =>
        Step.Send(
          callback = str("callback").get,
          on = str("on").getOrElse("now"),
          amount = Option(n.get("amount")).map(amount),
          providerKind = str("providerKind"),
          round = str("round").getOrElse("same"),
          transaction = str("transaction").getOrElse("new"),
          token = str("token").getOrElse("session"),
          gameCode = str("gameCode"),
          key = str("key").getOrElse("current"),
          malformed = Option(n.get("malformed")).exists(_.asBoolean()),
          extra = Option(n.get("extra")).collect { case o: ObjectNode => o.properties().asScala.map(e => e.getKey -> e.getValue).toMap }.getOrElse(Map.empty),
          schedule = Option(n.get("schedule")).map(schedule)
        )
      case Some("expect") =>
        val attempts = Option(n.get("attempts"))
        Step.Expect(
          of = int("of").get,
          status = Option(n.get("status")).map(_.elements().asScala.map(_.asText()).toVector).getOrElse(Vector.empty),
          httpStatus = int("httpStatus"),
          balance = str("balance").getOrElse("any"),
          exact = str("exact"),
          sameAnswerAs = int("sameAnswerAs"),
          minAttempts = attempts.flatMap(a => Option(a.get("min"))).map(_.asInt()),
          maxAttempts = attempts.flatMap(a => Option(a.get("max"))).map(_.asInt()),
          roundSettledAs = str("roundSettledAs")
        )
      case Some("wait") =>
        Step.Wait(
          fixed = str("for").map(Durations.unsafe),
          range = Option(n.get("range")).map(r => (Durations.unsafe(r.get("min").asText()), Durations.unsafe(r.get("max").asText()))),
          until = str("until")
        )
      case Some("settle-open-rounds") => Step.SettleOpenRounds
      case Some("rotate-key")         => Step.RotateKey(Option(n.get("withdrawOld")).forall(_.asBoolean()))
      case other                      => throw IllegalArgumentException(s"unknown step kind $other")

  /** The shipped scenarios: every scenario file the service carries, by the index file's list. */
  def shipped: Vector[Scenario] =
    val index = Option(getClass.getResourceAsStream("/scenarios/index.txt"))
      .map(s => try String(s.readAllBytes(), "UTF-8") finally s.close())
      .getOrElse(throw IllegalStateException("scenarios/index.txt is not on the classpath"))
    index.linesIterator.map(_.trim).filter(l => l.nonEmpty && !l.startsWith("#")).toVector.map { file =>
      val stream = Option(getClass.getResourceAsStream(s"/scenarios/$file"))
        .getOrElse(throw IllegalStateException(s"scenarios/$file is listed but not on the classpath"))
      val text = try String(stream.readAllBytes(), "UTF-8") finally stream.close()
      parse(text).fold(e => throw IllegalStateException(s"scenarios/$file: $e"), identity)
    }

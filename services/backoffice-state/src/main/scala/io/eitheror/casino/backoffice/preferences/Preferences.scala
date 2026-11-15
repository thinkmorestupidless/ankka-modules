package io.eitheror.casino.backoffice.preferences

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import com.thinkmorestupidless.ankka.core.{ComponentId, ErrorCode, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.backoffice.StateCodecs

import java.time.Instant
import scala.jdk.CollectionConverters.*
import scala.util.Try

/** A job the operator started at an owner: its reference there, its kind, when (data-model.md). No player. */
final case class Job(reference: String, kind: String, startedAt: Instant)

/**
 * An operator's preferences (FR-038). `worklistKinds` absent is the default: every kind the roles admit,
 * which the page's server knows and this service does not.
 */
final case class PreferencesState(theme: String = PreferencesRules.Dark, worklistKinds: Option[Set[String]] = None,
    onlineNotices: Boolean = false, jobs: List[Job] = Nil)

/** The keys a write names; the others stay as they are. */
final case class PreferencesWrite(theme: Option[String] = None, worklistKinds: Option[Set[String]] = None,
    onlineNotices: Option[Boolean] = None, jobs: Option[List[Job]] = None)

/** A write refused: `unknown_preference` (a key not of the four, or a player named) or `invalid_preference`. */
final case class PreferenceRefusal(code: String, message: String)

object PreferenceRefusal:
  val Unknown = "unknown_preference"
  val Invalid = "invalid_preference"

/**
 * What a preference may hold (data-model.md `Preferences`): four keys, a job three; anything else is refused
 * `unknown_preference`, so no field can carry a player. A value that names one (`player/…`, `player:…`) is
 * refused the same; a kind is a lower-case word and a reference an owner's opaque id, neither with a `/`.
 */
object PreferencesRules:
  val Dark  = "dark"
  val Light = "light"

  val MaxJobs = 20

  val Keys: Set[String]    = Set("theme", "worklistKinds", "onlineNotices", "jobs")
  val JobKeys: Set[String] = Set("reference", "kind", "startedAt")

  private val KindPattern      = "[a-z][a-z0-9_]{0,63}".r
  private val ReferencePattern = "[A-Za-z0-9][A-Za-z0-9._:-]{0,127}".r
  private val NamesPlayer      = "(?i)players?[/:].*".r

  private val mapper = ObjectMapper()

  private def unknown(what: String) = PreferenceRefusal(PreferenceRefusal.Unknown, what)
  private def invalid(what: String) = PreferenceRefusal(PreferenceRefusal.Invalid, what)

  /** The route's body read: a JSON object of some of the four keys. */
  def parse(text: String): Either[PreferenceRefusal, PreferencesWrite] =
    Try(mapper.readTree(text)).toOption.filter(n => n != null && n.isObject).toRight(invalid("the body is not a JSON object")).flatMap { o =>
      val names = o.fieldNames.asScala.toVector
      names.find(!Keys(_)) match
        case Some(k) => Left(unknown(s"no preference '$k'"))
        case None =>
          for
            theme <- field(o, "theme")(n => Option.when(n.isTextual)(n.asText).toRight(invalid("theme is dark or light")))
            kinds <- field(o, "worklistKinds")(strings("worklistKinds", _).map(_.toSet))
            on    <- field(o, "onlineNotices")(n => Option.when(n.isBoolean)(n.asBoolean).toRight(invalid("onlineNotices is true or false")))
            jobs  <- field(o, "jobs")(jobsOf)
            w      = PreferencesWrite(theme, kinds, on, jobs)
            _     <- check(w)
          yield w
    }

  /** Every rule a write is held to, whoever wrote it: the route's parse and the entity both. */
  def check(w: PreferencesWrite): Either[PreferenceRefusal, Unit] =
    val words = w.worklistKinds.toVector.flatten ++ w.jobs.toVector.flatten.flatMap(j => Vector(j.reference, j.kind))
    words.find(NamesPlayer.matches) match
      case Some(v) => Left(unknown(s"'$v' names a player; a preference holds none"))
      case None =>
        if w.theme.exists(t => t != Dark && t != Light) then Left(invalid("theme is dark or light"))
        else
          w.worklistKinds.toVector.flatten.find(!KindPattern.matches(_)) match
            case Some(k) => Left(invalid(s"'$k' is not a worklist kind"))
            case None =>
              w.jobs.toVector.flatten.find(j => !KindPattern.matches(j.kind) || !ReferencePattern.matches(j.reference)) match
                case Some(_) => Left(invalid("a job's kind is a word and its reference an owner's id"))
                case None    => Right(())

  /** The state after `w`: the keys it names replaced, at most 20 jobs kept, newest first. */
  def applied(s: PreferencesState, w: PreferencesWrite): PreferencesState =
    PreferencesState(
      theme = w.theme.getOrElse(s.theme),
      worklistKinds = w.worklistKinds.orElse(s.worklistKinds),
      onlineNotices = w.onlineNotices.getOrElse(s.onlineNotices),
      jobs = w.jobs.fold(s.jobs)(_.sortBy(_.startedAt)(using Ordering[Instant].reverse).take(MaxJobs))
    )

  private def field[A](o: JsonNode, name: String)(read: JsonNode => Either[PreferenceRefusal, A]): Either[PreferenceRefusal, Option[A]] =
    Option(o.get(name)) match
      case None                 => Right(None)
      case Some(n) if n.isNull  => Left(invalid(s"$name is not null"))
      case Some(n)              => read(n).map(Some(_))

  private def strings(name: String, n: JsonNode): Either[PreferenceRefusal, Vector[String]] =
    if !n.isArray then Left(invalid(s"$name is a list of words"))
    else
      val all = n.elements.asScala.toVector
      if all.forall(_.isTextual) then Right(all.map(_.asText)) else Left(invalid(s"$name is a list of words"))

  private def jobsOf(n: JsonNode): Either[PreferenceRefusal, List[Job]] =
    if !n.isArray then Left(invalid("jobs is a list"))
    else
      n.elements.asScala.toList.foldLeft[Either[PreferenceRefusal, List[Job]]](Right(Nil)) { (acc, j) =>
        acc.flatMap(done => jobOf(j).map(_ :: done))
      }.map(_.reverse)

  private def jobOf(j: JsonNode): Either[PreferenceRefusal, Job] =
    if !j.isObject then Left(invalid("a job is an object of reference, kind and startedAt"))
    else
      j.fieldNames.asScala.find(!JobKeys(_)) match
        case Some(k) => Left(unknown(s"a job holds no '$k'"))
        case None =>
          def text(k: String) = Option(j.get(k)).filter(_.isTextual).map(_.asText)
          (text("reference"), text("kind"), text("startedAt").flatMap(t => Try(Instant.parse(t)).toOption)) match
            case (Some(r), Some(k), Some(at)) => Right(Job(r, k, at))
            case _                            => Left(invalid("a job is an object of reference, kind and startedAt"))

/** One operator's preferences, its id the operator's subject (FR-038). */
final class Preferences(@scala.annotation.unused context: KeyValueEntityContext) extends KeyValueEntity[PreferencesState]:
  def emptyState: PreferencesState = PreferencesState()

  def write(w: PreferencesWrite): Effect[PreferencesState] =
    PreferencesRules.check(w) match
      case Left(r)  => effects.error(s"${r.code}: ${r.message}", ErrorCode.BadRequest)
      case Right(_) => effects.updateState(PreferencesRules.applied(currentState, w)).thenReplyState

  def get: ReadOnlyEffect[PreferencesState] = effects.reply(currentState)

  /** The erasure's part: the preferences deleted. True when there were any. */
  def erase: Effect[Boolean] =
    if currentState == emptyState then effects.reply(false) else effects.deleteEntity().thenReply(_ => true)

object Preferences extends KeyValueEntity.Companion[Preferences, PreferencesState](
      componentId = ComponentId("preferences"),
      stateSerializer = StateCodecs.serializer[PreferencesState]("preferences-state")
    ):
  import com.thinkmorestupidless.ankka.core.Serializers.given

  given Serializer[PreferencesWrite] = StateCodecs.serializer[PreferencesWrite]("preferences-write")
  given Serializer[PreferencesState] = StateCodecs.serializer[PreferencesState]("preferences-reply")

  def create(context: KeyValueEntityContext) = new Preferences(context)

  val write = command("write")(_.write)
  val erase = command("erase")(_.erase)
  val get   = query("get")(_.get)

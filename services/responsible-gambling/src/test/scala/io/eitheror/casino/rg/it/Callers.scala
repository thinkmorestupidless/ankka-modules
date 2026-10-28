package io.eitheror.casino.rg.it

import io.eitheror.casino.rg.Json
import io.eitheror.casino.rg.Json.*

import java.time.Instant
import scala.util.Try

/**
 * How each of the brand's services reads this service's answers, copied from their code so a drift in shape
 * fails here (T023, T026). `None` is a call that was not answered (unreachable, timed out). Each caller fails
 * closed: whatever it cannot read is `Unavailable`, never a decision.
 *
 *  - the wallet: `services/wallet/.../api/ResponsibleGambling.scala` (`read`, and `http`'s non-2xx rule);
 *  - game-provider: `services/game-provider/.../clients/Neighbours.scala` (`ResponsibleGamblingClient`);
 *  - players: `services/players/.../clients/Rg.scala` on `008-players` (`logIn`, `deposit`).
 */
object Callers:
  enum Read:
    case Allowed
    case Refused(decision: String)
    case Unavailable(why: String)

  import Read.*

  private def body(r: Reply) = Json.parse(r.text)

  def wallet(answer: Option[Reply]): Read = answer match
    case None                         => Unavailable("unanswered")
    case Some(r) if r.status / 100 != 2 => Unavailable(s"status ${r.status}")
    case Some(r) =>
      val b = body(r)
      b.flatMap(_.text("decision")) match
        case Some("allowed")                                => Allowed
        case Some(d @ ("locked" | "excluded" | "cooling_off")) => Refused(d)
        case Some("over_limit") if Seq("limit", "used", "remaining").forall(f => b.exists(_.long(f).isDefined)) => Refused("over_limit")
        case other                                          => Unavailable(s"decision $other")

  def gameProviderBet(answer: Option[Reply]): Read = answer match
    case Some(r) if r.status / 100 == 2 =>
      body(r).flatMap(_.text("decision")) match
        case Some("allowed")                                => Allowed
        case Some(d @ ("locked" | "excluded" | "cooling_off")) => Refused(d)
        case other                                          => Unavailable(s"decision $other")
    case Some(r) => Unavailable(s"status ${r.status}")
    case None    => Unavailable("unanswered")

  def gameProviderPlay(answer: Option[Reply]): Read = answer match
    case Some(r) if r.status / 100 == 2 || r.status == 403 =>
      val b = body(r)
      b.flatMap(_.bool("allowed")) match
        case Some(true)  => Allowed
        case Some(false) => Refused(b.flatMap(_.text("reason")).getOrElse("excluded"))
        case None        => Unavailable("no answer")
    case Some(r) => Unavailable(s"status ${r.status}")
    case None    => Unavailable("unanswered")

  def playersLogIn(answer: Option[Reply]): Read = answer match
    case Some(r) if r.status / 100 == 2 =>
      val b = body(r)
      b.flatMap(_.text("decision")).map(_.toLowerCase) match
        case Some("allowed")  => Allowed
        case Some("excluded") => Refused("excluded")
        case Some("cooling_off") =>
          val endsAt = b.flatMap(n => n.long("endsAt").map(Instant.ofEpochMilli).orElse(n.text("endsAt").flatMap(t => Try(Instant.parse(t)).toOption)))
          endsAt.fold(Unavailable("cooling_off without endsAt"))(_ => Refused("cooling_off"))
        case other => Unavailable(s"decision $other")
    case Some(r) => Unavailable(s"status ${r.status}")
    case None    => Unavailable("unanswered")

  def playersDeposit(answer: Option[Reply]): Read = answer match
    case Some(r) if r.status / 100 == 2 =>
      body(r).flatMap(_.text("decision")).map(_.toLowerCase) match
        case Some("allowed") => Allowed
        case Some(code)      => Refused(code)
        case None            => Unavailable("no decision")
    case Some(r) => Unavailable(s"status ${r.status}")
    case None    => Unavailable("unanswered")

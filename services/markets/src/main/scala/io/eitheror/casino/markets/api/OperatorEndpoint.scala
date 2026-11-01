package io.eitheror.casino.markets.api

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.core.{CommandError, EntityId}
import com.thinkmorestupidless.ankka.http.{Acl, EndpointClients, HttpEndpoint}
import com.thinkmorestupidless.predictions.markets.*
import com.thinkmorestupidless.predictions.markets.components.{MarketsComponents, Refused}
import io.eitheror.casino.contract.access.Routes
import io.eitheror.casino.markets.{Json, MarketsRuntime}
import io.eitheror.casino.markets.Json.*
import io.eitheror.casino.markets.terms.{MarketTerms, Terms}

import java.time.{Instant, LocalDate}
import scala.util.Try

import Answers.*

/**
 * `/v1/casino/backoffice/<slug>/markets` (contracts/routes.md): the brand's operators with the markets
 * permission (FR-004). The ACL checks the role, so the library's resolution port allows everything;
 * the brand's categories and currencies are checked here before the library sees a draft or a change
 * (FR-013), and a draft's own limits are written to its terms first (FR-014).
 */
final class OperatorEndpoint(components: MarketsComponents, clients: EndpointClients, authenticate: () => Acl)
    extends HttpEndpoint(s"/v1/casino/backoffice/${MarketsRuntime.settings.brand}/markets"):
  private def settings = MarketsRuntime.settings
  val acl: Acl         = Routes.operatorHolding(settings.brand, "markets", "platform_admin")(authenticate())

  private def market(id: String) = clients.componentClient.forEventSourcedEntity(EntityId(id))
  private def terms(id: String)  = clients.componentClient.forKeyValueEntity(EntityId(id))
  private def operator           = OperatorId(principal.subject)
  private def now                = MarketsRuntime.now()

  private def body(text: String)(f: JsonNode => Reply): Reply = Json.parseObject(text).fold(badBody)(f)

  postBody("/drafts") { (text: String) =>
    body(text) { b =>
      b.text("market").fold(field("market")) { id =>
        OperatorEndpoint.definition(b).fold(field, d =>
          offered(d).getOrElse(limits(b).fold(field, t =>
            // The limits are the market's once the library has taken the draft: a refused draft under
            // another market's id leaves that market's limits as they were.
            answer(market(id).call(components.market.draft).invoke(MarketCommand.Draft(MarketId(id), operator, d, now))) { _ =>
              terms(id).call(MarketTerms.set).invoke(t): Unit
              current(id)
            })))
      }
    }
  }

  postBody("/{market}/{command}") { (id: String, command: String, text: String) =>
    body(text) { b =>
      def run(c: MarketCommand): Reply = answer(OperatorEndpoint.send(components, clients.componentClient, id, c))(_ => current(id))
      def reason(f: String => MarketCommand): Reply = b.text("reason").fold(field("reason"))(r => run(f(r)))
      val window = MarketsRuntime.ReviewWindow
      command match
        case "change" =>
          OperatorEndpoint.definition(b).fold(field, d =>
            offered(d).getOrElse(limits(b).fold(field, t => changed(id, d, t))))
        case "approve"    => run(MarketCommand.Approve(operator, now))
        case "open"       => run(MarketCommand.Open(now))
        case "suspend"    => reason(MarketCommand.Suspend(operator, _, now))
        case "resume"     => run(MarketCommand.Resume(operator, now))
        case "close"      => reason(r => MarketCommand.Close(Some(operator), Some(r), now))
        case "move-close" =>
          OperatorEndpoint.instant(b, "close").fold(field("close"))(c => reason(MarketCommand.MoveClose(operator, c, _, now)))
        case "resolve" =>
          (b.text("outcome"), b.text("authority")) match
            case (None, _) => field("outcome")
            case (_, None) => field("authority")
            case (Some(o), Some(a)) => run(MarketCommand.Resolve(operator, OutcomeId(o), OperatorEndpoint.evidence(b), a, window, now))
        case "correct" =>
          b.text("outcome").fold(field("outcome"))(o => run(MarketCommand.Correct(operator, OutcomeId(o), OperatorEndpoint.evidence(b), window, now)))
        case "void" => reason(MarketCommand.Void(operator, _, OperatorEndpoint.evidence(b), window, now))
        case other  => refusal(404, "unknown_command", s"$other is not a command of a market")
    }
  }

  get("/{market}") { (id: String) => current(id) }

  /**
   * A change, and the limits it names. The book reads a market's limits once, when it opens, so a
   * change naming a limit is refused once the market is past approval; before that, a limit the change
   * leaves out keeps the drafted one, and a change of the limits alone (the library's `no change` to
   * the definition) is taken, which also sets them again when a draft's write of them failed.
   */
  private def changed(id: String, d: Definition, t: Terms): Reply =
    val named = t.maxOrderCost.nonEmpty || t.maxPosition.nonEmpty
    val state = market(id).call(components.market.get).invoke().state
    def write(): Reply =
      if named then
        val was = terms(id).call(MarketTerms.get).invoke()
        terms(id).call(MarketTerms.set).invoke(Terms(t.maxOrderCost.orElse(was.maxOrderCost), t.maxPosition.orElse(was.maxPosition))): Unit
      current(id)
    if named && !OperatorEndpoint.LimitsSettable(state) then
      val word = MarketState.word(state)
      refusal(409, "state", s"state: a market's limits are fixed when its book opens, and it is $word", "state" -> word)
    else
      try
        OperatorEndpoint.send(components, clients.componentClient, id, MarketCommand.Change(operator, d, now)): Unit
        write()
      catch
        case e: CommandError if named && Refused.unapply(e).exists(_.reason == "no change") => write()
        case e: CommandError                                                                  => refused(e)

  /** The market as it stands, with its record, in the library's JSON; 404 when nothing was drafted under the id. */
  private def current(id: String): Reply =
    val m = market(id).call(components.market.get).invoke()
    if m.state == MarketState.Empty then notFound else ok(m)

  /** The brand's categories and currencies (FR-013): a refusal naming the one not offered, else none. */
  private def offered(d: Definition): Option[Reply] =
    if !settings.categories.contains(d.category) then
      Some(refusal(400, "category", s"the brand does not offer the category ${d.category}", "category" -> d.category))
    else if !settings.currencies.contains(d.currency) then
      Some(refusal(400, "currency", s"the brand's wallet does not hold ${d.currency}", "currency" -> d.currency))
    else None

  /** A draft's or change's own limits, each above zero when given (data-model.md), or the field that is not. */
  private def limits(b: JsonNode): Either[String, Terms] =
    def one(name: String): Either[String, Option[Long]] = Option(b.get(name)).filterNot(_.isNull) match
      case None    => Right(None)
      case Some(_) => b.long(name).filter(_ > 0).map(Some(_)).toRight(name)
    for cost <- one("maxOrderCost"); position <- one("maxPosition") yield Terms(cost, position)

object OperatorEndpoint:
  /** The states in which a market's book has not opened, so its limits may still change. */
  val LimitsSettable: Set[MarketState] = Set(MarketState.Draft, MarketState.Approved)

  /** A command to the market under its own handler; the events it led to. */
  def send(c: MarketsComponents, client: com.thinkmorestupidless.ankka.sdk.ComponentClient, id: String, command: MarketCommand): Vector[MarketEvent] =
    val market = client.forEventSourcedEntity(EntityId(id))
    command match
      case x: MarketCommand.Draft     => market.call(c.market.draft).invoke(x)
      case x: MarketCommand.Change    => market.call(c.market.change).invoke(x)
      case x: MarketCommand.Approve   => market.call(c.market.approve).invoke(x)
      case x: MarketCommand.Open      => market.call(c.market.open).invoke(x)
      case x: MarketCommand.Suspend   => market.call(c.market.suspend).invoke(x)
      case x: MarketCommand.Resume    => market.call(c.market.resume).invoke(x)
      case x: MarketCommand.Close     => market.call(c.market.close).invoke(x)
      case x: MarketCommand.MoveClose => market.call(c.market.moveClose).invoke(x)
      case x: MarketCommand.Resolve   => market.call(c.market.resolve).invoke(x)
      case x: MarketCommand.Correct   => market.call(c.market.correct).invoke(x)
      case x: MarketCommand.Void      => market.call(c.market.void).invoke(x)
      case x: MarketCommand.Finalise  => market.call(c.market.finalise).invoke(x)

  def instant(b: JsonNode, name: String): Option[Instant] = b.text(name).flatMap(t => Try(Instant.parse(t)).toOption)

  /**
   * The definition a draft or change carries, or the name of the first field that cannot be read. What
   * each field must be (not blank, two or more outcomes, a close ahead) is the library's to refuse.
   */
  def definition(b: JsonNode): Either[String, Definition] =
    def text(name: String) = Right(b.textOr(name, ""))
    for
      question    <- text("question")
      description <- text("description")
      category    <- text("category")
      currency    <- text("currency")
      openTime    <- b.text("openTime").fold(Right(None))(t => Try(Instant.parse(t)).toOption.map(Some(_)).toRight("open time"))
      close       <- instant(b, "close").toRight("close")
      expected    <- b.text("expectedResolution").flatMap(t => Try(LocalDate.parse(t)).toOption).toRight("expected resolution date")
      criteria    <- text("criteria")
      authority   <- text("authority")
    yield Definition(question, description, category, b.strings("outcomes").map(OutcomeId(_)), currency, openTime, close, expected,
      criteria, authority)

  def evidence(b: JsonNode): Vector[Evidence] =
    b.objects("evidence").map(e => Evidence(e.textOr("source", ""), e.textOr("locator", ""), e.textOr("excerpt", "")))

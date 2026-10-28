package io.eitheror.casino.attribution.carriers

import com.github.plokhotnyuk.jsoniter_scala.core.writeToString
import com.thinkmorestupidless.ankka.core.{ComponentId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.attribution.{AttributionCodecs, Json}
import io.eitheror.casino.attribution.Json.text
import io.eitheror.casino.contract.facts.AttributionFact

/**
 * A fact as the feed and the webhooks carry it: rendered once by the contract's codec, so a page and a
 * webhook body hold exactly what the topic held.
 */
final case class FeedFact(factId: String, kind: String, json: String)

object FeedFact:
  def of(fact: AttributionFact): FeedFact =
    val json = writeToString(fact)
    FeedFact(fact.factId, Json.parse(json).flatMap(_.text("type")).getOrElse(""), json)

final case class FeedState(lastSeq: Long = 0, recent: Vector[String] = Vector.empty)

sealed trait FeedEvent
object FeedEvent:
  final case class Appended(seq: Long, fact: FeedFact) extends FeedEvent

sealed trait AppendResult
object AppendResult:
  final case class Added(seq: Long) extends AppendResult
  case object Seen extends AppendResult

object NetworkFeedCodecs:
  given Serializer[FeedState]    = AttributionCodecs.serializer[FeedState]("network-feed")
  given Serializer[FeedEvent]    = AttributionCodecs.serializer[FeedEvent]("network-feed-event")
  given Serializer[FeedFact]     = AttributionCodecs.serializer[FeedFact]("feed-fact")
  given Serializer[AppendResult] = AttributionCodecs.serializer[AppendResult]("append-result")

import NetworkFeedCodecs.given

/**
 * A network's feed ledger (data-model.md *NetworkFeed*; research R14), id the network id: each fact for the
 * network once, numbered. Its events are what `FeedRows` and `WebhookFanout` read.
 */
final class NetworkFeed(@scala.annotation.unused context: EventSourcedEntityContext) extends EventSourcedEntity[FeedState, FeedEvent]:
  def emptyState: FeedState = FeedState()

  def applyEvent(event: FeedEvent): FeedState = event match
    case FeedEvent.Appended(seq, fact) =>
      FeedState(seq, (currentState.recent :+ fact.factId).takeRight(NetworkFeed.MaxRecent))

  def append(fact: FeedFact): Effect[AppendResult] =
    if currentState.recent.contains(fact.factId) then effects.reply(AppendResult.Seen)
    else
      val seq = currentState.lastSeq + 1
      effects.persist(FeedEvent.Appended(seq, fact)).thenReply(_ => AppendResult.Added(seq))

  def get: ReadOnlyEffect[FeedState] = effects.reply(currentState)

object NetworkFeed extends EventSourcedEntity.Companion[NetworkFeed, FeedState, FeedEvent](
      componentId = ComponentId("network-feed"),
      stateSerializer = NetworkFeedCodecs.given_Serializer_FeedState,
      eventSerializer = NetworkFeedCodecs.given_Serializer_FeedEvent
    ):
  val MaxRecent = 10000

  override def snapshotEvery: Option[Int] = Some(100)

  def create(context: EventSourcedEntityContext) = new NetworkFeed(context)

  val append = command("append")(_.append)
  val get    = query("get")(_.get)

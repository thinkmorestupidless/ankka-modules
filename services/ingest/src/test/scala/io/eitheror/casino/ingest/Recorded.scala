package io.eitheror.casino.ingest

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.sdk.{CallTransport, ComponentClient}

import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.Future
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** One call a consumer or the refresh made: the component, the entity, the handler and the input's bytes. */
final case class Call(component: String, entity: String, method: String, payload: Array[Byte]):
  def as[I](using s: Serializer[I]): I = s.fromBytes(payload)

/**
 * A component client that records every call and answers each with the bytes `answer` gives (by default an
 * empty JSON object), for a consumer's or the refresh's suite, which assert on the entity each call names:
 * the testkit's `TestTransport` stubs by handler but does not say which entity a call was for.
 */
final class Recorded(answer: Call => Array[Byte] = _ => "{}".getBytes) extends CallTransport:
  private val made = ConcurrentLinkedQueue[Call]()
  val askTimeout: FiniteDuration = 5.seconds
  def calls: Vector[Call]         = made.asScala.toVector
  def client: ComponentClient     = ComponentClient(this)

  def ask(componentId: ComponentId, entityId: EntityId, method: MethodName, payload: Array[Byte], metadata: Metadata): Future[Array[Byte]] =
    val c = Call(componentId.toString, entityId.toString, method.toString, payload)
    made.add(c)
    Future.successful(answer(c))

  def tell(componentId: ComponentId, entityId: EntityId, message: Any): Unit = ()

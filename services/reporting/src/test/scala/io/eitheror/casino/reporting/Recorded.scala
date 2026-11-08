package io.eitheror.casino.reporting

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.sdk.{CallTransport, ComponentClient}

import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.Future
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** One call a consumer or a timed action made: the component, the entity, the handler and the input's bytes. */
final case class Call(component: String, entity: String, method: String, payload: Array[Byte]):
  def as[I](using s: Serializer[I]): I = s.fromBytes(payload)

/**
 * A component client that records every call and answers each with the bytes `answer` gives (by default `Recorded.answer`), as
 * attribution's: a consumer's suite asserts on the entity each call names, which the testkit's `TestTransport` does
 * not say.
 */
final class Recorded(answer: Call => Array[Byte] = Recorded.answer) extends CallTransport:
  private val made = ConcurrentLinkedQueue[Call]()
  val askTimeout: FiniteDuration = 5.seconds
  def calls: Vector[Call]         = made.asScala.toVector
  /** The calls but those to the topics' `Coverage`, which an intake consumer makes only for an earlier fact. */
  def entityCalls: Vector[Call]   = calls.filterNot(_.component == "coverage")
  def client: ComponentClient     = ComponentClient(this)

  def ask(componentId: ComponentId, entityId: EntityId, method: MethodName, payload: Array[Byte], metadata: Metadata): Future[Array[Byte]] =
    val c = Call(componentId.toString, entityId.toString, method.toString, payload)
    made.add(c)
    Future.successful(answer(c))

  def tell(componentId: ComponentId, entityId: EntityId, message: Any): Unit = ()

object Recorded:
  /** `true` for a command, an empty coverage for a coverage's observation. */
  val answer: Call => Array[Byte] = c => (if c.component == "coverage" then "{}" else "true").getBytes

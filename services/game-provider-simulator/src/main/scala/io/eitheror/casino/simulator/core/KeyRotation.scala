package io.eitheror.casino.simulator.core

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.sdk.*

import scala.concurrent.duration.*

final case class RotationState(boundMillis: Long, nextKeyId: Option[String], currentKeyId: Option[String], state: String)

/**
 * A rotation an operator asks for (FR-011): publish the next key, wait the brand's bound so it has
 * fetched the document, then promote the next key, which withdraws the old from the document. The
 * `rotate-key` step of a scenario does the same inside the scenario's own workflow.
 */
final class KeyRotation extends Workflow[RotationState]:

  def emptyState: RotationState = RotationState(0L, None, None, "idle")

  def start(boundMillis: Long): Effect[RotationState] =
    if currentState.state == "waiting" then effects.reply(currentState)
    else
      val s = RotationState(boundMillis, None, None, "waiting")
      effects.updateState(s).transitionTo(KeyRotation.publish.ref).thenReply(s)

  def get: ReadOnlyEffect[RotationState] = effects.reply(currentState)

  def publishStep: StepEffect =
    val next = Wiring.keys.publishNext()
    stepEffects.updateState(currentState.copy(nextKeyId = Some(next))).thenPause(currentState.boundMillis.millis, KeyRotation.promote.ref)

  def promoteStep: StepEffect =
    val current = Wiring.keys.promote()
    Wiring.keys.withdrawOld()
    stepEffects.updateState(currentState.copy(currentKeyId = Some(current), state = "done")).thenEnd

object KeyRotation
    extends Workflow.Companion[KeyRotation, RotationState](
      componentId = ComponentId("key-rotation"),
      stateSerializer = Codecs.serializer[RotationState]("rotation-state")
    ):
  given Serializer[RotationState] = Codecs.serializer[RotationState]("rotation-state")
  def create(context: WorkflowContext) = new KeyRotation
  val start   = command("start")(_.start)
  val get     = query("get")(_.get)
  val publish = step("publish")(_.publishStep)
  val promote = step("promote")(_.promoteStep)

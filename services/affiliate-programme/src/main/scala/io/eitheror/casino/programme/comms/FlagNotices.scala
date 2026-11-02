package io.eitheror.casino.programme.comms

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.programme.activity.{FlagState, FraudFlag}

/** A dismissed fraud flag tells its affiliate (FR-032), with the original's type and words, once per flag. */
final class FlagNotices(context: ConsumerContext) extends Consumer[FlagState, Unit]:
  def onMessage(s: FlagState): Effect =
    s.dismissedAt.filter(_ => s.affiliate.nonEmpty).foreach { at =>
      context.componentClient.forKeyValueEntity(EntityId(Notification.idFor(s"flag/${s.brand}/${s.flagId}/dismissed")))
        .call(Notification.write)
        .invoke(WriteNotification(s.affiliate, Some(s.brand), FlagNotices.Kind, FlagNotices.Body, at)): Unit
    }
    effects.done()

object FlagNotices extends Consumer.Companion[FlagNotices, FlagState, Unit](
      componentId = ComponentId("flag-notices"),
      source = ChangeSource.stateOf(FraudFlag)
    ):
  val Kind = "FRAUD_DISMISSED"
  val Body = "A fraud flag raised against your account has been reviewed and dismissed."

  def create(ctx: ConsumerContext) = new FlagNotices(ctx)

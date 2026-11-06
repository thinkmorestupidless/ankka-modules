package io.eitheror.casino.engagement

import com.thinkmorestupidless.ankka.core.ComponentDescriptor
import com.thinkmorestupidless.ankka.runtime.Ankka

/** Every component of the service (plan.md's structure), registered by the service and by its test kit alike. */
object EngagementService:
  val descriptors: Seq[ComponentDescriptor] = Seq.empty

@main def run(): Unit =
  val service = Ankka.service.registerAll(EngagementService.descriptors).withLocalKeyring().start(name = "engagement")
  sys.addShutdownHook(service.terminate())
  scala.concurrent.Await.result(service.whenTerminated, scala.concurrent.duration.Duration.Inf): Unit

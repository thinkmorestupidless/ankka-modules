package io.eitheror.casino.exchange

import com.thinkmorestupidless.ankka.core.ComponentDescriptor
import com.thinkmorestupidless.ankka.runtime.Ankka

/** Every component of the service (plan.md's table), registered by the service and by its test kit alike. */
object ExchangeService:
  val descriptors: Seq[ComponentDescriptor] = Seq.empty

@main def run(): Unit =
  val service = Ankka.service.registerAll(ExchangeService.descriptors).start(name = "exchange")
  sys.addShutdownHook(service.terminate())
  scala.concurrent.Await.result(service.whenTerminated, scala.concurrent.duration.Duration.Inf): Unit

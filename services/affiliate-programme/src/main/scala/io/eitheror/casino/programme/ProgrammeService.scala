package io.eitheror.casino.programme

import com.thinkmorestupidless.ankka.core.ComponentDescriptor
import com.thinkmorestupidless.ankka.runtime.Ankka

/** Every component of the service (data-model.md), registered by the service and by its test kit alike. */
object ProgrammeService:

  def descriptors: Seq[ComponentDescriptor] = Seq.empty

@main def run(): Unit =
  val service = Ankka.service.registerAll(ProgrammeService.descriptors).start()
  sys.addShutdownHook(service.terminate())
  scala.concurrent.Await.result(service.whenTerminated, scala.concurrent.duration.Duration.Inf): Unit

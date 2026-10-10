package io.eitheror.casino.sample

import com.thinkmorestupidless.ankka.http.HttpServer
import com.thinkmorestupidless.ankka.runtime.Ankka

/**
 * One endpoint per route class of spec 001 FR-020, deployed into a brand by the access suite
 * (tools/brands/src/it/scala/.../AccessSuite.scala) to show each recipe admits and refuses on a real
 * installation, with real tokens from the casino's realms. It holds no state.
 */
@main def run(): Unit =
  val service = Ankka.service
    .withExtension(HttpServer.of(_ => AccessEndpoint(sys.env.getOrElse("CASINO_BRAND", "goldvibe"))))
    .start()
  sys.addShutdownHook(service.terminate())
  scala.concurrent.Await.result(service.whenTerminated, scala.concurrent.duration.Duration.Inf): Unit

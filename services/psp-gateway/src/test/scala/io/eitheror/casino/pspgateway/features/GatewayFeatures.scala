package io.eitheror.casino.pspgateway.features

import ch.qos.logback.classic.{Logger, LoggerContext}
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.thinkmorestupidless.ankka.testkit.{GherkinSuite, LogCapturing}
import io.eitheror.casino.pspgateway.it.{Kit, Reply, SharedKit}
import io.eitheror.casino.pspgateway.sessions.Provider
import org.slf4j.LoggerFactory

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * A feature file of features/payments bound against the whole service and psp-stub (constitution
 * VII): the cashier is `FakeMerchant`, the providers are the stub's, and every id a scenario names is
 * its own, since the database outlives scenarios.
 */
abstract class GatewayFeatures(file: String) extends GherkinSuite(s"../../features/payments/$file") with LogCapturing:
  override val munitTimeout: Duration = 300.seconds

  protected def kit: Kit = SharedKit.kit
  protected var provider: Provider = Provider.DreamzPay
  protected var paymentId          = ""
  protected var last: Reply        = null

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    kit.reset()
    Provider.values.foreach(kit.credential("sb-1", _))
    provider = Provider.DreamzPay; paymentId = ""; last = null
    logged.list.clear()

  /** What the gateway's webhook pipeline logged during the scenario. */
  protected lazy val logged: ListAppender[ILoggingEvent] =
    val appender = ListAppender[ILoggingEvent]()
    appender.setContext(LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext])
    appender.start()
    LoggerFactory.getLogger("io.eitheror.casino.pspgateway").asInstanceOf[Logger].addAppender(appender)
    appender

  protected def logLines: Vector[String] = logged.list.asScala.toVector.map(_.getFormattedMessage)

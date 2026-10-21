package io.eitheror.casino.merchant.it

import com.thinkmorestupidless.ankka.testkit.LogCapturing

import scala.concurrent.duration.*

/** A suite on the JVM's one shared service; every id is fresh, so suites do not see each other's money. */
abstract class SharedKitSuite extends munit.FunSuite with LogCapturing:
  override val munitTimeout: Duration = 300.seconds
  protected def kit: Kit = SharedKit.kit
  override def beforeEach(context: BeforeEach): Unit = kit.reset()

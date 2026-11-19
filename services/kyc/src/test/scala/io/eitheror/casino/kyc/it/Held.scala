package io.eitheror.casino.kyc.it

/**
 * A suite whose kits are held to the kept description (spec 031 FR-015): after each test, every kit
 * started in this JVM fails on an answer of a player route its description does not allow, and is
 * cleared. A suite whose kit was never started holds nothing, and starts none.
 */
trait Held extends munit.FunSuite:
  override def afterEach(context: AfterEach): Unit =
    try Kit.assertHeld()
    finally super.afterEach(context)

package io.eitheror.casino.ingest.it

/**
 * A suite whose kits are held to the kept description (spec 031 FR-015): after each test, every kit
 * started in this JVM fails on an answer of the ticket's routes its description does not allow, and
 * is cleared. A suite whose lazy kit was never started holds nothing, and starts none. Suites in a fork run
 * one after another (no build sets `testForkedParallel`), so an answer is reported by the suite
 * whose test caused it.
 */
trait Held extends munit.FunSuite:
  override def afterEach(context: AfterEach): Unit =
    try Kit.assertHeld()
    finally super.afterEach(context)

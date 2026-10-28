package io.eitheror.casino.pspgateway.crypto

import com.thinkmorestupidless.ankka.testkit.LogCapturing

/** FR-006, SC-005: the original's `morphosis/adapter_test.go` `TestUnderpaid`, case for case. */
class UnderpaidSuite extends munit.FunSuite with LogCapturing:

  // (expected, received, underpaid, unreadable)
  private val cases = Vector(
    ("0.00500", "0.00500", false, false),
    ("0.00500", "0.005", false, false),
    ("0.00500", "0.00600", false, false),
    ("0.00500", "0.00499999", true, false),
    ("0.00500", "0.003", true, false),
    ("0.00500", "", true, true),
    ("", "0.005", true, true),
    ("0", "0.005", true, true)
  )

  cases.foreach { (expected, received, want, unreadable) =>
    test(s"TestUnderpaid: Underpaid(\"$expected\", \"$received\") is $want") {
      assertEquals(Underpaid(expected, Some(received)), want)
      // The original's error is the reason here: present exactly when an amount cannot be read.
      val reason = Underpaid.reason(expected, Some(received))
      if unreadable then assert(reason.exists(_.contains("not a")), clue(reason))
      else assertEquals(reason.isDefined, want)
    }
  }

  test("a missing received amount is short") {
    assert(Underpaid("0.005", None))
  }

  test("the comparison is exact, past a double's precision") {
    assert(Underpaid("0.100000000000000000001", Some("0.1")))
    assert(!Underpaid("0.1", Some("0.100000000000000000001")))
  }

  test("a fraction and an exponent read as big.Rat reads them") {
    assert(!Underpaid("1/200", Some("0.005")))
    assert(!Underpaid("5e-3", Some("0.005")))
    assert(Underpaid("5e-3", Some("0.0049")))
  }

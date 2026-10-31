package io.eitheror.casino.payouts.player

/** `PayoutDestinationRoutesSpec`'s name cases (R13): diacritics, case and spacing normalised. */
class NamesSuite extends munit.FunSuite:
  test("no name on the profile is PROFILE_INCOMPLETE"):
    assertEquals(Names.check("Ana", "Silva", None), Left(Names.Refusal.ProfileIncomplete))
    assertEquals(Names.check("Ana", "Silva", Some((" ", ""))), Left(Names.Refusal.ProfileIncomplete))

  test("a name that does not match is PAYOUT_DESTINATION_AML_BLOCKED"):
    assertEquals(Names.check("Anna", "Silva", Some(("Ana", "Silva"))).left.map(_.code), Left("PAYOUT_DESTINATION_AML_BLOCKED"))
    assertEquals(Names.check("Ana", "Silvas", Some(("Ana", "Silva"))), Left(Names.Refusal.Mismatch))

  test("a matching name passes, whatever its case, spacing and diacritics"):
    assertEquals(Names.check("  JOSÉ ", "Müller", Some(("jose", "muller"))), Right(()))
    assertEquals(Names.check("Zoë", "Ångström", Some(("ZOE", "angstrom "))), Right(()))

  test("one profile name blank still compares both"):
    assertEquals(Names.check("Ana", "", Some(("Ana", ""))), Right(()))
    assertEquals(Names.check("Ana", "Silva", Some(("Ana", ""))), Left(Names.Refusal.Mismatch))

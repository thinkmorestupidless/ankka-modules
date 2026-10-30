package io.eitheror.casino.kyc.policy

import io.eitheror.casino.kyc.record.Category

/** Research R9's table, row by row; every refusal of `Change`; and `substrate_test.go`'s seven fallbacks. */
class PolicySuite extends munit.FunSuite:
  import PolicyRefusal.*

  test("CURACAO: no gates, no currency or thresholds, a 30 day window, identity, suspend"):
    assertEquals(
      Profiles.of(Some("CURACAO")),
      Right(Policy(Gate.Off, Gate.Off, None, None, None, Some(30), Vector(Category.Identity), true, Profile.Curacao))
    )

  test("MGA: no gates, EUR, 200000 deposit and 1000000 source of funds, no window, identity and address, no suspend"):
    assertEquals(
      Profiles.of(Some("MGA")),
      Right(
        Policy(Gate.Off, Gate.Off, Some("EUR"), Some(200000L), Some(1000000L), None, Vector(Category.Identity, Category.ProofOfAddress), false, Profile.Mga)
      )
    )

  test("UKGC: both gates, GBP, 200000 source of funds, a 3 day window, identity and address, suspend"):
    assertEquals(
      Profiles.of(Some("UKGC")),
      Right(
        Policy(
          Gate.KycApproved,
          Gate.KycApproved,
          Some("GBP"),
          None,
          Some(200000L),
          Some(3),
          Vector(Category.Identity, Category.ProofOfAddress),
          true,
          Profile.Ukgc
        )
      )
    )

  test("a profile is named in any case; none is profile_required; an unknown one names the known, sorted"):
    assertEquals(Profiles.of(Some("ukgc")).map(_.profile), Right(Profile.Ukgc))
    assertEquals(Profiles.of(None), Left(ProfileRequired))
    assertEquals(Profiles.of(Some("  ")), Left(ProfileRequired))
    assertEquals(Profiles.of(Some("CUSTOM")), Left(UnknownProfile(Vector("CURACAO", "MGA", "UKGC"))))

  test("with no event the policy is the Curaçao default, marked as one"):
    assertEquals(Policy.Default, Profiles.Curacao.copy(isDefault = true))

  test("a change sets the profile CUSTOM, keeps what it leaves out and clears what it nulls"):
    val changed = Policy.change(Profiles.Mga, Change(playGate = Some("KYC_APPROVED"), sofThreshold = Some(None)))
    assertEquals(changed, Right(Profiles.Mga.copy(playGate = Gate.KycApproved, sofThreshold = None, profile = Profile.Custom)))
    assertEquals(Policy.change(Policy.Default, Change()).map(_.isDefault), Right(false))

  test("refused: a gate not NONE or KYC_APPROVED, naming the field"):
    assertEquals(Policy.change(Profiles.Curacao, Change(depositGate = Some("ALWAYS"))), Left(InvalidPolicy("depositGate")))
    assertEquals(Policy.change(Profiles.Curacao, Change(playGate = Some(""))), Left(InvalidPolicy("playGate")))

  test("refused: a negative threshold or window; zero is allowed"):
    val eur = Profiles.Mga
    assertEquals(Policy.change(eur, Change(depositThreshold = Some(Some(-1L)))), Left(InvalidPolicy("depositThreshold")))
    assertEquals(Policy.change(eur, Change(sofThreshold = Some(Some(-1L)))), Left(InvalidPolicy("sofThreshold")))
    assertEquals(Policy.change(eur, Change(windowDays = Some(Some(-1)))), Left(InvalidPolicy("windowDays")))
    assertEquals(Policy.change(eur, Change(depositThreshold = Some(Some(0L)))).map(_.depositThreshold), Right(Some(0L)))

  test("refused: a currency that is not three capital letters"):
    assertEquals(Policy.change(Profiles.Curacao, Change(currency = Some(Some("eur")))), Left(InvalidPolicy("currency")))

  test("refused: a threshold with no currency (currency_required), whether set now or left from before"):
    assertEquals(Policy.change(Profiles.Curacao, Change(depositThreshold = Some(Some(100L)))), Left(CurrencyRequired))
    assertEquals(Policy.change(Profiles.Mga, Change(currency = Some(None))), Left(CurrencyRequired))
    assertEquals(
      Policy.change(Profiles.Curacao, Change(currency = Some(Some("EUR")), depositThreshold = Some(Some(100L)))).map(_.currency),
      Right(Some("EUR"))
    )

  test("refused: an unknown category, or identity_back required (FR-013)"):
    assertEquals(Policy.change(Profiles.Curacao, Change(required = Some(Vector("selfie")))), Left(InvalidCategory("selfie")))
    assertEquals(Policy.change(Profiles.Curacao, Change(required = Some(Vector("identity_back")))), Left(InvalidCategory("identity_back")))

  // substrate_test.go, TestFetchRequiredDocTypes_*: the original read the policy over HTTP and fell back to
  // identity when the twin was not found, the network failed, the answer was not 2xx, the body was
  // malformed, or the key was absent. Here each of those five is the same thing, a brand whose policy
  // entity has no event, so they are one row.
  test("FetchRequiredDocTypes: NotFound, NetworkError, Non2xx, MalformedBody, KeyAbsent fall back to identity"):
    assertEquals(Policy.Default.required, Vector(Category.Identity))

  test("FetchRequiredDocTypes: ExplicitEmptyList stays empty"):
    assertEquals(Policy.change(Policy.Default, Change(required = Some(Vector.empty))).map(_.required), Right(Vector.empty))

  test("FetchRequiredDocTypes: ConfiguredList is returned as is"):
    assertEquals(
      Policy.change(Policy.Default, Change(required = Some(Vector("identity", "payment_method")))).map(_.required),
      Right(Vector(Category.Identity, Category.PaymentMethod))
    )

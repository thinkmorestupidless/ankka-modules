# tests/port/kyc

The original's answers to scenarios 28 and 29 (`tests/original/e2e-rg-kyc.patch`), which spec 009's
`ParitySuite` replays (one test per file).

Recorded on 2026-10-31 (T047) against the original at `a78decf1` on its KinD stack (`eitheror-dev`),
as `tests/original/README.md` *Recording the KYC scenarios* says. 35 of the 37 tests pass on both
runs; two skip on both and have no recording: `TestScenario28_KYCDocuments_FileOfUnacceptedTypeOrSizeRefused`
and `TestScenario29_KYCGates_DepositOverSofThresholdClearedAllowed` (`suite-result.json`).

How the run differed from those steps:

- The kind recipe's registration of `casino/kyc-status-rollup` (`IS_A Player`) was replaced by the
  chart's (`IS_A player.registered`, version 3), as production registers it. The first run, before
  that, failed the five tests that wait for the rollup.
- The casino pack's MinIO is published for amd64 only; an arm64-labelled copy of it was loaded into
  the cluster, and the bucket made with `aws s3 mb`, the pack's `mc` image being gone.
- Four tests differed between the two runs only in Keycloak's admin steps (a retried user update);
  they were recorded twice more, and those runs agree.
- The presigned addresses' `X-Amz-Credential` and `X-Amz-Signature` are redacted (the local MinIO's
  default key); `url` is compared for presence only.

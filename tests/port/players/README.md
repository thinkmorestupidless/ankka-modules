# players (spec 008, T029)

Recorded 2026-10-26 from the original at `a78decf1` (its `main` that day), on its own KinD stack, twice,
on fresh ids. Two sets:

- 57 recordings by `tests/original/players-driver/`, one per scenario of `features/players/*.feature`
  the original can show. Both runs gave the same results: 48 `pass` and 9 `fail` (below). The
  scenarios it cannot show are listed, with why, in the driver's `NotDriven`.
- The original's own `TestScenario18_MultiAccountDetection_IdentityMatch`, `_BonusBlock` and
  `_FraudCaseReview`, by `tests/original/recorder` (`e2e-recording.patch`), each `pass` on both runs.
  T029 counted four; the suite has three. `IdentityMatch` is skipped unconditionally in the original
  (casino-service was unstable on KinD when it was written); the skip was lifted in the local checkout
  for this run only. The recorder filed `_BonusBlock` and `_FraudCaseReview` under `realm`, their first
  request being the operator's sign-in; they were moved here, so their `service` still reads `realm`.
  Each was given the requirement it shows: `008/FR-036` for the match and the bonus block, `008/FR-037`
  for the review.

`services/players`' `ParitySuite` replays all 60; `port/Parity.scala` names the divergences.

## How they were made, where it differs from `tests/original/README.md`

- The KinD stack: `just dev-kind`, `just kind-install-wallet`, `just kind-install-casino` in a worktree
  of the original on a local branch, `local/recording-players`, never pushed. The Scala images need the
  original's Lightbend token and Akka licence, copied into that worktree. Its TL handlers need TinyGo
  0.42.0, the version `tl/guest/toolchain.go` pins (`TL_TINYGO` set to the official release).
  `eitheror-pack-casino-minio` stayed in `ImagePullBackOff` (its image is in the private registry);
  only KYC document uploads use it, which are spec 009's.
- The cluster's ports: casino-service is `localhost:8091`, Keycloak `localhost:8180` (8080 is hugin),
  and the substrate's twin API `localhost:9000` through a port-forward. The driver was run with
  `-casino-url http://localhost:8091 -keycloak-url http://localhost:8180 -substrate-url http://localhost:9000`.
- The operator: the realm-sync job's `dev-operator` (with the password that job sets and logs, not the
  `just` recipe's),
  through the `casino-backoffice` client (`-operator-client casino-backoffice`; the realm has no
  `backoffice` client). It holds the platform scope, not only spinvibe's.
- The outsider: `recording-outsider` in the `eitheror` realm, made for the run, with no realm role and
  no scope attribute.
- psp-stub ran on the host (`PSP_STUB_GATEWAY_URL=http://localhost:8087`) and `psp-gateway`'s provider
  base URLs were pointed at it with `kubectl --context kind-eitheror-dev set env`, ConnecX at
  `http://127.0.0.1:9` and `PSP_GATEWAY_BASE_URL` at `http://localhost:8087` (it was an ngrok address).
  The preflight (`-register http://localhost:8091`) then found every outside system stubbed. `players`
  drives no provider; the gateway was stubbed so that nothing could leave the machine.

## Where the original answered otherwise

Nine scenarios `fail` on the original, on both runs, each traced to its code at `a78decf1`. Each is a
named divergence in `Parity.scala` and in `plan.md`'s *Divergences*.

| Scenario | The original | Why the port differs |
|---|---|---|
| a registration with a password below the realm's policy is refused and nothing is created | `503 service_unavailable`: Keycloak's `invalidPasswordMinLengthMessage` is never mapped to its own `PasswordTooWeak` | the original's route means `422 password_too_weak`; its mapping is a bug |
| a closed player's sign-in is refused and their wallet balances are untouched | `200`: the sign-in gate refuses `self_excluded`, `suspended` and `blocked`, not `closed` | the run set `closed` on the twin; a real closure disables the realm user, so the original refuses it too, at the realm |
| a game session asked for a restricted or closed player is refused naming the status [closed] | `200`, for the same reason | as above |
| a refresh that is expired, reused or revoked is answered refresh expired [reused] | `200`: the realm template leaves `revokeRefreshToken` off | the modules tool turns rotation on when it creates the realm (`Realm.withPlayers`) |
| … [revoked] | `200`: the session revoke is fire-and-forget and does not take | the port awaits the revoke |
| a sign-out ends the player session and every game session under it and revokes the realm's session | the refresh after sign-out still `200`, as above | as above |
| a suspension with a reason ends every session, revokes the realm's sessions and carries the previous and new status | the refresh after suspension still `200` | as above |
| making a verified second email address the sign-in address makes the realm sign them in with it | the old address still signs in (`200`) | the port honours the player's choice of sign-in address |
| an operator without the brand's role is refused any action on its player however they signed in | `401`: a token with no operator standing is invalid credentials to it | the port answers `403`: a valid operator, without the brand |

`a password change … only when its link is followed` failed on the first runs because the driver
expected `200`; the original answers `202 verification_email_sent`, as the port does. The driver was
corrected and both runs made again.

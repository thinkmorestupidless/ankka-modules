# Spec 003 fixtures

Test data for `services/game-provider`. Nothing here is a secret: every key is a throwaway P-256
pair made for these tests and used nowhere else.

| File | What it is |
|---|---|
| `test-key.pem`, `test-key.pub` | The pair the test kit registers as the brand's provider public key and signs every callback with. PKCS#8 and SubjectPublicKeyInfo, PEM. |
| `other-key.pem`, `other-key.pub` | A second pair, for callbacks that must be refused: a wrong signature, a rotation. |
| `games.json` | The game list `fake-st8` serves at `/api/operator/v1/games` (the original's `tests/fake-st8/main.go`, `fakeGames`), in ST8's shape. |
| `st8-local.json` | A provider configuration for a local run against spec 020's simulator on `:8099` (quickstart step 5); put the base64 of `test-key.pem`'s DER in `operatorPrivateKey`. |

## Why the recordings are re-signed (FR-030)

The original's `fake-st8` and its Go suite both generate a fresh key pair on every run and register
the public half through `/st8/test/register-key`, a route this service does not serve. A recording's
`x-st8-sign` is therefore valid only for a key that no longer exists. The parity kit replays each
recorded body unchanged and signs it with `test-key.pem`; the recording's own signature is not compared.
`WrongSignature`'s refused step is signed with `other-key.pem`, so it is still refused for the reason it was.

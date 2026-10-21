# Recording the original's gateway, and replaying it

The method is `tests/original/README.md`'s, with `psp-stub` as every provider. Recordings land in
`tests/port/psp-gateway/` (research R16).

## Setup

- **The stack:** the original's KinD stack, as spec 004's recordings used.
- **psp-gateway's environment:**
  - every provider base URL points at `psp-stub` (`http://psp-stub:1810N`);
  - `UNITYFINANCE_API_URL` points at `http://psp-stub:18108/v1`;
  - `PSP_GATEWAY_BASE_URL` points at the gateway's own cluster address;
  - `PSP_GATEWAY_MERCHANT_SERVICE_URL` and `CASINO_SERVICE_URL` point at the driver's receiver.
- **Preflight:** `tests/original/preflight` refuses to start until every one of those answers.
- **Driver and runs:** `tests/original/gateway-driver/` runs its scenarios twice on fresh ids, against
  the gateway's node port. `tests/original/merge` makes the committed files.

## A scenario's steps

| Step | Recorded from | Replayed as |
|---|---|---|
| the initiate | the driver's call (`terminalProperties` carrying the stub's credentials, as the original requires) | `merchant`'s call by identity; the kit's `FakeMerchant` serves the same values from its credential routes |
| the provider call | `observe:` from `psp-stub`'s `/__received` | the same, from the replay's `psp-stub` |
| `setup:` stub control | the driver's `/__control` | the same |
| the webhook | the driver asks `psp-stub` to send it (`/__webhook`), and records the gateway's answer | the same, against the port |
| the outcome call | `observe:` from the receiver's log: path, body | `FakeMerchant` or `FakePayouts`'s log |
| the re-drive | `POST /internal/v1/psp/redrive-undelivered` with the secret | `time:` advance past `REDRIVE_INTERVAL` |

## What `vary` covers

Per step, as spec 004's recordings do:

- the payment id and the request id (fresh per run);
- the provider's own ids (`psp-stub` mints them);
- every timestamp, the Cozeni salt and the Morphosis token;
- every signature that covers any of those.

## Divergences in replay

Each divergence in plan.md *Divergences* names the steps it holds at. `ParitySuite` checks every
declared divergence still differs, and that nothing undeclared does.

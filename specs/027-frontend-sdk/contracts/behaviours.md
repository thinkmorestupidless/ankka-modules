# Contract: the behaviours

```ts
interface Behaviour<S extends { kind: string; allowed: readonly string[] }, A> {
  step(): S
  subscribe(listener: (step: S) => void): () => void
  actions: A            // each: (...args) => { ok: true } | { ok: false; notAllowedAt: S["kind"] }
  stop(): void          // ends timers and streams; sends what must outlive the page
}

const site = playerSite({ transport, clock? })        // one per page
site.session; site.deposit(); site.play; site.inbox; site.chat   // by the modules /api/site names
```

`site.play`, `site.inbox`, `site.chat` are `undefined` when the brand does not run their module.
Steps and fields are in [data-model.md](../data-model.md). Below, what moves each.

## Session

- `signIn` → `signing_in` → `signed_in`, or `refused`.
- `register` → `registering` → `awaiting_verification`, or `refused`. Never `signed_in`.
- `verifyEmail` → `verifying` → `signed_out` with `verified: true`, or `refused`.
- `resendVerification` → the same step with `resent: true`, or `refused` (`rate_limited` with the
  wait).
- `requestPasswordReset` → `signed_out` with `resetRequested: true`, whatever the brand answered.
- `signOut` → `signing_out` → `signed_out`, whatever the brand answered.
- `back` from `refused` → the step `during` names.
- `signed_in`: no key press, click or scroll for `idleMinutes` less `idleWarningSeconds` →
  `idle_warning`; `stay` → refresh → `signed_in`; nothing for `idleWarningSeconds` → `signed_out`
  with reason `idle`.
- any call answered `signed_out` after the one shared refresh was refused → `signed_out` with
  reason `refresh_expired`, after the re-read of R3.
- `play` reports a recent bet → the idle count starts again (a change from the original).

## Deposit

- `amount` —`confirm`→ `starting` (one `startDeposit` under a new request id; a second `confirm`
  is not allowed) → `choosing_terminal`; no terminals → `failed(no_terminals)`; a refusal → `refused`. A start not answered →
  `refused(service_unavailable)`, which also allows `retry`: the same amount under the same request
  id, back to `starting`.
- `choosing_terminal` —`choose`→ by `schemes.ts`: `choosing_coin`; or `profile` read, then
  `details` when a need is missing or malformed, else `submitting`.
- `details` —`giveDetails`→ `changeProfile` (its failure ignored, as the original) → `submitting`.
- `submitting` → one `submitCashier` → read `payment` every 1.5 s:
  `AWAITING_REDIRECT` → `waiting_at_provider`; `AWAITING_CRYPTO` → `waiting_on_crypto`;
  `AWAITING_PAYMENT` → `waiting`; `COMPLETED` → `succeeded`; `FAILED`, `EXPIRED` → `failed` with the payment's `failureCode`.
  A refused submit → `failed` with the cashier's code; `PAYMENT_REJECTED` carries its `message`
  as `words`.
- any waiting step: read every 3 s; only an outcome moves it; 20 minutes from the submit →
  `failed(timed_out)`; `close` → `abandonCashier`, not awaited.
- `succeeded` → the funds are read.
- `back`: from `choosing_coin`, `details` → `choosing_terminal`; from `refused`, `failed`,
  `choosing_terminal` → `amount`.

`schemes.ts`, the original's table:

| Scheme | Needs |
|---|---|
| `CRYPTO` | a coin, from the original's list in `DepositModal.tsx` |
| `SKRILL`, `NETELLER`, `PAYSAFECARD`, `RAPID` | first name, last name |
| `APPLE_PAY`, `GOOGLE_PAY` | name; a telephone number in international form |
| `INTERAC_ETRANSFER` | name; a 10-digit mobile number; a province |
| `VISA`, `MASTERCARD`, `CARD_VISA`, `CARD_MASTERCARD`, `PAY_ID`, `INTERAC` | name; telephone; address line; state (a Canadian province code in `CA`) |
| any other | nothing |

## Play

- `pick`: a visitor → `needs_registration` (fun mode: `needs_sign_in`); no playable funds and not
  fun mode → `needs_deposit` (the site opens the deposit); else `launching`.
- `launching`: `launch` and `game` together. A refusal → `refused`. The game prohibited or
  restricted in the player's country → `endGameSession`, then `refused(blocked_in_territory)`.
  Else `playing`.
- `playing`: every 5 minutes `gameSession`; `lastBetAt` within 6 minutes → the session refreshes
  and is not idle. With an interval from `protection`: once the time shown since the last check
  reaches it → `reality_check`. Time hidden is not counted and hiding starts nothing again, so no
  check falls due while hidden (a change from the original).
- `reality_check` —`keepOn`→ `acknowledgeRealityCheck` → `playing`, the interval starting again;
  —`stop`→ `endGameSession` → `ended`.
- `close`, the page leaving (`pagehide`), sign-out → `endGameSession` (no body: `players` records the
  player's leaving), sent with `keepalive`.

## Inbox

- signed in → `connecting` → `push` opens, `ready` → `connected`, `unread` read.
- `signal`, the page becoming visible, `refresh` → `unread` read again.
- the stream ending or `closing` → `connecting` after `min(1 s × 2^attempt, 30 s)`; `ready` sets
  `attempt` to 0.
- signed out → `closed`.

## Chat

- `send` from `ready` or `ended` (a new session id after `ended`) → `answering`; `delta` appends;
  `confirm_action` then `done` → `awaiting_approval`; `done` → `ready`; `error` → `ready` with the
  words as the agent's turn; the stream ending with no `done` → `ready`, the turn `interrupted`.
- before the stream: `agent_unavailable` → `unavailable`; `too_many_turns` → `refused`;
  `conversation_ended` → `ended`; `session_not_yours` → `refused`, and the session id is dropped;
  `Session already active` → `awaiting_approval` from the refusal's `details`.
- `dismiss` from `refused` → `ready`. `unavailable` allows nothing.
- `approve`, `cancel` (sent as `reject`) → `deciding` → `action_result` (approve only), `delta`, `done` → `ready`;
  `already_decided` → `ready`.
- the session id is kept in `sessionStorage` under `eo_chat_<playerId>` and removed at sign-out.

## The adapter

```tsx
const { step, actions } = useBehaviour(site.deposit)   // re-renders on each step
<PlayerSite site={site}>…</PlayerSite>                  // context only
```

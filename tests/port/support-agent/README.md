# support-agent (spec 017, T055)

Recorded 2026-11-11 from the original at `7630feb0`, twice, on fresh players, by
`tests/original/support-agent-recorder.py`. There were 19 recordings, and the two runs agreed on every step, so no
recording carries a `vary`.

| Recordings | Requirement |
|---|---|
| `a read tool answers the model [<tool>]`, one per read tool (6) | 017/FR-010 |
| `a reply is sent as one delta and done` | 017/FR-003 |
| `a write tool waits for the player's decision [<tool>, approve\|reject]`, for each write tool (10) | 017/FR-013 |
| `what the routes answer around a pending decision` | 017/FR-017 |
| `the routes refuse a player with no valid token` | 017/FR-002 |

`services/support-agent`'s `ParitySuite` replays them, with the divergences in plan.md's *Divergences* marked.

## The run

- **The agent:** `starter-packs/casino/agent` ran on the host, from the recording worktree
  `eitheror-platform-recording-rewards` (uvicorn on `18080`, Python 3.12, the dependencies in its
  `pyproject.toml`). This kept the casino pack's helm release untouched, because that release was in a
  failed state. The agent reached the kind stack's substrate internal API (`9001` → `19101`) and the
  wallet's internal port (`9001` → `19102`) through reconnecting port-forwards (`kubectl` with a
  kind-only kubeconfig).
- **The model:** PydanticAI's `FunctionModel` replaced Anthropic, through a patch to `agents/motoko.py`
  that was never committed to the original, under `MOTOKO_SCRIPTED=1`. A message
  `script:{"tool": …, "args": …}` makes the model ask for that tool, and `script:{"text": …}` makes it
  answer with that text. After a tool, the model answers `result: <what the tool returned, as JSON>`.
  So a recording shows each tool's return exactly as the original's model saw it.
- **The player:** each scenario registers its own player on casino-service (`8080` → `18091`) and
  deposits 50.00 EUR through the wallet's internal deposit route.
- **The token:** the player's token is the hand-off JWT (HS256 with `HANDOFF_SHARED_SECRET`, the run's
  own value) carrying `player_id`, `session_id`, `brand_id` `spinvibe` and `currency` `EUR`.
- **The secrets:** the wallet's `WALLET_INTERNAL_SECRET` was read from its deployment into the run's
  environment and never written down. No recording carries a token or a secret.

## What the run found about the original

- **The original cannot read the wallet.** `get_wallet_balance` and `get_transaction_history` send
  `X-Internal-Secret` on its own. The wallet requires `X-Internal-Brand-Id` and HMAC(secret, brand)
  (`WalletRoutes.requireInternalAuth`), and answers 403. The turn then ends with an `error` frame that
  carries the internal URL. The rebuild reads the balance through the wallet's own route (FR-010) and
  never shows an internal address (FR-021).
- **`get_player_state` reads the twin by the bare player id.** The players' twins are kept under
  `casino-<brand>`, so every field answers `unknown` or empty.
- **A write reports success whatever the substrate answered.** `action_result` is always
  `success: true`, and a missing result field reads as empty: `" submitted"`,
  `"Withdrawal reference: ."`, `"Self-exclusion applied at "`. The rebuild carries the owning service's
  answer, refusals included (plan, *Divergences*: `action_result.success` may be `false`).
- **The tools are named `tool_<name>`**, because they are the decorated functions' names. contracts/tools.md
  names them without the prefix.
- **The chat's stream stays open across the decision.** `confirm_action` comes first. Then, once
  `/resume` has answered `{"ok": true}`, the same stream carries `action_result` (on approve only), the
  reply's one `delta`, and `done`.

## Reading them

- Values are tokenised: `{playerId}`, `{email}`, `{session}`, `{token}` and `{paymentRef}`.
- The chat's response `body` is its frames, each `{event, data}`, in order.
- A write scenario's chat step holds the whole stream, including the frames that came after the
  resume step, which follows it in `steps`.

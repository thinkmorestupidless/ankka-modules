# support-agent

Motoko, a brand's player support agent (spec 017), on ankka 0.16's agent model:
- answers a signed-in player about their account from the parts that own the answer (`players`,
  `wallet`, `payouts`, `responsible-gambling`, `rewards`), never from a figure of its own;
- asks the owning part to act (a withdrawal, a deposit limit, a break, an exclusion) only once the
  player approves, keyed by the approval so it runs once;
- keeps each turn inside its remit with guardrails, a turn rate and a daily budget per brand;
- hands the player to the brand's live chat when it cannot help.

An ankka 0.16.2 service in Scala, one sbt build. It is off for a brand until an operator turns it on.
The transcript, the approvals and their decisions are ankka's session memory, held under the player's
subject and sealed by the keyring (feature 042); this service's own entities hold no message text.

## Components

| Component | Kind | What it holds or does |
|---|---|---|
| `motoko` | agent, per session | the turn: the brand's prompt version, the player's context, the tools, the guardrails, the model |
| `support-conversation` | event sourced, per session | the trail ankka's memory does not keep: the player, the version, approvals, decisions, rejections, the end |
| `support-configuration` | key value, per brand | the current configuration version and the changes kept for the audit |
| `support-config-version` | key value, per version | one version's body, written once |
| `support-turn-window` | key value, per player | the turns of the last minute (FR-024) |
| `support-budget` | key value, per brand and UTC day | the model's tokens counted, each session once |
| `support-forget-timer` | timer | forgets a conversation untouched for the brand's retention (FR-027) |
| `support-conversations` | view | a row per conversation, for the operator's list and status |
| `support-audit-publisher`, `support-config-audit` | consumers | `platform.audit` (contracts/audit.md) |

The routes (contracts/routes.md), one endpoint per prefix:

| Prefix | Who calls it |
|---|---|
| `/v1/casino/agent/<slug>` | the player: `POST /run/chat_with_player` and `POST /resume/{session}`, each a stream |
| `/v1/casino/agent-config/<slug>` | operators: the configuration, its versions, the status, the conversations |
| `/v1/casino/agent-service/<slug>` | the installation: `/health` |

A turn's text is held until ankka's output guardrails pass it, so a rejected reply reaches no one and
the model is asked again, at most twice (research, *Found during implementation*).

## Settings

| Variable | What it says | Default |
|---|---|---|
| `CASINO_BRAND`, `CASINO_PROJECT` | the brand's slug and its project | — (the project is the slug) |
| `CASINO_REAL_MONEY` | whether the brand takes real money; safeguarding is then required | `false` |
| `AGENT_MODEL_PROVIDER` | `anthropic`, or `scripted:<file>` for a local run with no key | `anthropic` |
| `MODEL_PROVIDER_KEY` | the Anthropic key, from the module's `model-provider-key` secret | — |
| `AGENT_JUDGE` | `jev` turns the judged guardrails on (with `TYPESAFE_API_KEY`); `none` or absent leaves them off | off |
| `AGENT_COMPACTION_BYTES`, `AGENT_COMPACTION_RECENT` | when a session is compacted, and how many recent messages it keeps | |
| `ANKKA_AUTH_*` | the player and staff sign-in issuers | |
| `ANKKA_KAFKA_BOOTSTRAP_SERVERS` | the broker; absent publishes in memory (a local run) | |
| `ANKKA_KEYRING_ADDRESS` | the installation's keyring; absent keeps keys in this service's database | |

Everything a brand changes without a deploy (the prompt, the hand-off, safeguarding, the personal
fields, the model, the limits, the retention, whether it is on) is the configuration, through the
operator's routes; each change is a version and an audit record. `modules/support/module.json`
deploys the service; `just modules render <slug>` shows a brand's.

## What erasure does not reach

A player's erasure makes every one of their conversations unreadable here (FR-019). What a session
already sent to the model provider is beyond the installation's erasure: it is the provider's to keep
or delete under its own terms.

## For spec 019 (the Spinvibe migration)

- The player site's proxy forwards the player's realm token to `/v1/casino/agent/<slug>`; the player
  is the token's, never a body value.
- The chat widget reads an approved action's result from the resume's stream (`action_result`,
  then the reply), since ankka's gateway ends a response at 15 seconds and a stream cannot wait for
  the player's decision.

## Run it locally

```bash
cd services/support-agent
CASINO_VERSION=0.2.0-SNAPSHOT sbt schema   # ankka's schema into target/ddl
docker compose up -d                       # Postgres on 5444
cd ../.. && just support-agent-run         # HTTP on 9020, as brand goldvibe
```

Its neighbours are their own local runs (the recipe names their ports). `AGENT_MODEL_PROVIDER=scripted:<file>`
answers from a JSON array of replies, each `{"text": …}` or `{"tool": …, "arguments": {…}}`.

## Test it

```bash
just support-agent-test   # the unit suites (the Docker ones run only with -Dit=true)
just support-agent-it     # the integration, parity and feature suites (Docker)
```

- the unit suites test each part alone: the guardrails, redaction, summaries, softening, the hand-off
  filter, the configuration rules, the limits, the audit mapping, the row, the prompt;
- `it/` runs the service against Postgres with the five neighbours faked (`FakeNeighbours`), the model
  and the judge scripted, an in-memory broker and a clock the kit moves;
- `features/` binds the scenarios of `features/support-agent/`;
- `port/ParitySuite` replays the original's recordings in `tests/port/support-agent/`, the differences
  marked under plan.md's *Divergences*.

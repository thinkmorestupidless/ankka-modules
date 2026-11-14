# Quickstart: support-agent

These steps check the service against the spec. Routes are in
[contracts/routes.md](contracts/routes.md) and the tools in [contracts/tools.md](contracts/tools.md).

## Prerequisites

- Docker, for Postgres and the integration suites.
- The contract published under a version of this branch's own (contract snapshots collide across
  worktrees):

  ```bash
  CASINO_VERSION=0.2.0-017-SNAPSHOT sbt contract/publishLocal
  ```

- No model key. Every suite runs on `TestModelProvider`, and a local run uses the scripted provider
  named by `AGENT_MODEL_PROVIDER=scripted:<file>`.

## The suites

| Command | Proves |
|---|---|
| `just support-agent-test` | the pure packages: `Summaries`, `Redaction`, `Rate`, `Budget`, `Handoff`, `Softening`, `Mapping`, `ConfigRules`; the original's read, write and RG tool tests ported |
| `just support-agent-it` | the kit over Postgres, with fakes for the five neighbours: every scenario in `features/support-agent/` bound; `ParitySuite` replaying `tests/port/support-agent/` |
| `just service payouts test`, `… responsible-gambling test`, `… players test` | amendments A1, R1–R4, P1–P3 and their keys |
| `just features` | 0 findings |

## Run it locally

```bash
just support-agent-run          # port 9020, Postgres on 5444, CASINO_BRAND=goldvibe
```

The run needs the five neighbours running locally, on the ports the recipe names
(`-Dankka.local-services.<name>=…`). It also needs the local installation's realms
(`auth-casino.127.0.0.1.sslip.io:8443`) for the player's and the brand admin's tokens:

1. `PUT /v1/casino/agent-config/goldvibe` with `{"enabled": true}` as a brand admin. That answers
   200 on a brand without real money. With `CASINO_REAL_MONEY=true` and no safeguarding message, it
   answers 422 `safeguarding_not_configured`.
2. `POST /v1/casino/agent/goldvibe/run/chat_with_player`
   `{"message":"What is my balance?","session_id":"s-1"}` with a player token. The stream carries
   `delta`s naming the balance in major units, then `done` (T043).
3. With the script asking `request_withdrawal(1000, "GBP")`, the stream carries `confirm_action`
   `{action_name:"request_withdrawal", summary:"Withdraw 10.00 GBP from your account", session_id:"s-1"}`
   and then `done`.
4. `POST /v1/casino/agent/goldvibe/resume/s-1` `{"action":"approve"}`. The stream carries
   `action_result` `{success:true, detail:"<requestId> submitted"}`, a `delta`, and `done` (T044).
   The fake payouts saw one request, keyed by the approval id. A second resume answers 409
   `already_decided`.
5. `GET /v1/casino/agent-config/goldvibe/status` shows the day's tokens.
6. Read the audit records from the run's `platform.audit` (the local broker's console). There is one
   per tool call, request and decision, and none carries message text.

## Recording the original (needs the user)

[research.md](research.md) R13 says what to record:

1. Run the original on kind with `casinoAgent.enabled: true` and PydanticAI's `FunctionModel` in
   place of Anthropic. Every kubectl call names `--context kind-…`.
2. Record T043, T044, and each write tool's approve and reject with ankka-port.
3. Put the recordings under `tests/port/support-agent/`. `ParitySuite` replays them.

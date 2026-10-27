# Facts: responsible-gambling

`RgPublisher` folds `ProfileEntity` events into `casino.players`, keyed by the player id; the CloudEvents id
is `profile/<player>/<sequence>`. `AuditPublisher` writes `platform.audit`. Nothing else is produced: the
engagement events (`deposit_limit_set`, `loss_limit_set`, `session_limit_set`, `rg_limit_loosened`,
`cooling_off_set`, `self_exclusion_activated`) are spec 014's, derived from these (FR-030).

## casino.players

| Event | Family | Fields |
|---|---|---|
| `LimitSet` (tighter, or an operator's) | `rg_limit_applied` | `limit_type`, `period`, `currency`, `value`, `caused_by` `set` (none before) or `lowered` |
| `LimitSet` by an operator, looser | `rg_limit_applied` | `caused_by` `set` |
| `PendingApplied` | `rg_limit_applied` | `caused_by` `applied`, or `removed` when the value is none |
| `LimitRemoved` (operator) | `rg_limit_applied` | `value` none, `caused_by` `removed` |
| `PendingStaged` | none | the staged change is not a limit yet |
| `CaseOpened` | `rg_case_opened` | `rg_case_id`, `case_type`, `trigger`, `closes_at`, `permanent` (self-exclusion only) |
| `CaseClosed` | `rg_case_closed` | `rg_case_id`, `case_type`, `closure` (`expired` \| `lifted` \| `closed`) |
| every other event | none | |

`limit_type` is the original's name (`DEPOSIT_DAILY` … `SESSION_MINUTES`), so `players`' `RgLimitsConsumer`
(008) reads `SESSION_MINUTES` unchanged; `period` is `daily` | `weekly` | `monthly` (none for session).

`player_self_excluded`, `player_reinstated` are `players`', produced when the sync timer has it set the
status (FR-018, spec 008 FR-032).

## Contract additions (additive; `contract/.../facts/CasinoPlayers.scala`)

```scala
final case class RgLimitApplied(playerId: String, limitType: String, period: Option[String], value: Option[Long],
    causedBy: String, occurredAt: Instant, currency: Option[String] = None) extends CasinoPlayersFact

final case class RgCaseOpened(playerId: String, rgCaseId: String, caseType: String, trigger: String,
    closesAt: Option[Instant], occurredAt: Instant, permanent: Option[Boolean] = None) extends CasinoPlayersFact
```

`just schemas` regenerates `contract/schemas/casino.players.v1.json`; both fields are optional, so every
reader already deployed accepts the new messages (constitution IV).

## platform.audit

One record per operator event: `service` `responsible-gambling`, `action` one of `limit-set`,
`limit-remove`, `break-set`, `break-lift`, `exclude`, `return-request`, `case-open`, `case-note`,
`case-close`, `settings`; `subject_kind` `player`; `changes` before and after for values, and a note or
reason **named** with no value (spec 018 FR-003; FR-032 here).

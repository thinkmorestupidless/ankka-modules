# The original's audit, held here

The original's audit query (`POST /internal/audit/query`) is internal and unauthenticated, and nothing
in a brand calls it (spec 018 research R1, R16). What it demonstrates is held by
`services/audit/src/test/scala/io/eitheror/casino/audit/port/ParitySuite.scala`, one test per row of
`specs/018-audit-and-proposals/contracts/recording.md`.

| Row | Original | How it is held |
|---|---|---|
| 1 | `TestScenario2_ActionProposeExecute`, last assertion | see the row's note below |
| 2 | `TestRunAuditQuery_empty` (`cli/epipe/cmd/audit_test.go:29`) | translated from the test's fixture |
| 3 | `TestRunAuditQuery_events` (`:43`) | translated: the fixture's two events written in a brand and queried |
| 4 | `TestRunAuditQuery_filterPipelineKind` (`:88`) | translated: by action, `since: 1h` as `from` |
| 5 | `TestAuditQuery_HTTP` (`cli/epipe/substrate/client_test.go:321`) | translated: the records under `records` |
| 6 | `PseudonymRegistrySpec` | see the row's note below |

Rows 2–5 are tests of the original's CLI over a stub client, so there is nothing to record from a
running original: their fixtures are the expectation. Divergences, named in each test that meets one:
oldest first with a cursor, where the original answers newest first and pages by `hasMore` (FR-013);
the actor is who acted, by pseudonym, where the original's is the twin.

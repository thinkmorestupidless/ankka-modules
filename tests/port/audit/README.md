# The original's audit, held here

The original's audit query (`POST /internal/audit/query`) is internal and unauthenticated, and nothing
in a brand calls it (spec 018 research R1, R16). What it demonstrates is held by
`services/audit/src/test/scala/io/eitheror/casino/audit/port/ParitySuite.scala`, one test per row of
`specs/018-audit-and-proposals/contracts/recording.md`.

| Row | Original | How it is held |
|---|---|---|
| 1 | `TestScenario2_ActionProposeExecute`, last assertion | translated from the assertion (note below) |
| 2 | `TestRunAuditQuery_empty` (`cli/epipe/cmd/audit_test.go:29`) | translated from the test's fixture |
| 3 | `TestRunAuditQuery_events` (`:43`) | translated: the fixture's two events written in a brand and queried |
| 4 | `TestRunAuditQuery_filterPipelineKind` (`:88`) | translated: by action, `since: 1h` as `from` |
| 5 | `TestAuditQuery_HTTP` (`cli/epipe/substrate/client_test.go:321`) | translated: the records under `records` |
| 6 | `PseudonymRegistrySpec` | translated from the spec's four assertions (note below) |

Rows 2–5 are tests of the original's CLI over a stub client, so there is nothing to record from a
running original: their fixtures are the expectation. Divergences, named in each test that meets one:
oldest first with a cursor, where the original answers newest first and pages by `hasMore` (FR-013);
the actor is who acted, by pseudonym, where the original's is the twin.

**Row 1** is translated, not recorded. Recording it means starting the original's whole stack
(`just test-e2e-scenario 2` in `eitheror-platform`), which stops the ankka installation that shares the
kind cluster and the ports. The test asserts only that, after a mutation, the query answers at least
one record with a `visibilityTier`; the translation writes an operator's adjustment in a brand, asks
for it under the brand's compliance role by its correlation, and holds that exactly one record comes
back with a visibility. Divergence: two visibility classes (`brand`, `installation`, FR-020) where the
original has three tiers.

**Row 6** is translated from `PseudonymRegistrySpec`: the same pseudonym for one real id over five
calls, a UUID unequal to the id, another pseudonym for another id. It adds what the in-memory original
could not show: the same pseudonym after the service restarts, since the registry is a key value
entity, and a registry id that does not contain the real id (a keyed digest, research R3).

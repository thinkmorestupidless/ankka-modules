# Recordings and parity tests

The original's audit is internal (research R1); what it demonstrates is kept as parity tests in
`services/audit/src/test/.../port/ParitySuite.scala` over `tests/port/audit/`.

| # | Original | Recorded how | Parity test here |
|---|---|---|---|
| 1 | `TestScenario2_ActionProposeExecute`, its last assertion (`tests/e2e/scenario2_action_propose_execute_test.go:81-95`): after a mutation, the audit query answers a record with a visibility | `just test-e2e-scenario 2` on the original, then the query on its internal port (19001), saved as JSON | an operator's adjustment on a brand leaves one stored record, with a visibility, readable by the brand's compliance role |
| 2 | `TestRunAuditQuery_empty` (`cli/epipe/cmd/audit_test.go:29`) | translated from the test's fixture | a query that matches nothing answers no rows and no cursor |
| 3 | `TestRunAuditQuery_events` (`:43`) | translated | a query answers each matching record with its actor, action and subject |
| 4 | `TestRunAuditQuery_filterPipelineKind` (`:88`) | translated | a query by action and `from` answers only those |
| 5 | `TestAuditQuery_HTTP` (`cli/epipe/substrate/client_test.go:321`) | translated | the answer's records are under `records` |
| 6 | `PseudonymRegistrySpec` | translated | one pseudonym per real id, the same after a restart |

**Marked divergences**: the original answers newest first and pages by `hasMore`; this answers
oldest first with a cursor (FR-013). The original's actor is the twin; here it is who acted. Each
parity test that meets one says so in its name.

Where the original's stack cannot be started locally, row 1 is translated from the Go test's
assertion as rows 2–6 are, and `tests/port/audit/README.md` says which rows were recorded.

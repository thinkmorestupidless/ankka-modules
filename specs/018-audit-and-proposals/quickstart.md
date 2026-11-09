# Quickstart: proving the shared audit

In build order. Every command runs from the worktree root. Use a contract version of this branch
(`CASINO_VERSION=0.2.0-018-SNAPSHOT`), since other worktrees publish `0.2.0-SNAPSHOT` too.

## 1. The neighbours

```bash
just test                                   # contract (Routes) and the modules tool (AUDIT_BRANDS, reidentify_<slug>)
just modules validate --all
just service wallet test                    # records.feature: adjustment, refused bet, captured deposit, repeated key
just service responsible-gambling test      # records.feature: deposit limit
just service players test                   # records.feature: email change; the erasure workflow's records
```

Expected: each green; `records.feature`'s support-agent scenario reported ignored, "spec 017 is not
built".

## 2. The service, without a database

```bash
just audit-test
```

The pure suites (`KeepSuite`, `IdsSuite`, `StoredSuite` with its no-real-id property, `QuerySuite`,
`ExportSuite`, `RetentionSuite`), the entity and workflow kits, and `GherkinSuite` over the five
`features/audit/*.feature` other than `records.feature` (§1). Expected: green; the installation scenarios for rollbacks, restores and
grants ignored with "ankka publishes no project events (research R7)".

## 3. The integration kit (Docker)

```bash
just audit-it
```

`AnkkaTestKit` over Postgres with an `InMemoryBroker` carrying two brands' `platform.audit` and
`platform.modules` and `payments`' `platform.audit`, a `FakeKeycloak` and the in-memory object store.

| Suite | Shows |
|---|---|
| `IngestSuite` | two brands and payments read; kept and passed over; quarantine of each kind; redelivery changes nothing (US2, FR-007–FR-012) |
| `LeakSuite` | every real id used in the run is absent from every row, every answer, every export and the captured log; only `Identity` holds them (SC-002) |
| `QuerySuite` (it) | each role's scope, the filters, the cursor, the records of queries, no recursion (US3, FR-013, FR-015) |
| `ExportSuite` (it) | CSV and JSON lines, the signed URL, the two records (FR-014) |
| `ReidentifySuite` | both directions, the refusal, both recorded and visible to the brand (US4) |
| `InstallationSuite` | module changes and erasure states as installation records beside the brand's (US5) |
| `RolesSuite` | grant, revoke, an outside holding found once (FR-016) |
| `DurabilitySuite` | no route changes or deletes; the sweep removes only what is past its brand's retention; retention raised and refused lower; a raised generation re-reads with nothing changed (US6, SC-008) |
| `ParitySuite` | the six rows of contracts/recording.md |

## 4. Performance

```bash
just audit-perf
```

A generated brand mix (bets and wins at 500 bets a second; actions at 200 a second) for five minutes
over Postgres: SC-003's lag under 30 seconds, the share kept printed, then SC-004 against the store at
a year of that brand's actions: first page under 2 seconds; a month's export under 10 minutes. The
share kept and the year's row count are written to `services/audit/PERF.md`.

## 5. On kind (optional)

```bash
just modules apply goldvibe --installation dev-kind
brands apply shared/reasoner/manifest.json --installation dev-kind
```

Only with `--context kind-…` (the default context is production). Drive an operator's adjustment on
the brand, then `GET /v1/audit-records?brand=goldvibe` as a brand compliance officer; read
`/v1/audit-status`.
